# 002: Native traffic capture (milestone 11, Phase 3b): plan

- **Status:** Accepted (all recommendations D1–D8), 2026-10-14.
- **Date:** 2026-10-14
- **Milestone:** 11 (native `VpnService` capture)
- **Studied:** `reference/pcapdroid` (`CaptureService.java`, `jni/core/capture_vpn.c`, `docs/how_it_works.md`),
  its submodule `zdtun` at commit `c2a2674` (fetched for study with `git submodule update --init --depth 1
  submodules/zdtun`, as the CLAUDE.md setup notes allow).

## Why, given that the PCAPdroid bridge already works

Milestone 10 lost no packets on either phone and did not slow a 10 MB download. Native capture adds:

1. **No second app.** It doesn't need PCAPdroid installed or its consent dialog. It uses the system VPN consent
   dialog instead, which asks once per install.
2. **Packet timestamps in NetLogger's own time base.** Each packet's timestamp comes from `CLOCK_BOOTTIME`, the
   same clock as `elapsedRealtimeNs`, plus one fixed offset. The capture then aligns exactly with the radio log,
   and changes to the wall clock can't shift it.
3. **App per connection.** Each connection records which app owns it (UID and package name), in a file
   NetLogger writes itself.

The cost is about 1,500–2,500 lines of new C and Kotlin, plus the NDK toolchain. If those three points don't
matter for your research, milestone 11 can be dropped and the PCAPdroid bridge kept as the only capture path.

## How it works

The method is the same one PCAPdroid uses without root:

```
app ──IP packets──► tun (VpnService) ──► NetLogger native thread ──► normal sockets (protect()) ──► Internet
                                           │
                                           └─► capture.pcap (packets as the app sent and received them)
```

- `VpnService.Builder` routes all IPv4 traffic, and IPv6 when the mobile network has it (`0.0.0.0/1`,
  `128.0.0.0/1`, `2000::/3`), to the tun interface.
- A native thread reads IP packets from the tun file descriptor. For every TCP, UDP or ICMP flow it opens a
  normal socket, excluded from the VPN with `protect()`, and passes data both ways.
  - For TCP this means imitating the remote end: sequence numbers, ACKs and window size.
- Every packet that crosses the tun interface, in either direction, goes into `capture.pcap`.

**Limits.** These go into the UI and `docs/columns.md`, as CLAUDE.md requires:

- The capture shows only IP traffic of apps, between each app and the VPN. It has no radio signaling and no
  on-air packets.
  - Packet sizes and timing on the tun side are not those on the radio link: the forwarder rebuilds TCP
    segments.
  - The remote side's packet loss, retransmissions and TCP handshake timing can't be seen.
- No other VPN can run at the same time. That includes PCAPdroid: the two capture modes exclude each other.
- The capture adds CPU load and may limit throughput. Speed tests during a capture are flagged
  `captureActive = true`, as they already are.
- With Private DNS (DNS over TLS) on, DNS queries appear only as TLS traffic to port 853.

## Decisions needed from you

| # | Question | Recommendation |
|---|---|---|
| D1 | **Forwarder:** use zdtun, write our own, or use lwIP | **zdtun as a library, unmodified.** It is the forwarder inside PCAPdroid, written in C, about 2,100 lines in `zdtun.c` plus headers. It is **LGPL v3 or later** (file headers and `COPYING`), which is compatible with our GPL v3. It handles TCP, UDP, ICMP, IPv4 and IPv6, and has been field-tested in PCAPdroid. Writing our own TCP imitation is the riskiest part of the milestone. lwIP is a full TCP/IP stack and more than we need. |
| D2 | **How zdtun gets into the repo** | **Copy the files we need**, unchanged, to `feature/capture/src/main/cpp/third_party/zdtun/`: `zdtun.c/h`, `utils.c/h`, `third_party/net_headers.h`, `third_party/uthash.h`. Keep the headers and `COPYING`, add a README naming the commit `c2a2674`, and list it in `docs/third-party.md`. A git submodule is the alternative, but it makes every clone and CI run depend on another repo. The SOCKS5, client, gateway and pivot files are not needed. `uthash.h` has its own license, BSD 1-clause (checked). `net_headers.h` has no header of its own, so zdtun's `COPYING` covers it. |
| D3 | **Process:** run the VPN service in its own process (`android:process=":vpn"`) | **Yes.** A crash in native code would otherwise kill `LoggingService` in the middle of a drive. The cost is passing state between processes: packet counts and status through a bound `Messenger`, files under NetLogger's own `files/captures/`. The `:vpn` process writes no database rows; the main process stores the `CAPTURE_*` events as it does today. |
| D4 | **Tun MTU** | **1500.** Captured packets then have realistic sizes for analysis. PCAPdroid uses 10,000, which costs less CPU but produces packets up to 10 kB, which no real link carries. I'll measure the throughput cost on the moto. If it is too high, the MTU becomes a setting. |
| D5 | **Packet file format** | **pcap with nanosecond timestamps** (magic `0xa1b23c4d`), link type `LINKTYPE_RAW` (101), so Wireshark and tshark open it directly. Timestamp = `CLOCK_BOOTTIME` + (wall clock − boot time), with the offset taken once at capture start, so time never jumps backwards inside a file. `capture_sync.csv` gets the offset at start and stop, as it does now, plus a row every 60 s, so that wall-clock drift can be measured. |
| D6 | **App per connection:** stored in a file or in Room | **In a file, `connections.csv`**, so there is no schema change. Columns: protocol, the two addresses and ports, uid, package, first and last seen (`elapsedRealtimeNs`), bytes and packets in each direction. The uid comes from `ConnectivityManager.getConnectionOwnerUid()`, a public API, API 29+, that only works for the active VPN app. A Room table could follow later if you want to query it in the app. |
| D7 | **NetLogger's own traffic** (speed test, map tiles) | **Captured** like every other app's traffic: speed test packets are exactly what you want to see with the radio log. The forwarder's own sockets are excluded with `protect()`, so nothing loops. App filter: the same package list as the PCAPdroid mode, using `addAllowedApplication`. |
| D8 | **Foreground service type** for the VPN service | **`specialUse`** with the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property, the same as PCAPdroid. `systemExempted` is for system apps and always-on VPNs. This adds the `FOREGROUND_SERVICE_SPECIAL_USE` permission. Google Play reviews `specialUse` and has a VPN policy; that only matters if NetLogger is ever published there. |

