/*
 * NetLogger: host tests for the capture loop (capture_vpn.c + zdtun). A SOCK_SEQPACKET socketpair stands in for the
 * tun (packet boundaries kept, EOF when the other end closes); a UDP echo server on 127.0.0.1 is the "Internet".
 * Copyright (C) 2026 NetLogger contributors. Licensed under the GNU GPL v3 (see LICENSE).
 */
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#include "capture_vpn.h"

static int failures;
static const char *current;

#define CHECK(cond) do { \
        if (!(cond)) { fprintf(stderr, "FAIL %s:%d [%s] %s\n", __FILE__, __LINE__, current, #cond); failures++; } \
    } while (0)
#define CHECK_EQ(a, b) do { \
        long long _a = (long long) (a), _b = (long long) (b); \
        if (_a != _b) { \
            fprintf(stderr, "FAIL %s:%d [%s] %s == %s (%lld != %lld)\n", __FILE__, __LINE__, current, #a, #b, _a, _b); \
            failures++; \
        } \
    } while (0)

/* --- packets ----------------------------------------------------------------------------------------------------- */

static uint16_t ip_checksum(const uint8_t *p, size_t len) {
    uint32_t sum = 0;
    for (size_t i = 0; i + 1 < len; i += 2) sum += (uint32_t) (p[i] << 8 | p[i + 1]);
    if (len & 1) sum += (uint32_t) (p[len - 1] << 8);
    while (sum >> 16) sum = (sum & 0xffff) + (sum >> 16);
    return (uint16_t) ~sum;
}

/* IPv4 header (20 bytes) + [l4_len] bytes of payload already at p + 20. Returns the total length. */
static size_t ipv4(uint8_t *p, uint8_t proto, const char *src, const char *dst, size_t l4_len) {
    size_t total = 20 + l4_len;
    p[0] = 0x45;
    p[1] = 0;
    p[2] = (uint8_t) (total >> 8);
    p[3] = (uint8_t) total;
    p[4] = 0x12; p[5] = 0x34;  /* id */
    p[6] = 0x40; p[7] = 0;     /* DF, no fragment */
    p[8] = 64;
    p[9] = proto;
    p[10] = p[11] = 0;
    inet_pton(AF_INET, src, p + 12);
    inet_pton(AF_INET, dst, p + 16);
    uint16_t sum = ip_checksum(p, 20);
    p[10] = (uint8_t) (sum >> 8);
    p[11] = (uint8_t) sum;
    return total;
}

static size_t udp4(uint8_t *p, const char *src, uint16_t sport, const char *dst, uint16_t dport,
                   const char *payload) {
    size_t plen = strlen(payload);
    uint8_t *u = p + 20;
    u[0] = (uint8_t) (sport >> 8); u[1] = (uint8_t) sport;
    u[2] = (uint8_t) (dport >> 8); u[3] = (uint8_t) dport;
    u[4] = (uint8_t) ((8 + plen) >> 8); u[5] = (uint8_t) (8 + plen);
    u[6] = u[7] = 0;               /* no UDP checksum (allowed for IPv4) */
    memcpy(u + 8, payload, plen);
    return ipv4(p, IPPROTO_UDP, src, dst, 8 + plen);
}

/* --- echo server and loop thread ---------------------------------------------------------------------------------- */

typedef struct { int sock; uint16_t port; pthread_t thread; } echo_t;

static void *echo_main(void *arg) {
    echo_t *e = arg;
    uint8_t buf[2048];
    for (;;) {
        struct sockaddr_in from;
        socklen_t fl = sizeof(from);
        ssize_t n = recvfrom(e->sock, buf, sizeof(buf), 0, (struct sockaddr *) &from, &fl);
        if (n <= 0) return NULL;
        if (n == 4 && memcmp(buf, "quit", 4) == 0) return NULL;
        sendto(e->sock, buf, (size_t) n, 0, (struct sockaddr *) &from, fl);
    }
}

static void echo_start(echo_t *e) {
    e->sock = socket(AF_INET, SOCK_DGRAM, 0);
    struct sockaddr_in a = { .sin_family = AF_INET, .sin_addr.s_addr = htonl(INADDR_LOOPBACK) };
    bind(e->sock, (struct sockaddr *) &a, sizeof(a));
    socklen_t al = sizeof(a);
    getsockname(e->sock, (struct sockaddr *) &a, &al);
    e->port = ntohs(a.sin_port);
    pthread_create(&e->thread, NULL, echo_main, e);
}

static void echo_stop(echo_t *e) {
    int s = socket(AF_INET, SOCK_DGRAM, 0);
    struct sockaddr_in a = { .sin_family = AF_INET, .sin_port = htons(e->port), .sin_addr.s_addr = htonl(INADDR_LOOPBACK) };
    sendto(s, "quit", 4, 0, (struct sockaddr *) &a, sizeof(a));
    close(s);
    pthread_join(e->thread, NULL);
    close(e->sock);
}

/* The fd was closed (capture_vpn_run takes over the pcap fd). */
static int fcntl_closed(int fd) { return fcntl(fd, F_GETFD) == -1 && errno == EBADF; }

static int protect_calls;
static int count_protect(void *userdata, int sock) {
    (void) userdata;
    (void) sock;
    protect_calls++;
    return 0;
}

typedef struct { capture_vpn_t *cv; capture_vpn_config_t config; int result; pthread_t thread; } loop_t;

static void *loop_main(void *arg) {
    loop_t *l = arg;
    l->result = capture_vpn_run(l->cv, &l->config);
    return NULL;
}

static char pcap_path[64];

static void loop_start(loop_t *l, int tun_fd) {
    strcpy(pcap_path, "/tmp/netcapture_vpn_XXXXXX");
    int pcap_fd = mkstemp(pcap_path);
    l->cv = capture_vpn_create();
    l->config = (capture_vpn_config_t) {
        .tun_fd = tun_fd, .pcap_fd = pcap_fd, .mtu = 1500, .snaplen = 65535, .pcap_buffer_bytes = 1 << 16,
        .pcap_flush_interval_ns = 1000000000ull, .ipv6_enabled = 0, .protect_socket = count_protect,
    };
    pthread_create(&l->thread, NULL, loop_main, l);
}

static int loop_join(loop_t *l) {
    pthread_join(l->thread, NULL);
    return l->result;
}

/* Waits up to [ms] for a packet on [fd]; returns its length or -1. */
static ssize_t recv_wait(int fd, uint8_t *buf, size_t size, int ms) {
    struct pollfd p = { .fd = fd, .events = POLLIN };
    if (poll(&p, 1, ms) != 1) return -1;
    return read(fd, buf, size);
}

static long pcap_records(void) {
    FILE *f = fopen(pcap_path, "rb");
    if (f == NULL) return -1;
    uint8_t h[24];
    long n = -1;
    if (fread(h, 1, 24, f) == 24) {
        n = 0;
        uint8_t r[16];
        while (fread(r, 1, 16, f) == 16) {
            uint32_t caplen;
            memcpy(&caplen, r + 8, 4);
            if (fseek(f, caplen, SEEK_CUR) != 0) break;
            n++;
        }
    }
    fclose(f);
    return n;
}

/* --- tests ------------------------------------------------------------------------------------------------------- */

static void test_udp_round_trip(void) {
    current = "udp round trip";
    echo_t echo;
    echo_start(&echo);
    int tun[2];
    CHECK(socketpair(AF_UNIX, SOCK_SEQPACKET, 0, tun) == 0);
    loop_t l;
    protect_calls = 0;
    loop_start(&l, tun[0]);

    uint8_t pkt[256], reply[2048];
    size_t len = udp4(pkt, "10.111.222.1", 40000, "127.0.0.1", echo.port, "hello netlogger");
    CHECK(write(tun[1], pkt, len) == (ssize_t) len);
    ssize_t n = recv_wait(tun[1], reply, sizeof(reply), 3000);
    CHECK_EQ(n, (ssize_t) len);
    if (n == (ssize_t) len) {
        /* The reply as the app sees it: from the echo server back to the app's address and port. */
        CHECK_EQ(reply[0], 0x45);
        CHECK_EQ(reply[9], IPPROTO_UDP);
        CHECK(memcmp(reply + 12, pkt + 16, 4) == 0);
        CHECK(memcmp(reply + 16, pkt + 12, 4) == 0);
        CHECK(memcmp(reply + 20, pkt + 22, 2) == 0); /* source port = echo port */
        CHECK(memcmp(reply + 22, pkt + 20, 2) == 0); /* destination port = 40000 */
        CHECK(memcmp(reply + 28, "hello netlogger", 15) == 0);
        CHECK_EQ(ip_checksum(reply, 20), 0);         /* valid IPv4 header checksum */
    }

    /* Not forwarded, but captured: garbage, IPv6 while IPv6 is off, TCP data of an unknown connection. */
    uint8_t junk[] = { 0x00, 0x01, 0x02 };
    write(tun[1], junk, sizeof(junk));
    uint8_t v6[48] = { 0x60 };                /* IPv6 + empty UDP datagram, ::1 -> ::1 */
    v6[5] = 8; v6[6] = IPPROTO_UDP; v6[7] = 64;
    v6[8 + 15] = 1; v6[24 + 15] = 1;
    v6[40] = 0x9c; v6[41] = 0x42; v6[42] = 0x00; v6[43] = 0x35; v6[45] = 8;
    write(tun[1], v6, sizeof(v6));
    uint8_t tcp[40] = { 0 };
    tcp[20 + 12] = 0x50;                       /* data offset 5 */
    tcp[20 + 13] = 0x10;                       /* ACK only */
    tcp[20 + 0] = 0x9c; tcp[20 + 1] = 0x41;    /* 40001 */
    tcp[20 + 2] = 0x00; tcp[20 + 3] = 0x50;    /* 80 */
    size_t tl = ipv4(tcp, IPPROTO_TCP, "10.111.222.1", "127.0.0.1", 20);
    write(tun[1], tcp, tl);

    usleep(400 * 1000); /* two publish intervals */
    capture_vpn_stats_t s;
    capture_vpn_get_stats(l.cv, &s);
    CHECK_EQ(s.running, 1);
    CHECK_EQ(s.tun_rx_packets, 4);
    CHECK_EQ(s.tun_tx_packets, 1);
    CHECK_EQ(s.conns_udp, 1);
    CHECK_EQ(s.unparsed, 1);
    CHECK_EQ(s.ipver_disabled, 1);
    CHECK_EQ(s.tcp_without_conn, 1);
    CHECK_EQ(s.tun_tx_errors, 0);
    CHECK(s.start_boot_ns > 0);
    CHECK(protect_calls >= 1);

    capture_vpn_stop(l.cv);
    CHECK_EQ(loop_join(&l), CAPTURE_EXIT_STOPPED);
    capture_vpn_get_stats(l.cv, &s);
    CHECK_EQ(s.running, 0);
    CHECK_EQ(s.exit_reason, CAPTURE_EXIT_STOPPED);
    CHECK_EQ(s.pcap.packets, 5);  /* 4 from the app, 1 to it */
    CHECK_EQ(s.pcap.dropped, 0);
    CHECK_EQ(s.conns_active, 0);
    CHECK(s.stop_boot_ns >= s.start_boot_ns);
    CHECK_EQ(pcap_records(), 5);
    capture_vpn_destroy(l.cv);
    close(tun[0]);
    close(tun[1]);
    unlink(pcap_path);
    echo_stop(&echo);
}

static void test_tun_closed(void) {
    current = "tun closed";
    int tun[2];
    CHECK(socketpair(AF_UNIX, SOCK_SEQPACKET, 0, tun) == 0);
    loop_t l;
    loop_start(&l, tun[0]);
    usleep(100 * 1000);
    close(tun[1]); /* like a revoked VPN: the loop ends by itself */
    CHECK_EQ(loop_join(&l), CAPTURE_EXIT_TUN_CLOSED);
    CHECK_EQ(pcap_records(), 0); /* header only, still a valid file */
    capture_vpn_destroy(l.cv);
    close(tun[0]);
    unlink(pcap_path);
}

static void test_stop_before_run(void) {
    current = "stop before run";
    int tun[2];
    CHECK(socketpair(AF_UNIX, SOCK_SEQPACKET, 0, tun) == 0);
    loop_t l;
    strcpy(pcap_path, "/tmp/netcapture_vpn_XXXXXX");
    int pcap_fd = mkstemp(pcap_path);
    l.cv = capture_vpn_create();
    capture_vpn_stop(l.cv);
    l.config = (capture_vpn_config_t) { .tun_fd = tun[0], .pcap_fd = pcap_fd, .mtu = 1500, .snaplen = 65535,
                                        .pcap_buffer_bytes = 4096 };
    CHECK_EQ(capture_vpn_run(l.cv, &l.config), CAPTURE_EXIT_STOPPED);
    CHECK(fcntl_closed(pcap_fd));
    capture_vpn_destroy(l.cv);
    close(tun[0]);
    close(tun[1]);
    unlink(pcap_path);
}

int main(void) {
    signal(SIGPIPE, SIG_IGN);
    test_udp_round_trip();
    test_tun_closed();
    test_stop_before_run();
    if (failures > 0) {
        fprintf(stderr, "%d check(s) failed\n", failures);
        return 1;
    }
    printf("netcapture vpn tests passed\n");
    return 0;
}
