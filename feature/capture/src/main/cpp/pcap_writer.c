/*
 * NetLogger: buffered pcap writer for native capture (Phase 3b).
 * Copyright (C) 2026 NetLogger contributors. Licensed under the GNU GPL v3 (see LICENSE).
 */
#include "pcap_writer.h"

#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

struct pcap_writer {
    int fd;
    uint32_t snaplen;
    uint8_t *buf;
    size_t buf_size;
    size_t buf_len;
    uint64_t buf_records;      /* records in buf, dropped together if its write fails */
    uint64_t flush_interval_ns;
    uint64_t first_buffered_ts_ns; /* timestamp of the oldest record in buf (valid when buf_records > 0) */
    pcap_writer_stats_t stats;
};

/* write() until done; retries EINTR and short writes. Returns 0 or -1 with errno set. */
static int write_all(int fd, const uint8_t *data, size_t len) {
    while (len > 0) {
        ssize_t n = write(fd, data, len);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (n == 0) {
            errno = EIO;
            return -1;
        }
        data += n;
        len -= (size_t) n;
    }
    return 0;
}

static void put_u32(uint8_t *p, uint32_t v) { memcpy(p, &v, sizeof(v)); }

static void put_u16(uint8_t *p, uint16_t v) { memcpy(p, &v, sizeof(v)); }

pcap_writer_t *pcap_writer_open(int fd, uint32_t snaplen, size_t buf_size, uint64_t flush_interval_ns) {
    if (fd < 0 || snaplen == 0 || buf_size < PCAP_RECORD_HEADER_LEN) return NULL;
    pcap_writer_t *w = calloc(1, sizeof(*w));
    if (w == NULL) return NULL;
    w->buf = malloc(buf_size);
    if (w->buf == NULL) {
        free(w);
        return NULL;
    }
    w->fd = fd;
    w->snaplen = snaplen;
    w->buf_size = buf_size;
    w->flush_interval_ns = flush_interval_ns;

    uint8_t header[PCAP_FILE_HEADER_LEN];
    put_u32(header, PCAP_MAGIC_NS);
    put_u16(header + 4, PCAP_VERSION_MAJOR);
    put_u16(header + 6, PCAP_VERSION_MINOR);
    put_u32(header + 8, 0);  /* thiszone: timestamps are UTC */
    put_u32(header + 12, 0); /* sigfigs */
    put_u32(header + 16, snaplen);
    put_u32(header + 20, PCAP_LINKTYPE_RAW);
    if (write_all(fd, header, sizeof(header)) != 0) {
        free(w->buf);
        free(w);
        return NULL;
    }
    return w;
}

int pcap_writer_flush(pcap_writer_t *w) {
    if (w->buf_len == 0) return 0;
    int rc = write_all(w->fd, w->buf, w->buf_len);
    if (rc != 0) {
        w->stats.last_errno = errno;
        w->stats.dropped += w->buf_records;
        w->stats.packets -= w->buf_records;
    } else {
        w->stats.flushes++;
    }
    w->buf_len = 0;
    w->buf_records = 0;
    return rc;
}

int pcap_writer_write(pcap_writer_t *w, uint64_t ts_ns, const uint8_t *pkt, uint32_t len) {
    uint32_t caplen = len < w->snaplen ? len : w->snaplen;
    size_t record_len = PCAP_RECORD_HEADER_LEN + (size_t) caplen;

    uint8_t header[PCAP_RECORD_HEADER_LEN];
    put_u32(header, (uint32_t) (ts_ns / 1000000000u));
    put_u32(header + 4, (uint32_t) (ts_ns % 1000000000u));
    put_u32(header + 8, caplen);
    put_u32(header + 12, len);

    if (caplen < len) w->stats.truncated++;

    if (w->buf_len + record_len > w->buf_size && pcap_writer_flush(w) != 0) {
        /* The buffered records are lost; still try this one below. */
    }
    if (record_len > w->buf_size) {
        /* Bigger than the whole buffer (buffer is empty now): write it directly. */
        if (write_all(w->fd, header, sizeof(header)) != 0 || write_all(w->fd, pkt, caplen) != 0) {
            w->stats.last_errno = errno;
            w->stats.dropped++;
            return -1;
        }
        w->stats.packets++;
        w->stats.bytes += caplen;
        w->stats.flushes++;
        return 0;
    }

    memcpy(w->buf + w->buf_len, header, sizeof(header));
    memcpy(w->buf + w->buf_len + sizeof(header), pkt, caplen);
    w->buf_len += record_len;
    w->buf_records++;
    w->stats.packets++;
    w->stats.bytes += caplen;

    if (w->buf_records == 1) {
        w->first_buffered_ts_ns = ts_ns;
    } else if (w->flush_interval_ns > 0 && ts_ns > w->first_buffered_ts_ns
               && ts_ns - w->first_buffered_ts_ns >= w->flush_interval_ns) {
        return pcap_writer_flush(w);
    }
    return 0;
}

int pcap_writer_close(pcap_writer_t *w) {
    if (w == NULL) return 0;
    int rc = pcap_writer_flush(w);
    if (close(w->fd) != 0 && rc == 0) {
        w->stats.last_errno = errno;
        rc = -1;
    }
    free(w->buf);
    free(w);
    return rc;
}

void pcap_writer_get_stats(const pcap_writer_t *w, pcap_writer_stats_t *out) { *out = w->stats; }
