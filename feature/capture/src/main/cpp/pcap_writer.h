/*
 * NetLogger: buffered pcap writer for native capture (Phase 3b).
 * Copyright (C) 2026 NetLogger contributors. Licensed under the GNU GPL v3 (see LICENSE).
 *
 * File format: classic pcap with nanosecond timestamps (magic 0xa1b23c4d, version 2.4), host byte order,
 * link type LINKTYPE_RAW (101): every record is one IPv4 or IPv6 packet as read from or written to the tun.
 * Wireshark and tshark open it directly. See docs/decisions/002-native-capture.md (D5).
 */
#ifndef NETCAPTURE_PCAP_WRITER_H
#define NETCAPTURE_PCAP_WRITER_H

#include <stddef.h>
#include <stdint.h>

#define PCAP_MAGIC_NS 0xa1b23c4du
#define PCAP_VERSION_MAJOR 2
#define PCAP_VERSION_MINOR 4
#define PCAP_LINKTYPE_RAW 101
#define PCAP_FILE_HEADER_LEN 24
#define PCAP_RECORD_HEADER_LEN 16

typedef struct pcap_writer pcap_writer_t;

typedef struct {
    uint64_t packets;     /* records handed to the writer and written or buffered */
    uint64_t bytes;       /* captured bytes of those records (after snaplen truncation), headers excluded */
    uint64_t truncated;   /* records cut to snaplen */
    uint64_t dropped;     /* records lost because a write failed */
    uint64_t flushes;     /* write() rounds of the buffer */
    int last_errno;       /* errno of the last failed write, 0 if none */
} pcap_writer_stats_t;

/*
 * Takes ownership of [fd] (closed by pcap_writer_close) and writes the file header at once.
 * [snaplen]: longest record stored; longer packets are truncated (orig_len keeps the real length).
 * [buf_size]: write buffer in bytes. [flush_interval_ns]: a record whose timestamp is at least this far past the
 * oldest buffered record flushes the buffer, so a crash loses at most about that much traffic (0 = flush only when
 * full or asked). Records only trigger this check: the caller flushes when traffic pauses.
 * Returns NULL (fd left open) when the header can't be written or memory is short.
 */
pcap_writer_t *pcap_writer_open(int fd, uint32_t snaplen, size_t buf_size, uint64_t flush_interval_ns);

/*
 * Appends one record with timestamp [ts_ns] (nanoseconds since the Unix epoch).
 * Returns 0, or -1 when the record was dropped because a write failed (counted in stats.dropped).
 */
int pcap_writer_write(pcap_writer_t *w, uint64_t ts_ns, const uint8_t *pkt, uint32_t len);

/* Writes out the buffer. Returns 0, or -1 on a write error (the buffered records are counted as dropped). */
int pcap_writer_flush(pcap_writer_t *w);

/* Flushes, closes the fd and frees [w]. Returns 0, or -1 if the flush or close failed. [w] may be NULL. */
int pcap_writer_close(pcap_writer_t *w);

void pcap_writer_get_stats(const pcap_writer_t *w, pcap_writer_stats_t *out);

#endif
