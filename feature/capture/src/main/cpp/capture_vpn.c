/*
 * NetLogger: native capture loop (Phase 3b).
 * Copyright (C) 2026 NetLogger contributors. Licensed under the GNU GPL v3 (see LICENSE).
 *
 * The loop follows the structure of PCAPdroid's run_vpn() (capture_vpn.c, GPL v3, studied, not copied): one
 * select() over the tun and zdtun's sockets; a readable tun is handled first and zdtun's sockets only in rounds
 * without a tun packet, so a connection that a forwarded packet closed is never handled from a stale fd set.
 */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE /* pipe2 */
#endif
#include "capture_vpn.h"

#include <errno.h>
#include <fcntl.h>
#include <netinet/tcp.h>
#include <pthread.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/select.h>
#include <unistd.h>

#include "third_party/zdtun/zdtun.h"

#define SELECT_TIMEOUT_MS 250
#define PURGE_INTERVAL_NS (5 * 1000000000LL)
#define PUBLISH_INTERVAL_NS (100 * 1000000LL)
#define TUN_BUFFER_BYTES 65536

struct capture_vpn {
    pthread_mutex_t lock;
    int stop_pipe[2];
    int stop_requested;          /* under lock */
    int ran;                     /* run may be called once */
    capture_vpn_stats_t published; /* under lock */

    /* Owned by the loop thread. */
    const capture_vpn_config_t *config;
    capture_vpn_stats_t live;
    pcap_writer_t *pcap;
    int looping;                 /* 0 while zdtun is finalized: its last packets are not sent to the app */
    int tun_failed;
    uint8_t buffer[TUN_BUFFER_BYTES];
};

capture_vpn_t *capture_vpn_create(void) {
    capture_vpn_t *cv = calloc(1, sizeof(*cv));
    if (cv == NULL) return NULL;
    if (pipe2(cv->stop_pipe, O_CLOEXEC | O_NONBLOCK) != 0) {
        free(cv);
        return NULL;
    }
    pthread_mutex_init(&cv->lock, NULL);
    return cv;
}

void capture_vpn_stop(capture_vpn_t *cv) {
    pthread_mutex_lock(&cv->lock);
    cv->stop_requested = 1;
    ssize_t ignored = write(cv->stop_pipe[1], "s", 1); /* a full pipe already means "stop" */
    (void) ignored;
    pthread_mutex_unlock(&cv->lock);
}

void capture_vpn_get_stats(capture_vpn_t *cv, capture_vpn_stats_t *out) {
    pthread_mutex_lock(&cv->lock);
    *out = cv->published;
    pthread_mutex_unlock(&cv->lock);
}

void capture_vpn_destroy(capture_vpn_t *cv) {
    if (cv == NULL) return;
    close(cv->stop_pipe[0]);
    close(cv->stop_pipe[1]);
    pthread_mutex_destroy(&cv->lock);
    free(cv);
}

static uint64_t now_ts(const capture_vpn_t *cv) { return capture_clock_ts_ns(&cv->live.clock, capture_clock_boot_ns()); }

static void publish(capture_vpn_t *cv, zdtun_t *zdt) {
    if (cv->pcap != NULL) pcap_writer_get_stats(cv->pcap, &cv->live.pcap);
    if (zdt != NULL) cv->live.conns_active = (uint64_t) zdtun_get_num_connections(zdt);
    pthread_mutex_lock(&cv->lock);
    cv->published = cv->live;
    pthread_mutex_unlock(&cv->lock);
}

/* --- zdtun callbacks ------------------------------------------------------------------------------------------- */

/* zdtun -> app: write the packet to the tun, then to the pcap. */
static int send_to_tun(zdtun_t *zdt, zdtun_pkt_t *pkt, const zdtun_conn_t *conn) {
    (void) conn;
    capture_vpn_t *cv = zdtun_userdata(zdt);
    if (!cv->looping) return 0;
    ssize_t n = write(cv->config->tun_fd, pkt->buf, pkt->len);
    if (n == (ssize_t) pkt->len) {
        cv->live.tun_tx_packets++;
        cv->live.tun_tx_bytes += pkt->len;
        pcap_writer_write(cv->pcap, now_ts(cv), (const uint8_t *) pkt->buf, pkt->len);
        return 0;
    }
    cv->live.tun_tx_errors++;
    /* ENOBUFS: the tun queue is full, this connection is closed (-1). EIO/EBADF: the tun is gone. */
    if (n < 0 && (errno == EIO || errno == EBADF)) cv->tun_failed = 1;
    return -1;
}

