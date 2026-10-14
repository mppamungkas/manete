/*
 * NetLogger: packet timestamps for native capture (Phase 3b).
 * Copyright (C) 2026 NetLogger contributors. Licensed under the GNU GPL v3 (see LICENSE).
 *
 * A packet's timestamp is CLOCK_BOOTTIME (the clock of SystemClock.elapsedRealtimeNanos(), NetLogger's
 * elapsedRealtimeNs) plus one wall-clock offset taken at capture start. Timestamps therefore never jump when the
 * wall clock is changed, and pcap time minus the offset is exactly elapsedRealtimeNs.
 * See docs/decisions/002-native-capture.md (D5).
 */
#ifndef NETCAPTURE_CAPTURE_CLOCK_H
#define NETCAPTURE_CAPTURE_CLOCK_H

#include <stdint.h>

typedef struct {
    int64_t wall_minus_boot_ns; /* CLOCK_REALTIME - CLOCK_BOOTTIME at init */
    int64_t uncertainty_ns;     /* time between the two CLOCK_REALTIME reads around the CLOCK_BOOTTIME read */
} capture_clock_t;

/* CLOCK_BOOTTIME in nanoseconds (= elapsedRealtimeNanos), or -1 if it can't be read. */
int64_t capture_clock_boot_ns(void);

/* Measures the offset (best of a few tries). Returns 0, or -1 if a clock can't be read. */
int capture_clock_init(capture_clock_t *c);

/* Timestamp (ns since the Unix epoch) of a packet seen at [boot_ns]. */
static inline uint64_t capture_clock_ts_ns(const capture_clock_t *c, int64_t boot_ns) {
    return (uint64_t) (boot_ns + c->wall_minus_boot_ns);
}

#endif
