/*
 * NetLogger: JNI entry points of libnetcapture (native traffic capture, Phase 3b).
 * Copyright (C) 2026 NetLogger contributors. Licensed under the GNU GPL v3 (see LICENSE).
 */
#include <jni.h>

#define NETCAPTURE_VERSION "0.1"

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

JNIEXPORT jstring JNICALL
Java_id_my_pamslab_netlogger_feature_capture_vpn_NativeCapture_nativeVersion(JNIEnv *env, jclass clazz) {
    (void) clazz;
    return (*env)->NewStringUTF(env, "netcapture " NETCAPTURE_VERSION " (" NETCAPTURE_ABI ", clang " __clang_version__ ")");
}
