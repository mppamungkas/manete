/*
 * NetLogger: native capture loop (Phase 3b). Reads IP packets from the VpnService tun, forwards them with zdtun over
 * normal (protected) sockets, writes every packet that crosses the tun to a pcap file.
 * Copyright (C) 2026 NetLogger contributors. Licensed under the GNU GPL v3 (see LICENSE).
 *
 * No JNI here (see netcapture_jni.c), so the loop also runs in host tests with a socketpair as the tun.
 * See docs/decisions/002-native-capture.md.
 */
#ifndef NETCAPTURE_CAPTURE_VPN_H
#define NETCAPTURE_CAPTURE_VPN_H

#include <stdint.h>

#include "capture_clock.h"
#include "pcap_writer.h"

typedef struct capture_vpn capture_vpn_t;

typedef struct {
    int tun_fd;                  /* packet-oriented, blocking; not closed by the loop (the VpnService owns it) */
    int pcap_fd;                 /* taken over: closed when the loop ends, also on early failure */
    int mtu;                     /* tun MTU, used by zdtun for the TCP MSS */
    uint32_t snaplen;
    uint32_t pcap_buffer_bytes;
    uint64_t pcap_flush_interval_ns;
    int ipv6_enabled;            /* IPv6 is routed into the tun; otherwise IPv6 packets are dropped */
    /* Exclude a forwarding socket from the VPN (VpnService.protect). Returns 0 on success. May be NULL in tests. */
    int (*protect_socket)(void *userdata, int socket);
    void *userdata;
} capture_vpn_config_t;

/* Why capture_vpn_run returned. */
enum {
    CAPTURE_EXIT_STOPPED = 0,     /* capture_vpn_stop */
    CAPTURE_EXIT_TUN_CLOSED = 1,  /* the tun was closed or failed (VPN revoked, another VPN started) */
    CAPTURE_EXIT_SELECT_ERROR = 2,
    CAPTURE_EXIT_INIT_ERROR = 3,  /* zdtun, pipe, clock or fd range */
    CAPTURE_EXIT_PCAP_ERROR = 4,  /* the pcap file could not be started */
};

/*
 * Snapshot of the counters. Every packet read from the tun is written to the pcap (also packets zdtun can't
 * forward); every packet zdtun sends to the app is written after it reached the tun.
 */
typedef struct {
    int running;
    int exit_reason;               /* valid when running == 0 after a run */
    uint64_t tun_rx_packets;       /* app -> network, read from the tun */
    uint64_t tun_rx_bytes;
    uint64_t tun_tx_packets;       /* network -> app, written to the tun */
    uint64_t tun_tx_bytes;
    uint64_t tun_tx_errors;        /* writes to the tun that failed (the packet is lost for the app) */
    uint64_t unparsed;             /* tun packets zdtun can't parse (captured, not forwarded) */
    uint64_t fragments;            /* IP fragments (captured, not forwarded: zdtun doesn't reassemble) */
    uint64_t ipver_disabled;       /* IPv6 packets while IPv6 is not routed */
    uint64_t tcp_without_conn;     /* TCP segments of connections opened before the capture (not forwarded) */
    uint64_t conns_failed;         /* connections zdtun could not create or forward */
    uint64_t protect_failed;       /* forwarding sockets VpnService.protect refused (their traffic would loop) */
    uint64_t conns_tcp;            /* connections opened since start */
    uint64_t conns_udp;
    uint64_t conns_icmp;
    uint64_t conns_active;
    pcap_writer_stats_t pcap;
    capture_clock_t clock;         /* the offset used for every packet timestamp */
    int64_t start_boot_ns;         /* CLOCK_BOOTTIME (= elapsedRealtimeNanos) at start, 0 before */
    int64_t stop_boot_ns;          /* at the end of the run, 0 while running */
} capture_vpn_stats_t;

/* Creates an idle capture (no threads). Returns NULL when out of memory. */
capture_vpn_t *capture_vpn_create(void);

/* Runs the loop on the calling thread until stopped or the tun fails. Returns a CAPTURE_EXIT_* reason. Once only. */
int capture_vpn_run(capture_vpn_t *cv, const capture_vpn_config_t *config);

/* Asks the loop to end soon; any thread, also before run starts (run then returns at once). */
void capture_vpn_stop(capture_vpn_t *cv);

/* Latest counters (published by the loop at least every 100 ms); any thread. */
void capture_vpn_get_stats(capture_vpn_t *cv, capture_vpn_stats_t *out);

/* Frees [cv]; only after run returned (or if it never ran). */
void capture_vpn_destroy(capture_vpn_t *cv);

#endif
