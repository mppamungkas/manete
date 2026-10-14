/*
 * NetLogger: host tests for pcap_writer and capture_clock.
 * Copyright (C) 2026 NetLogger contributors. Licensed under the GNU GPL v3 (see LICENSE).
 */
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include "capture_clock.h"
#include "pcap_writer.h"

static int failures;
static const char *current;

#define CHECK(cond) do { \
        if (!(cond)) { fprintf(stderr, "FAIL %s:%d [%s] %s\n", __FILE__, __LINE__, current, #cond); failures++; } \
    } while (0)
#define CHECK_EQ(a, b) do { \
        unsigned long long _a = (unsigned long long) (a), _b = (unsigned long long) (b); \
        if (_a != _b) { \
            fprintf(stderr, "FAIL %s:%d [%s] %s == %s (%llu != %llu)\n", __FILE__, __LINE__, current, #a, #b, _a, _b); \
            failures++; \
        } \
    } while (0)

/* --- helpers ---------------------------------------------------------------------------------------------------- */

static char path[64];

/* A fresh empty file; returns a write fd. */
static int temp_fd(void) {
    strcpy(path, "/tmp/netcapture_test_XXXXXX");
    int fd = mkstemp(path);
    if (fd < 0) { perror("mkstemp"); exit(2); }
    return fd;
}

static long file_size(void) {
    struct stat st;
    return stat(path, &st) == 0 ? (long) st.st_size : -1;
}

/* Reads the whole file at `path` into a malloc'd buffer. */
static uint8_t *read_file(size_t *len) {
    FILE *f = fopen(path, "rb");
    if (f == NULL) { perror("fopen"); exit(2); }
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    uint8_t *buf = malloc(n > 0 ? (size_t) n : 1);
    *len = fread(buf, 1, (size_t) n, f);
    fclose(f);
    return buf;
}

static uint32_t u32(const uint8_t *p) { uint32_t v; memcpy(&v, p, 4); return v; }
static uint16_t u16(const uint8_t *p) { uint16_t v; memcpy(&v, p, 2); return v; }

typedef struct { uint32_t sec, nsec, caplen, origlen; const uint8_t *data; } record_t;

/* Parses records after the file header; returns how many, -1 if the file is malformed. */
static int parse(const uint8_t *buf, size_t len, record_t *out, int max) {
    size_t off = PCAP_FILE_HEADER_LEN;
    int n = 0;
    while (off < len) {
        if (off + PCAP_RECORD_HEADER_LEN > len || n == max) return -1;
        record_t r = { u32(buf + off), u32(buf + off + 4), u32(buf + off + 8), u32(buf + off + 12), NULL };
        off += PCAP_RECORD_HEADER_LEN;
        if (off + r.caplen > len) return -1;
        r.data = buf + off;
        off += r.caplen;
        out[n++] = r;
    }
    return n;
}

static void fill(uint8_t *p, size_t len, uint8_t seed) {
    for (size_t i = 0; i < len; i++) p[i] = (uint8_t) (seed + i * 7);
}

/* --- tests ------------------------------------------------------------------------------------------------------ */

static void test_file_header(void) {
    current = "file header";
    int fd = temp_fd();
    pcap_writer_t *w = pcap_writer_open(fd, 65535, 4096, 0);
    CHECK(w != NULL);
    CHECK_EQ(file_size(), PCAP_FILE_HEADER_LEN); /* written at open, not buffered */
    CHECK_EQ(pcap_writer_close(w), 0);

    size_t len;
    uint8_t *buf = read_file(&len);
    CHECK_EQ(len, 24);
    CHECK_EQ(u32(buf), 0xa1b23c4d);
    CHECK_EQ(u16(buf + 4), 2);
    CHECK_EQ(u16(buf + 6), 4);
    CHECK_EQ(u32(buf + 8), 0);
    CHECK_EQ(u32(buf + 12), 0);
    CHECK_EQ(u32(buf + 16), 65535);
    CHECK_EQ(u32(buf + 20), 101);
    /* Little-endian hosts (all Android ABIs): the bytes Wireshark checks for nanosecond pcap. */
    static const uint8_t magic_le[] = { 0x4d, 0x3c, 0xb2, 0xa1 };
    CHECK(memcmp(buf, magic_le, 4) == 0);
    free(buf);
    unlink(path);
}

static void test_records_and_timestamps(void) {
    current = "records";
    int fd = temp_fd();
    pcap_writer_t *w = pcap_writer_open(fd, 65535, 4096, 0);
    uint8_t a[60], b[1500];
    fill(a, sizeof(a), 1);
    fill(b, sizeof(b), 2);
    uint64_t ts_a = 1760000000123456789ull; /* 2025-10-09, ns part 123456789 */
    uint64_t ts_b = ts_a + 999999999ull;    /* carries into the next second */
    CHECK_EQ(pcap_writer_write(w, ts_a, a, sizeof(a)), 0);
    CHECK_EQ(pcap_writer_write(w, ts_b, b, sizeof(b)), 0);

    pcap_writer_stats_t st;
    pcap_writer_get_stats(w, &st);
    CHECK_EQ(st.packets, 2);
    CHECK_EQ(st.bytes, 1560);
    CHECK_EQ(st.dropped, 0);
    CHECK_EQ(pcap_writer_close(w), 0);

    size_t len;
    uint8_t *buf = read_file(&len);
    record_t r[4];
    CHECK_EQ(parse(buf, len, r, 4), 2);
    CHECK_EQ(r[0].sec, 1760000000);
    CHECK_EQ(r[0].nsec, 123456789);
    CHECK_EQ(r[0].caplen, 60);
    CHECK_EQ(r[0].origlen, 60);
    CHECK(memcmp(r[0].data, a, sizeof(a)) == 0);
    CHECK_EQ(r[1].sec, 1760000001);
    CHECK_EQ(r[1].nsec, 123456788);
    CHECK_EQ(r[1].caplen, 1500);
    CHECK(memcmp(r[1].data, b, sizeof(b)) == 0);
    free(buf);
    unlink(path);
}

static void test_snaplen_truncates(void) {
    current = "snaplen";
    int fd = temp_fd();
    pcap_writer_t *w = pcap_writer_open(fd, 100, 4096, 0);
    uint8_t p[300];
    fill(p, sizeof(p), 3);
    pcap_writer_write(w, 1, p, sizeof(p));
    pcap_writer_write(w, 2, p, 100);
    pcap_writer_stats_t st;
    pcap_writer_get_stats(w, &st);
    CHECK_EQ(st.truncated, 1);
    CHECK_EQ(st.bytes, 200);
    pcap_writer_close(w);

    size_t len;
    uint8_t *buf = read_file(&len);
    record_t r[4];
    CHECK_EQ(parse(buf, len, r, 4), 2);
    CHECK_EQ(r[0].caplen, 100);
    CHECK_EQ(r[0].origlen, 300);
    CHECK(memcmp(r[0].data, p, 100) == 0);
    CHECK_EQ(r[1].caplen, 100);
    CHECK_EQ(r[1].origlen, 100);
    free(buf);
    unlink(path);
}

static void test_buffering_and_order(void) {
    current = "buffering";
    int fd = temp_fd();
    /* Buffer holds 3 records of 16 + 100 bytes. */
    pcap_writer_t *w = pcap_writer_open(fd, 65535, 3 * 116, 0);
    uint8_t p[100], big[1000];
    for (int i = 0; i < 3; i++) {
        fill(p, sizeof(p), (uint8_t) i);
        pcap_writer_write(w, (uint64_t) i, p, sizeof(p));
    }
    CHECK_EQ(file_size(), PCAP_FILE_HEADER_LEN); /* all three still buffered */
    fill(p, sizeof(p), 3);
    pcap_writer_write(w, 3, p, sizeof(p));       /* doesn't fit: the first three go out */
    CHECK_EQ(file_size(), PCAP_FILE_HEADER_LEN + 3 * 116);
    fill(big, sizeof(big), 4);
    pcap_writer_write(w, 4, big, sizeof(big));   /* bigger than the buffer: flush, then written directly */
    CHECK_EQ(file_size(), PCAP_FILE_HEADER_LEN + 4 * 116 + 1016);
    fill(p, sizeof(p), 5);
    pcap_writer_write(w, 5, p, sizeof(p));
    CHECK_EQ(pcap_writer_flush(w), 0);
    CHECK_EQ(file_size(), PCAP_FILE_HEADER_LEN + 5 * 116 + 1016);
    pcap_writer_close(w);

    size_t len;
    uint8_t *buf = read_file(&len);
    record_t r[8];
    CHECK_EQ(parse(buf, len, r, 8), 6);
    for (int i = 0; i < 6; i++) {
        CHECK_EQ(r[i].nsec, i); /* order kept */
        CHECK_EQ(r[i].data[0], (uint8_t) i);
    }
    CHECK_EQ(r[4].caplen, 1000);
    free(buf);
    unlink(path);
}

static void test_flush_interval(void) {
    current = "flush interval";
    int fd = temp_fd();
    const uint64_t second = 1000000000ull;
    pcap_writer_t *w = pcap_writer_open(fd, 65535, 1 << 20, second);
    uint8_t p[40];
    fill(p, sizeof(p), 9);
    uint64_t t0 = 1760000000ull * second;
    pcap_writer_write(w, t0, p, sizeof(p));
    pcap_writer_write(w, t0 + second / 2, p, sizeof(p));
    CHECK_EQ(file_size(), PCAP_FILE_HEADER_LEN);
    pcap_writer_write(w, t0 + second, p, sizeof(p)); /* 1 s after the oldest buffered record */
    CHECK_EQ(file_size(), PCAP_FILE_HEADER_LEN + 3 * 56);
    pcap_writer_write(w, t0 + second + 1, p, sizeof(p)); /* new oldest record: buffered again */
    CHECK_EQ(file_size(), PCAP_FILE_HEADER_LEN + 3 * 56);
    pcap_writer_write(w, t0 - second, p, sizeof(p)); /* earlier timestamp: no underflow, no flush */
    CHECK_EQ(file_size(), PCAP_FILE_HEADER_LEN + 3 * 56);
    pcap_writer_stats_t st;
    pcap_writer_get_stats(w, &st);
    CHECK_EQ(st.flushes, 1);
    CHECK_EQ(pcap_writer_close(w), 0);
    CHECK_EQ(file_size(), PCAP_FILE_HEADER_LEN + 5 * 56);
    unlink(path);
}

static void test_write_errors_are_counted(void) {
    current = "write errors";
    /* A read-only fd: the header write fails, open returns NULL and leaves the fd open. */
    int ro = open("/dev/null", O_RDONLY);
    CHECK(pcap_writer_open(ro, 65535, 4096, 0) == NULL);
    CHECK(fcntl(ro, F_GETFD) != -1);
    close(ro);

    /* A pipe whose reader is gone: writes after the header fail with EPIPE. */
    int fds[2];
    CHECK(pipe(fds) == 0);
    pcap_writer_t *w = pcap_writer_open(fds[1], 65535, 256, 0);
    CHECK(w != NULL);
    close(fds[0]);
    uint8_t p[100];
    fill(p, sizeof(p), 0);
    CHECK_EQ(pcap_writer_write(w, 1, p, sizeof(p)), 0); /* buffered */
    CHECK_EQ(pcap_writer_write(w, 2, p, sizeof(p)), 0); /* buffered */
    pcap_writer_write(w, 3, p, sizeof(p));              /* flush of the first two fails */
    uint8_t big[1000];
    fill(big, sizeof(big), 1);
    pcap_writer_stats_t st;
    pcap_writer_get_stats(w, &st);
    CHECK_EQ(st.dropped, 2);
    CHECK_EQ(st.packets, 1); /* the third record, in the buffer */
    /* Too big for the buffer: the buffer is flushed first (order), which drops the third record, then the direct
     * write fails as well. */
    CHECK_EQ(pcap_writer_write(w, 4, big, sizeof(big)), -1);
    pcap_writer_get_stats(w, &st);
    CHECK_EQ(st.dropped, 4);
    CHECK_EQ(st.packets, 0);
    CHECK_EQ(st.last_errno, EPIPE);
    CHECK_EQ(pcap_writer_write(w, 5, p, sizeof(p)), 0); /* buffered */
    CHECK_EQ(pcap_writer_close(w), -1);                 /* its flush fails */
    CHECK_EQ(pcap_writer_close(NULL), 0);
}

static void test_bad_arguments(void) {
    current = "bad arguments";
    CHECK(pcap_writer_open(-1, 65535, 4096, 0) == NULL);
    int fd = temp_fd();
    CHECK(pcap_writer_open(fd, 0, 4096, 0) == NULL);
    CHECK(pcap_writer_open(fd, 65535, 8, 0) == NULL);
    CHECK_EQ(file_size(), 0);
    close(fd);
    unlink(path);
}

static void test_clock(void) {
    current = "clock";
    capture_clock_t c;
    CHECK_EQ(capture_clock_init(&c), 0);
    CHECK(c.uncertainty_ns >= 0 && c.uncertainty_ns < 1000000); /* under 1 ms */

    struct timespec wall;
    clock_gettime(CLOCK_REALTIME, &wall);
    int64_t wall_ns = (int64_t) wall.tv_sec * 1000000000 + wall.tv_nsec;
    int64_t boot = capture_clock_boot_ns();
    CHECK(boot > 0);
    int64_t diff = (int64_t) capture_clock_ts_ns(&c, boot) - wall_ns;
    CHECK(diff > -5000000 && diff < 5000000); /* within 5 ms of the wall clock */

    /* Monotonic: later boot time, later timestamp, independent of the wall clock. */
    int64_t prev = boot;
    for (int i = 0; i < 1000; i++) {
        int64_t now = capture_clock_boot_ns();
        CHECK(now >= prev);
        CHECK(capture_clock_ts_ns(&c, now) >= capture_clock_ts_ns(&c, prev));
        prev = now;
    }

    /* Pure arithmetic: timestamp - offset gives elapsedRealtimeNs back. */
    capture_clock_t fixed = { .wall_minus_boot_ns = 1760000000000000000ll, .uncertainty_ns = 0 };
    CHECK_EQ(capture_clock_ts_ns(&fixed, 123456789), 1760000000123456789ull);
}

int main(void) {
    /* As in the capture process (zdtun needs it too): a broken pipe is an EPIPE error, not a signal. */
    signal(SIGPIPE, SIG_IGN);
    test_file_header();
    test_records_and_timestamps();
    test_snaplen_truncates();
    test_buffering_and_order();
    test_flush_interval();
    test_write_errors_are_counted();
    test_bad_arguments();
    test_clock();
    if (failures > 0) {
        fprintf(stderr, "%d check(s) failed\n", failures);
        return 1;
    }
    printf("netcapture host tests passed\n");
    return 0;
}