static void on_socket_open(zdtun_t *zdt, socket_t sock) {
    capture_vpn_t *cv = zdtun_userdata(zdt);
    const capture_vpn_config_t *c = cv->config;
    if (c->protect_socket != NULL && c->protect_socket(c->userdata, sock) != 0) cv->live.protect_failed++;
}

static int on_connection_open(zdtun_t *zdt, zdtun_conn_t *conn) {
    capture_vpn_t *cv = zdtun_userdata(zdt);
    switch (zdtun_conn_get_5tuple(conn)->ipproto) {
        case IPPROTO_TCP: cv->live.conns_tcp++; break;
        case IPPROTO_UDP: cv->live.conns_udp++; break;
        default: cv->live.conns_icmp++; break;
    }
    return 0; /* never block */
}

/* --- loop ------------------------------------------------------------------------------------------------------ */

/* app -> network: capture the packet, then forward it with zdtun. */
static void handle_tun_packet(capture_vpn_t *cv, zdtun_t *zdt, int len) {
    cv->live.tun_rx_packets++;
    cv->live.tun_rx_bytes += (uint64_t) len;
    pcap_writer_write(cv->pcap, now_ts(cv), cv->buffer, (uint32_t) len);

    zdtun_pkt_t pkt;
    if (zdtun_parse_pkt(zdt, (const char *) cv->buffer, (uint16_t) len, &pkt) != 0) {
        cv->live.unparsed++;
        return;
    }
    if (pkt.flags & ZDTUN_PKT_IS_FRAGMENT) {
        cv->live.fragments++;
        return;
    }
    if (pkt.tuple.ipver == 6 && !cv->config->ipv6_enabled) {
        cv->live.ipver_disabled++;
        return;
    }
    /* A TCP segment other than a SYN belongs to an existing connection: never create one for it. */
    int tcp_established = pkt.tuple.ipproto == IPPROTO_TCP
        && (!(pkt.tcp->th_flags & TH_SYN) || (pkt.tcp->th_flags & TH_ACK));
    zdtun_conn_t *conn = zdtun_lookup(zdt, &pkt.tuple, !tcp_established);
    if (conn == NULL) {
        if (tcp_established) cv->live.tcp_without_conn++;
        else cv->live.conns_failed++;
        return;
    }
    if (zdtun_forward(zdt, &pkt, conn) != 0) {
        cv->live.conns_failed++;
        zdtun_conn_close(zdt, conn, CONN_STATUS_ERROR);
    }
}

/* Ends the run: zdtun first (its last packets are not sent), then the pcap; publishes the final counters. */
static int finish(capture_vpn_t *cv, zdtun_t *zdt, int reason) {
    cv->looping = 0;
    if (zdt != NULL) {
        cv->live.conns_active = 0;
        zdtun_finalize(zdt);
    }
    if (cv->pcap != NULL) {
        pcap_writer_flush(cv->pcap);
        pcap_writer_get_stats(cv->pcap, &cv->live.pcap);
        if (pcap_writer_close(cv->pcap) != 0 && cv->live.pcap.last_errno == 0) cv->live.pcap.last_errno = errno;
        cv->pcap = NULL;
    }
    cv->live.running = 0;
    cv->live.exit_reason = reason;
    cv->live.stop_boot_ns = capture_clock_boot_ns();
    publish(cv, NULL);
    return reason;
}

