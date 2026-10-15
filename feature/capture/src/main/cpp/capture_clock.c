/*
 * NetLogger: packet timestamps for native capture (Phase 3b).
 * Copyright (C) 2026 NetLogger contributors. Licensed under the GNU GPL v3 (see LICENSE).
 */
#include "capture_clock.h"

#include <time.h>

#define CLOCK_INIT_TRIES 5

static int64_t read_ns(clockid_t id) {
    struct timespec ts;
    if (clock_gettime(id, &ts) != 0) return -1;
    return (int64_t) ts.tv_sec * 1000000000 + ts.tv_nsec;
}

int64_t capture_clock_boot_ns(void) { return read_ns(CLOCK_BOOTTIME); }

int capture_clock_init(capture_clock_t *c) {
    int found = 0;
    for (int i = 0; i < CLOCK_INIT_TRIES; i++) {
        int64_t wall1 = read_ns(CLOCK_REALTIME);
        int64_t boot = read_ns(CLOCK_BOOTTIME);
        int64_t wall2 = read_ns(CLOCK_REALTIME);
        if (wall1 < 0 || boot < 0 || wall2 < wall1) continue;
        int64_t uncertainty = wall2 - wall1;
        if (!found || uncertainty < c->uncertainty_ns) {
            /* The midpoint of the two wall reads is the best estimate of the wall time at the boot read. */
            c->wall_minus_boot_ns = wall1 + uncertainty / 2 - boot;
            c->uncertainty_ns = uncertainty;
            found = 1;
        }
    }
    return found ? 0 : -1;
}
