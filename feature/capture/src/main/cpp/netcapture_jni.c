/*
 * NetLogger: JNI entry points of libnetcapture (native traffic capture, Phase 3b).
 * Copyright (C) 2026 NetLogger contributors. Licensed under the GNU GPL v3 (see LICENSE).
 *
 * Kotlin side: id.my.pamslab.netlogger.feature.capture.vpn.NativeCapture. A capture is a handle (pointer to
 * capture_vpn_t) that Kotlin creates, runs on its own thread, stops from any thread and destroys after the run.
 */
#include <jni.h>
#include <stdint.h>
#include <unistd.h>

#include "capture_vpn.h"

#define NETCAPTURE_VERSION "0.3"

#if defined(__aarch64__)
#define NETCAPTURE_ABI "arm64-v8a"
#elif defined(__arm__)
#define NETCAPTURE_ABI "armeabi-v7a"
#elif defined(__x86_64__)
#define NETCAPTURE_ABI "x86_64"
#elif defined(__i386__)
#define NETCAPTURE_ABI "x86"
#else
#define NETCAPTURE_ABI "unknown"
#endif

#define JNI_FN(name) Java_id_my_pamslab_netlogger_feature_capture_vpn_NativeCapture_##name

/* Order of the LongArray returned by nativeStats; NativeCaptureStats.kt reads the same order. */
enum {
    S_RUNNING, S_EXIT_REASON, S_TUN_RX_PACKETS, S_TUN_RX_BYTES, S_TUN_TX_PACKETS, S_TUN_TX_BYTES, S_TUN_TX_ERRORS,
    S_UNPARSED, S_FRAGMENTS, S_IPVER_DISABLED, S_TCP_WITHOUT_CONN, S_CONNS_FAILED, S_PROTECT_FAILED, S_CONNS_TCP,
    S_CONNS_UDP, S_CONNS_ICMP, S_CONNS_ACTIVE, S_PCAP_PACKETS, S_PCAP_BYTES, S_PCAP_TRUNCATED, S_PCAP_DROPPED,
    S_PCAP_LAST_ERRNO, S_WALL_MINUS_BOOT_NS, S_CLOCK_UNCERTAINTY_NS, S_START_BOOT_NS, S_STOP_BOOT_NS, S_CONNS_UID_UNKNOWN, S_COUNT
};

/*
 * The Kotlin NativeCaptureCallbacks object. All calls happen on the loop thread, the thread that called nativeRun,
 * so env stays valid. An exception thrown by a callback is cleared and treated as a failure.
 */
typedef struct {
    JNIEnv *env;
    jobject callbacks;
    jmethodID protect;            /* boolean protect(int) */
    jmethodID connection_owner;   /* int connectionOwner(int, int, byte[], int, byte[], int) */
    jmethodID connection_closed;  /* void connectionClosed(int, int, byte[], int, byte[], int, int, long x6, String, int) */
} callback_context_t;

static int cleared(JNIEnv *env) {
    if (!(*env)->ExceptionCheck(env)) return 0;
    (*env)->ExceptionClear(env);
    return 1;
}

static int protect_socket(void *userdata, int sock) {
    callback_context_t *cc = userdata;
    jboolean ok = (*cc->env)->CallBooleanMethod(cc->env, cc->callbacks, cc->protect, (jint) sock);
    if (cleared(cc->env)) return -1;
    return ok ? 0 : -1;
}

static jbyteArray ip_bytes(JNIEnv *env, const capture_conn_t *c, const uint8_t *ip) {
    jsize len = c->ipver == 4 ? 4 : 16;
    jbyteArray a = (*env)->NewByteArray(env, len);
    if (a != NULL) (*env)->SetByteArrayRegion(env, a, 0, len, (const jbyte *) ip);
    return a;
}

static int resolve_uid(void *userdata, const capture_conn_t *c) {
    callback_context_t *cc = userdata;
    JNIEnv *env = cc->env;
    jbyteArray src = ip_bytes(env, c, c->src_ip);
    jbyteArray dst = ip_bytes(env, c, c->dst_ip);
    jint uid = -1;
    if (src != NULL && dst != NULL) {
        uid = (*env)->CallIntMethod(env, cc->callbacks, cc->connection_owner, (jint) c->ipver, (jint) c->ipproto,
                                    src, (jint) c->src_port, dst, (jint) c->dst_port);
        if (cleared(env)) uid = -1;
    }
    cleared(env);
    if (src != NULL) (*env)->DeleteLocalRef(env, src);
    if (dst != NULL) (*env)->DeleteLocalRef(env, dst);
    return uid;
}

static void connection_closed(void *userdata, const capture_conn_t *c) {
    callback_context_t *cc = userdata;
    JNIEnv *env = cc->env;
    jbyteArray src = ip_bytes(env, c, c->src_ip);
    jbyteArray dst = ip_bytes(env, c, c->dst_ip);
    jstring status = (*env)->NewStringUTF(env, c->status != NULL ? c->status : "");
    if (src != NULL && dst != NULL && status != NULL) {
        (*env)->CallVoidMethod(env, cc->callbacks, cc->connection_closed, (jint) c->ipver, (jint) c->ipproto,
                               src, (jint) c->src_port, dst, (jint) c->dst_port, (jint) c->uid,
                               (jlong) c->first_boot_ns, (jlong) c->last_boot_ns,
                               (jlong) c->packets_out, (jlong) c->bytes_out, (jlong) c->packets_in,
                               (jlong) c->bytes_in, status, (jint) c->error);
    }
    cleared(env);
    if (src != NULL) (*env)->DeleteLocalRef(env, src);
    if (dst != NULL) (*env)->DeleteLocalRef(env, dst);
    if (status != NULL) (*env)->DeleteLocalRef(env, status);
}