## New dependencies and toolchain (your approval needed)

- **Android NDK r27 LTS**, installed as r27d (`ndk;27.3.13750724`, the latest r27 patch; the plan named r27c,
  `27.2.12479018`), and **CMake 3.22.1**, installed with `sdkmanager` on the VM.
  Neither is installed yet. Install size is about 1.5 GB. `ndkVersion` is pinned in Gradle.
  - The GitHub Actions Ubuntu image already includes an NDK. I'll pin the same version there.
- **zdtun** source as in D2. No new Gradle or Maven dependency.
- Permissions: `FOREGROUND_SERVICE_SPECIAL_USE`, and `BIND_VPN_SERVICE` on the service, which is a system
  permission and not requested from the user.

## Steps (one commit each; build, unit tests and lint green before each)

1. **Toolchain.** NDK and CMake on the VM, `externalNativeBuild` in `feature:capture`, a minimal JNI library,
   and CI. Check that the app still builds and installs on both phones.
2. **pcap writer in C** (`pcap_writer.c`) with nanosecond timestamps, buffered, with a flush interval.
   Host-side C unit tests (built with the SDK's CMake and the VM's gcc, also run in CI). They check the header,
   the records and the timestamp offset by reading the file back.
3. **VPN forwarding.** `NativeCaptureService` (VpnService, `:vpn` process), tun setup (D4, IPv6 only when
   the mobile network has it), a zdtun loop on its own thread, `protect()` through JNI, and a clean stop that
   closes the tun, closes the sockets and flushes the file.
   - Tested on the moto first: browsing, DNS, `curl` download, QUIC (UDP 443).
4. **Connections table.** On every new connection, look up its owner with `getConnectionOwnerUid()` and
   resolve the package name in Kotlin, cached per uid. Write `connections.csv` on close and at stop.
5. **Integration.**
   - A third capture mode, "Native (VPN)", on the capture card, with the limits text from above.
   - Consent through `VpnService.prepare()`. The system dialog is for you to tap; I won't answer it.
   - `CAPTURE_STARTED` / `CAPTURE_STOPPED` events with packet, byte and drop counts.
   - `captureActive` set for speed tests.
   - Export adds `connections.csv`.
   - Check that `NetworkGuard` still sees mobile data while the VPN is up: a VPN network should report its
     underlying transport. If it doesn't, the guard will check the VPN's underlying network.
6. **Phone tests**, results in `docs/device-quirks.md`. Done when all of these pass on both phones:
   - Throughput with native capture on vs off, and vs PCAPdroid capture: 3 × 10 MB downloads and the speed test.
     - Target: no more than 10 % slower than no capture. A bigger loss is reported, not hidden.
   - Packet counts: tun read/written vs pcap records, zero gaps.
   - Packet timestamps vs `elapsedRealtimeNs` of the radio log: offset check at stop.
   - 30 min with the screen off while capturing, with logging still at ≥ 95 % ticks.
   - Kill tests: kill the `:vpn` process and check that logging continues, the pcap is still readable up to the
     last flush, and a `CAPTURE_STOPPED` event with an error is stored.
   - Another VPN, or PCAPdroid, already running: clean refusal with a message.

## Out of scope for milestone 11

- PCAPNG, TLS decryption, DPI and app protocol detection (nDPI), firewall or blocking.
- Root capture.
- Per-packet uid inside the pcap. The uid is per connection, in `connections.csv`.