int capture_vpn_run(capture_vpn_t *cv, const capture_vpn_config_t *config) {
    cv->config = config;
    memset(&cv->live, 0, sizeof(cv->live));

    pthread_mutex_lock(&cv->lock);
    int stop = cv->stop_requested || cv->ran;
    cv->ran = 1;
    pthread_mutex_unlock(&cv->lock);
    if (stop) {
        close(config->pcap_fd);
        return finish(cv, NULL, CAPTURE_EXIT_STOPPED);
    }

    /* A broken forwarding socket must be an EPIPE error, not a signal that ends the process. */
    signal(SIGPIPE, SIG_IGN);

    if (config->tun_fd < 0 || config->tun_fd >= FD_SETSIZE || cv->stop_pipe[0] >= FD_SETSIZE
        || capture_clock_init(&cv->live.clock) != 0) {
        close(config->pcap_fd);
        return finish(cv, NULL, CAPTURE_EXIT_INIT_ERROR);
    }
    cv->live.start_boot_ns = capture_clock_boot_ns();

    cv->pcap = pcap_writer_open(config->pcap_fd, config->snaplen, config->pcap_buffer_bytes,
                                config->pcap_flush_interval_ns);
    if (cv->pcap == NULL) {
        cv->live.pcap.last_errno = errno;
        close(config->pcap_fd);
        return finish(cv, NULL, CAPTURE_EXIT_PCAP_ERROR);
    }

    zdtun_callbacks_t callbacks = {
        .send_client = send_to_tun,
        .on_socket_open = on_socket_open,
        .on_connection_open = on_connection_open,
    };
    zdtun_t *zdt = zdtun_init(&callbacks, cv);
    if (zdt == NULL) return finish(cv, NULL, CAPTURE_EXIT_INIT_ERROR);
    zdtun_set_mtu(zdt, config->mtu);

    cv->looping = 1;
    cv->live.running = 1;
    publish(cv, zdt);

    int64_t now = capture_clock_boot_ns();
    int64_t next_purge = now + PURGE_INTERVAL_NS;
    int64_t next_publish = now + PUBLISH_INTERVAL_NS;
    int64_t last_flush = now;
    int reason;

    for (;;) {
        fd_set rd, wr;
        int max_fd = 0;
        FD_ZERO(&rd);
        FD_ZERO(&wr);
        zdtun_fds(zdt, &max_fd, &rd, &wr);
        FD_SET(config->tun_fd, &rd);
        FD_SET(cv->stop_pipe[0], &rd);
        if (config->tun_fd > max_fd) max_fd = config->tun_fd;
        if (cv->stop_pipe[0] > max_fd) max_fd = cv->stop_pipe[0];

        struct timeval timeout = { .tv_sec = 0, .tv_usec = SELECT_TIMEOUT_MS * 1000 };
        int ready = select(max_fd + 1, &rd, &wr, NULL, &timeout);
        if (ready < 0) {
            if (errno == EINTR) continue;
            reason = CAPTURE_EXIT_SELECT_ERROR;
            break;
        }
        if (FD_ISSET(cv->stop_pipe[0], &rd)) {
            reason = CAPTURE_EXIT_STOPPED;
            break;
        }
        if (FD_ISSET(config->tun_fd, &rd)) {
            ssize_t n = read(config->tun_fd, cv->buffer, sizeof(cv->buffer));
            if (n > 0) {
                handle_tun_packet(cv, zdt, (int) n);
            } else if (n == 0 || (errno != EINTR && errno != EAGAIN)) {
                reason = CAPTURE_EXIT_TUN_CLOSED;
                break;
            }
        } else if (ready > 0) {
            zdtun_handle_fd(zdt, &rd, &wr);
        }
        if (cv->tun_failed) {
            reason = CAPTURE_EXIT_TUN_CLOSED;
            break;
        }

        now = capture_clock_boot_ns();
        /* The writer flushes on packets; this covers a quiet link. */
        if (config->pcap_flush_interval_ns > 0 && now - last_flush >= (int64_t) config->pcap_flush_interval_ns) {
            pcap_writer_flush(cv->pcap);
            last_flush = now;
        }
        if (now >= next_purge) {
            zdtun_purge_expired(zdt);
            next_purge = now + PURGE_INTERVAL_NS;
        }
        if (now >= next_publish) {
            publish(cv, zdt);
            next_publish = now + PUBLISH_INTERVAL_NS;
        }
    }
    return finish(cv, zdt, reason);
}