static capture_vpn_t *handle(jlong h) { return (capture_vpn_t *) (intptr_t) h; }

JNIEXPORT jstring JNICALL JNI_FN(nativeVersion)(JNIEnv *env, jclass clazz) {
    (void) clazz;
    return (*env)->NewStringUTF(env, "netcapture " NETCAPTURE_VERSION " (" NETCAPTURE_ABI ", clang " __clang_version__ ")");
}

JNIEXPORT jlong JNICALL JNI_FN(nativeCreate)(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return (jlong) (intptr_t) capture_vpn_create();
}

JNIEXPORT jint JNICALL JNI_FN(nativeRun)(JNIEnv *env, jclass clazz, jlong h, jobject callbacks, jint tun_fd,
                                         jint pcap_fd, jint mtu, jint snaplen, jint buffer_bytes,
                                         jlong flush_interval_ms, jboolean ipv6_enabled) {
    (void) clazz;
    callback_context_t cc = { env, callbacks, NULL, NULL, NULL };
    jclass cls = (*env)->GetObjectClass(env, callbacks);
    cc.protect = (*env)->GetMethodID(env, cls, "protect", "(I)Z");
    cc.connection_owner = cc.protect == NULL ? NULL : (*env)->GetMethodID(env, cls, "connectionOwner", "(II[BI[BI)I");
    cc.connection_closed = cc.connection_owner == NULL ? NULL
        : (*env)->GetMethodID(env, cls, "connectionClosed", "(II[BI[BIIJJJJJJLjava/lang/String;I)V");
    (*env)->DeleteLocalRef(env, cls);
    if (cc.connection_closed == NULL) {
        (*env)->ExceptionClear(env);
        close(pcap_fd);
        return CAPTURE_EXIT_INIT_ERROR;
    }
    capture_vpn_config_t config = {
        .tun_fd = tun_fd,
        .pcap_fd = pcap_fd,
        .mtu = mtu,
        .snaplen = (uint32_t) snaplen,
        .pcap_buffer_bytes = (uint32_t) buffer_bytes,
        .pcap_flush_interval_ns = (uint64_t) flush_interval_ms * 1000000u,
        .ipv6_enabled = ipv6_enabled ? 1 : 0,
        .protect_socket = protect_socket,
        .resolve_uid = resolve_uid,
        .connection_closed = connection_closed,
        .userdata = &cc,
    };
    return capture_vpn_run(handle(h), &config);
}

JNIEXPORT void JNICALL JNI_FN(nativeStop)(JNIEnv *env, jclass clazz, jlong h) {
    (void) env;
    (void) clazz;
    capture_vpn_stop(handle(h));
}

JNIEXPORT jlongArray JNICALL JNI_FN(nativeStats)(JNIEnv *env, jclass clazz, jlong h) {
    (void) clazz;
    capture_vpn_stats_t s;
    capture_vpn_get_stats(handle(h), &s);
    jlong v[S_COUNT] = {
        [S_RUNNING] = s.running,
        [S_EXIT_REASON] = s.exit_reason,
        [S_TUN_RX_PACKETS] = (jlong) s.tun_rx_packets,
        [S_TUN_RX_BYTES] = (jlong) s.tun_rx_bytes,
        [S_TUN_TX_PACKETS] = (jlong) s.tun_tx_packets,
        [S_TUN_TX_BYTES] = (jlong) s.tun_tx_bytes,
        [S_TUN_TX_ERRORS] = (jlong) s.tun_tx_errors,
        [S_UNPARSED] = (jlong) s.unparsed,
        [S_FRAGMENTS] = (jlong) s.fragments,
        [S_IPVER_DISABLED] = (jlong) s.ipver_disabled,
        [S_TCP_WITHOUT_CONN] = (jlong) s.tcp_without_conn,
        [S_CONNS_FAILED] = (jlong) s.conns_failed,
        [S_PROTECT_FAILED] = (jlong) s.protect_failed,
        [S_CONNS_TCP] = (jlong) s.conns_tcp,
        [S_CONNS_UDP] = (jlong) s.conns_udp,
        [S_CONNS_ICMP] = (jlong) s.conns_icmp,
        [S_CONNS_ACTIVE] = (jlong) s.conns_active,
        [S_PCAP_PACKETS] = (jlong) s.pcap.packets,
        [S_PCAP_BYTES] = (jlong) s.pcap.bytes,
        [S_PCAP_TRUNCATED] = (jlong) s.pcap.truncated,
        [S_PCAP_DROPPED] = (jlong) s.pcap.dropped,
        [S_PCAP_LAST_ERRNO] = s.pcap.last_errno,
        [S_WALL_MINUS_BOOT_NS] = s.clock.wall_minus_boot_ns,
        [S_CLOCK_UNCERTAINTY_NS] = s.clock.uncertainty_ns,
        [S_START_BOOT_NS] = s.start_boot_ns,
        [S_STOP_BOOT_NS] = s.stop_boot_ns,
        [S_CONNS_UID_UNKNOWN] = (jlong) s.conns_uid_unknown,
    };
    jlongArray out = (*env)->NewLongArray(env, S_COUNT);
    if (out != NULL) (*env)->SetLongArrayRegion(env, out, 0, S_COUNT, v);
    return out;
}

JNIEXPORT void JNICALL JNI_FN(nativeDestroy)(JNIEnv *env, jclass clazz, jlong h) {
    (void) env;
    (void) clazz;
    capture_vpn_destroy(handle(h));
}
