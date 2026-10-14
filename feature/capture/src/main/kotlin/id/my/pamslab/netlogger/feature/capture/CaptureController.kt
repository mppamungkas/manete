package id.my.pamslab.netlogger.feature.capture

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import id.my.pamslab.netlogger.core.data.CaptureFileStore
import id.my.pamslab.netlogger.core.data.CaptureStateHolder
import id.my.pamslab.netlogger.core.data.SessionRepository
import id.my.pamslab.netlogger.core.model.Clock
import id.my.pamslab.netlogger.core.model.capture.CaptureMode
import id.my.pamslab.netlogger.core.model.capture.CaptureState
import id.my.pamslab.netlogger.core.model.logging.SessionEvent
import id.my.pamslab.netlogger.core.model.session.EventType
import id.my.pamslab.netlogger.core.model.session.SessionSummary
import id.my.pamslab.netlogger.feature.capture.vpn.NativeCapture
import id.my.pamslab.netlogger.feature.capture.vpn.NativeCaptureExit
import id.my.pamslab.netlogger.feature.capture.vpn.NativeCaptureService
import id.my.pamslab.netlogger.feature.capture.vpn.NativeCaptureStats
import id.my.pamslab.netlogger.feature.capture.vpn.NativeCaptureStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Why a capture ended, stored as CAPTURE_STOPPED fromValue. PCAPDROID: PCAPdroid stopped by itself. Native capture:
 * VPN_REVOKED (another VPN started, or the VPN was turned off in the system settings), ERROR (the capture loop
 * failed), PROCESS_DIED (the ":vpn" process ended without reporting; the pcap is valid up to its last flush).
 */
enum class StopReason { USER, SESSION_END, PCAPDROID, VPN_REVOKED, ERROR, PROCESS_DIED }

/**
 * Links a traffic capture to the open logging session: PCAPdroid (Phase 3a, below) or NetLogger's own VpnService
 * ([CaptureMode.NATIVE], Phase 3b: see the "Native capture" section).
 *
 * PCAPdroid (Phase 3a). The UI launches the intents this class builds
 * (PCAPdroid wants startActivityForResult) and reports the results back. In TCP mode this class owns the receiver
 * and capture.pcap; in FILE mode PCAPdroid owns the file and only its name is linked.
 *
 * Timing: packets keep PCAPdroid's own record timestamps (wall clock). For alignment with the radio log, the
 * wall-clock ↔ elapsedRealtimeNs offset is written to capture_sync.csv at capture start and stop, and both
 * CAPTURE_STARTED / CAPTURE_STOPPED events carry both time bases. When the session ends, NetLogger's side closes;
 * PCAPdroid itself keeps capturing until stopped from the capture card or PCAPdroid.
 */
@Singleton
class CaptureController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: SessionRepository,
    private val holder: CaptureStateHolder,
    private val files: CaptureFileStore,
    private val clock: Clock,
    val settings: CaptureSettings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var receiver: TcpPcapReceiver? = null
    private var pcapOut: OutputStream? = null
    private var syncFile: File? = null
    private var preparedUuid: String? = null

    // Native capture (main process side; the capture itself runs in ":vpn").
    private var nativePcap: File? = null
    private var nativeRequestedStop: StopReason? = null
    private var nativeLastStatusMs = 0L
    private var nativeLastStats: NativeCaptureStats? = null
    private var nativeJob: Job? = null
    private var nativeMtu = CaptureSettings.MTU_REALISTIC

    val state: StateFlow<CaptureState> = holder.state.asStateFlow()

    /** The session that is recording, if any. */
    val openSession: StateFlow<SessionSummary?> = repository.observeSessions()
        .map { list -> list.firstOrNull { it.endedAtMs == null } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** PCAPdroid may still capture after our side ended (session stopped from the notification). */
    private val _pcapdroidMayRun = MutableStateFlow(false)
    val pcapdroidMayRun: StateFlow<Boolean> = _pcapdroidMayRun.asStateFlow()

    init {
        scope.launch {
            openSession.collect { open ->
                val s = holder.state.value
                if (s.sessionId != null && s.sessionId != open?.id) {
                    if (s.mode == CaptureMode.NATIVE) {
                        if (s.running || nativePcap != null) stopNative(StopReason.SESSION_END)
                    } else if (s.running) {
                        finish(StopReason.SESSION_END, null)
                    }
                }
            }
        }
        val filter = IntentFilter(NativeCaptureStatus.ACTION)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                NativeCaptureStatus.parse(intent)?.let { status -> scope.launch { onNativeStatus(status) } }
            }
        }
        // Not exported: only NetLogger's own ":vpn" process (same uid) can send it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }

    fun pcapdroidPackage(): String? = Pcapdroid.installedPackage(context.packageManager)

    /**
     * Prepares a capture for the open session and returns PCAPdroid's start intent, or null with
     * [CaptureState.error] set. In TCP mode the receiver is already listening when this returns.
     */
    suspend fun prepareStart(): Intent? = mutex.withLock {
        val session = openSession.value ?: return fail("Start logging first: a capture belongs to a session")
        if (holder.state.value.running) return fail("A capture is already running")
        // One capture per session: a second one would overwrite capture.pcap and capture_sync.csv.
        if (files.sync(session.uuid).exists()) return fail("This session already has a capture. Start a new session for another one.")
        val mode = settings.mode
        if (mode == CaptureMode.NATIVE) return prepareNative(session)
        val pkg = pcapdroidPackage() ?: return fail("PCAPdroid is not installed")
        val extras = try {
            when (mode) {
                CaptureMode.TCP -> {
                    val out = BufferedOutputStream(FileOutputStream(files.pcap(session.uuid)), WRITE_BUFFER)
                    pcapOut = out
                    val r = TcpPcapReceiver(out)
                    receiver = r
                    holder.state.value = CaptureState(sessionId = session.id, mode = mode, fileName = files.pcap(session.uuid).name)
                    Pcapdroid.startExtras(mode, r.port, null, settings.appFilter, RECEIVER_CLASS)
                }
                CaptureMode.FILE -> {
                    val name = "netlogger_${session.uuid}.pcap"
                    holder.state.value = CaptureState(sessionId = session.id, mode = mode, fileName = name)
                    Pcapdroid.startExtras(mode, null, name, settings.appFilter, RECEIVER_CLASS)
                }
                CaptureMode.NATIVE -> error("handled above")
            }
        } catch (e: IOException) {
            closeReceiver()
            return fail("Capture file or TCP port: ${e.message}")
        }
        syncFile = files.sync(session.uuid)
        preparedUuid = session.uuid
        Log.i(TAG, "capture: asking $pkg to start ($mode)")
        Pcapdroid.intent(pkg, extras)
    }

    /** Result of the start request: RESULT_OK means PCAPdroid started (consent given), or the VPN consent (NATIVE). */
    fun onStartResult(ok: Boolean) {
        scope.launch {
            mutex.withLock {
                val s = holder.state.value
                if (s.mode == CaptureMode.NATIVE) return@withLock onNativeConsent(ok)
                val sessionId = s.sessionId ?: return@withLock
                if (s.running) return@withLock
                val uuid = openSession.value?.takeIf { it.id == sessionId }?.uuid
                if (!ok || uuid == null) {
                    closeReceiver()
                    // Nothing was captured for this session: leave no empty files behind.
                    preparedUuid?.let(files::delete)
                    if (ok) _pcapdroidMayRun.value = true // the session ended while PCAPdroid asked for consent
                    holder.state.value = CaptureState(
                        error = if (ok) "The session ended before the capture started; stop PCAPdroid"
                        else "PCAPdroid did not start the capture (consent denied or error)",
                    )
                    return@withLock
                }
                val sync = ClockSync.now(clock)
                holder.state.value = s.copy(running = true, startedElapsedNs = sync.elapsedNs, error = null)
                _pcapdroidMayRun.value = true
                syncFile?.let { ClockSync.writeHeader(it); ClockSync.append(it, "START", sync, null) }
                repository.setCaptureFile(sessionId, s.fileName?.let { if (s.mode == CaptureMode.FILE) "$PCAPDROID_DIR/$it" else it })
                repository.addEvent(sessionId, event(EventType.CAPTURE_STARTED, s.mode?.name, "${s.fileName} ${sync.describe()}", sync))
                Log.i(TAG, "capture started: ${s.mode} ${s.fileName}")
                if (s.mode == CaptureMode.TCP) launch { publishStats(sessionId) }
            }
        }
    }

    fun stopIntent(): Intent? = pcapdroidPackage()?.let { Pcapdroid.intent(it, Pcapdroid.stopExtras) }

    /** Result of the stop request (stats in the result intent). */
    fun onStopResult(ok: Boolean, result: Intent?) {
        if (ok) _pcapdroidMayRun.value = false
        scope.launch { finish(StopReason.USER, Pcapdroid.stats(result)) }
    }

    /** PCAPdroid's CaptureStatus broadcast: its capture stopped (from PCAPdroid itself or our stop request). */
    fun onPcapdroidStopped(stats: Pcapdroid.Stats?) {
        _pcapdroidMayRun.value = false
        scope.launch { finish(StopReason.PCAPDROID, stats) }
    }

    private suspend fun finish(reason: StopReason, stats: Pcapdroid.Stats?) = mutex.withLock {
        val s = holder.state.value
        if (!s.running) {
            // The stop result and the broadcast both come; the second one only adds PCAPdroid's count if missing.
            if (stats != null && s.pcapdroidPackets == null && s.sessionId != null) holder.state.value = s.copy(pcapdroidPackets = stats.packets)
            return@withLock
        }
        val r = receiver
        // PCAPdroid closes the TCP stream once its capture stopped: wait for it, so no buffered packet is cut off.
        // At the session end PCAPdroid still runs, so NetLogger's side closes right away.
        val drained = reason != StopReason.SESSION_END && r?.awaitEnd(DRAIN_TIMEOUT_MS) == true
        closeReceiver()
        val sync = ClockSync.now(clock)
        val missing = if (r != null && stats != null) stats.packets - r.packets else null
        val counts = buildString {
            append("netlogger_packets=${r?.packets ?: "–"} netlogger_bytes=${r?.bytes ?: "–"}")
            append(" pcapdroid_packets=${stats?.packets ?: "–"} pcapdroid_bytes_dumped=${stats?.bytesDumped ?: "–"}")
            missing?.let { append(" missing=$it") }
            if (r != null && !drained && reason != StopReason.SESSION_END) append(" not_drained")
            r?.error?.let { append(" error=$it") }
            append(" ${sync.describe()}")
        }
        syncFile?.let { ClockSync.append(it, "STOP", sync, counts) }
        s.sessionId?.let { repository.addEvent(it, event(EventType.CAPTURE_STOPPED, reason.name, counts, sync)) }
        holder.state.value = s.copy(
            running = false, packets = r?.packets ?: s.packets, bytes = r?.bytes ?: s.bytes,
            pcapdroidPackets = stats?.packets, error = r?.error,
        )
        Log.i(TAG, "capture stopped ($reason): $counts")
    }

    private suspend fun publishStats(sessionId: Long) {
        while (scope.isActive) {
            val r = receiver ?: return
            holder.state.update { s ->
                if (!s.running || s.sessionId != sessionId) return
                s.copy(packets = r.packets, bytes = r.bytes, error = r.error)
            }
            delay(STATS_MS)
        }
    }

    // --- Native capture (Phase 3b) ----------------------------------------------------------------------------------

    /** Whether this phone can run the native capture (libnetcapture loads). */
    val nativeAvailable: Boolean get() = NativeCapture.available

    /**
     * Prepares a native capture. Returns the system's VPN consent intent for the UI to launch, or null: either the
     * consent was given before and the capture is starting, or [CaptureState.error] says why not. Called under [mutex].
     */
    private fun prepareNative(session: SessionSummary): Intent? {
        if (!NativeCapture.available) return fail("Native capture is not available on this phone (${NativeCapture.loadError})")
        if (_pcapdroidMayRun.value) return fail("Stop the PCAPdroid capture first: only one VPN can run at a time")
        if (nativePcap != null) return fail("The last native capture is still ending; try again in a moment")
        holder.state.value = CaptureState(sessionId = session.id, mode = CaptureMode.NATIVE, fileName = files.pcap(session.uuid).name)
        preparedUuid = session.uuid
        syncFile = files.sync(session.uuid)
        val consent = try {
            VpnService.prepare(context)
        } catch (e: Exception) {
            return fail("VPN consent check failed: ${e.message}")
        }
        if (consent == null) startNative(session.uuid)
        return consent
    }

    /** Called under [mutex]. */
    private fun onNativeConsent(ok: Boolean) {
        val s = holder.state.value
        val uuid = preparedUuid
        if (s.running || nativePcap != null || uuid == null) return
        if (!ok) {
            holder.state.value = CaptureState(error = "VPN consent denied: the native capture did not start")
            return
        }
        if (openSession.value?.id != s.sessionId) {
            holder.state.value = CaptureState(error = "The session ended before the capture started")
            return
        }
        startNative(uuid)
    }

    /** Asks ":vpn" to start; the capture counts as running with its first STATS report. Called under [mutex]. */
    private fun startNative(uuid: String) {
        val pcap = files.pcap(uuid)
        nativePcap = pcap
        nativeRequestedStop = null
        nativeLastStats = null
        nativeLastStatusMs = SystemClock.elapsedRealtime()
        nativeMtu = settings.nativeMtu
        try {
            NativeCaptureService.start(context, pcap, files.connections(uuid), settings.appFilter, mtu = nativeMtu)
        } catch (e: Exception) {
            nativePcap = null
            fail("Native capture could not start: ${e.message}")
            return
        }
        Log.i(TAG, "capture: native capture requested (${pcap.name})")
        nativeJob?.cancel()
        nativeJob = scope.launch { nativeWatch() }
    }

    /** Stop from the capture card ([StopReason.USER]) or at the session end. The ENDED report finishes it. */
    fun stopNative(reason: StopReason = StopReason.USER) {
        scope.launch {
            mutex.withLock {
                if (nativePcap == null) return@withLock
                if (nativeRequestedStop == null) nativeRequestedStop = reason
            }
            try {
                NativeCaptureService.stop(context)
            } catch (e: Exception) {
                Log.w(TAG, "capture: stopping the native capture failed", e)
            }
        }
    }

    private suspend fun onNativeStatus(status: NativeCaptureStatus.Status) = mutex.withLock {
        if (status.standalone) return@withLock // debug capture from adb, no session
        val pcap = nativePcap
        if (pcap == null || status.pcapPath != pcap.absolutePath) {
            // Not ours (e.g. the main process restarted while ":vpn" kept capturing): stop it, it has no session.
            if (status.event == NativeCaptureStatus.STATS && status.stats?.running == true) {
                Log.w(TAG, "capture: native capture without a session (${File(status.pcapPath).name}): stopping it")
                NativeCaptureService.stop(context)
            }
            return@withLock
        }
        nativeLastStatusMs = SystemClock.elapsedRealtime()
        status.stats?.let { nativeLastStats = it }
        val s = holder.state.value
        when (status.event) {
            NativeCaptureStatus.STATS -> {
                val st = status.stats ?: return@withLock
                if (!s.running && st.running) nativeStarted(st) else if (s.running) holder.state.value = s.withNative(st)
            }
            NativeCaptureStatus.ENDED -> {
                if (!s.running) {
                    // Never started: nothing was captured for this session, leave no files behind.
                    val st = status.stats
                    if (st != null && st.startElapsedNs > 0) nativeStarted(st) else {
                        nativeCleared()
                        preparedUuid?.let(files::delete)
                        holder.state.value = CaptureState(error = "Native capture did not start: ${status.error ?: status.exit ?: "unknown"}")
                        return@withLock
                    }
                }
                val reason = nativeRequestedStop ?: when {
                    status.exit == NativeCaptureExit.TUN_CLOSED || status.why == NativeCaptureStatus.Why.REVOKED -> StopReason.VPN_REVOKED
                    status.exit == NativeCaptureExit.STOPPED && status.why == NativeCaptureStatus.Why.USER -> StopReason.USER
                    else -> StopReason.ERROR
                }
                nativeFinished(reason, status.stats ?: nativeLastStats, status.exit)
            }
        }
    }

    /** First report of a running capture. Called under [mutex]. */
    private suspend fun nativeStarted(st: NativeCaptureStats) {
        val s = holder.state.value
        val sessionId = s.sessionId ?: return
        // The pcap's own clock offset: pcap timestamp - wallMinusElapsedNs = elapsedRealtimeNs, exactly, for the
        // whole file. CHECK rows (every 60 s) and STOP read the clocks again, so wall-clock changes show up.
        val start = ClockSync(Math.floorDiv(st.startElapsedNs + st.wallMinusElapsedNs, 1_000_000L), st.startElapsedNs, st.wallMinusElapsedNs)
        holder.state.value = s.withNative(st).copy(running = true, startedElapsedNs = st.startElapsedNs, error = null)
        syncFile?.let {
            ClockSync.writeHeader(it)
            ClockSync.append(it, "START", start, "pcap_offset clock_uncertainty_ns=${st.clockUncertaintyNs} mtu=$nativeMtu")
        }
        repository.setCaptureFile(sessionId, s.fileName)
        repository.addEvent(sessionId, event(EventType.CAPTURE_STARTED, CaptureMode.NATIVE.name, "${s.fileName} ${start.describe()} mtu=$nativeMtu", start))
        Log.i(TAG, "capture started: NATIVE ${s.fileName} ${start.describe()} mtu=$nativeMtu")
    }

    /** Called under [mutex]. */
    private suspend fun nativeFinished(reason: StopReason, st: NativeCaptureStats?, exit: NativeCaptureExit?) {
        val s = holder.state.value
        val sync = ClockSync.now(clock)
        val counts = buildString {
            append("pcap_packets=${st?.pcapPackets ?: "–"} pcap_bytes=${st?.pcapBytes ?: "–"}")
            append(" expected_packets=${st?.expectedPcapPackets ?: "–"} dropped=${st?.pcapDropped ?: "–"}")
            append(" tun_rx_packets=${st?.tunRxPackets ?: "–"} tun_tx_packets=${st?.tunTxPackets ?: "–"}")
            append(" tun_tx_errors=${st?.tunTxErrors ?: "–"}")
            append(" connections_tcp=${st?.connsTcp ?: "–"} connections_udp=${st?.connsUdp ?: "–"} connections_icmp=${st?.connsIcmp ?: "–"}")
            append(" uid_unknown=${st?.connsUidUnknown ?: "–"} conns_failed=${st?.connsFailed ?: "–"}")
            append(" unparsed=${st?.unparsed ?: "–"} fragments=${st?.fragments ?: "–"} tcp_without_conn=${st?.tcpWithoutConn ?: "–"}")
            append(" exit=${exit ?: "–"}")
            if (reason == StopReason.PROCESS_DIED) append(" process_died")
            append(" ${sync.describe()}")
        }
        syncFile?.let { ClockSync.append(it, "STOP", sync, counts) }
        s.sessionId?.let { repository.addEvent(it, event(EventType.CAPTURE_STOPPED, reason.name, counts, sync)) }
        val problem = when {
            reason == StopReason.PROCESS_DIED -> "The capture process ended unexpectedly; the pcap is valid up to its last flush"
            reason == StopReason.ERROR -> "The native capture failed (${exit ?: "unknown"})"
            reason == StopReason.VPN_REVOKED -> "The VPN was closed by Android (another VPN, or turned off in the settings)"
            (st?.pcapDropped ?: 0) > 0 -> "${st?.pcapDropped} packets could not be written to the pcap"
            else -> null
        }
        holder.state.value = (if (st != null) s.withNative(st) else s).copy(running = false, error = problem)
        nativeCleared()
        Log.i(TAG, "capture stopped ($reason): $counts")
    }

    private fun nativeCleared() {
        nativePcap = null
        nativeRequestedStop = null
        nativeJob?.cancel()
        nativeJob = null
    }

    /**
     * While a native capture is requested or running: a CHECK row in capture_sync.csv every 60 s (the phone's wall
     * clock vs. elapsedRealtime, against the pcap's fixed offset), and a watchdog for the ":vpn" process.
     */
    private suspend fun nativeWatch() {
        var nextCheck = SystemClock.elapsedRealtime() + SYNC_CHECK_MS
        while (scope.isActive) {
            delay(WATCH_MS)
            mutex.withLock {
                if (nativePcap == null) return
                val now = SystemClock.elapsedRealtime()
                val s = holder.state.value
                if (s.running && now >= nextCheck) {
                    syncFile?.let { ClockSync.append(it, "CHECK", ClockSync.now(clock), null) }
                    nextCheck = now + SYNC_CHECK_MS
                }
                val silentMs = now - nativeLastStatusMs
                if (silentMs > (if (s.running) SILENT_RUNNING_MS else SILENT_STARTING_MS) && !vpnProcessAlive()) {
                    if (s.running) {
                        nativeFinished(StopReason.PROCESS_DIED, nativeLastStats, null)
                    } else {
                        nativeCleared()
                        preparedUuid?.let(files::delete)
                        holder.state.value = CaptureState(error = "Native capture did not start (no answer from the capture process)")
                    }
                    return
                }
            }
        }
    }

    private fun vpnProcessAlive(): Boolean = try {
        context.getSystemService(ActivityManager::class.java)?.runningAppProcesses
            ?.any { it.processName == "${context.packageName}:vpn" } ?: false
    } catch (e: Exception) {
        false
    }

    private fun CaptureState.withNative(st: NativeCaptureStats) = copy(
        packets = st.pcapPackets, bytes = st.pcapBytes, expectedPackets = st.expectedPcapPackets,
        droppedPackets = st.pcapDropped, connections = st.connsTcp + st.connsUdp + st.connsIcmp,
    )

    private fun closeReceiver() {
        receiver?.close()
        receiver = null
        pcapOut?.let { runCatching { it.close() } }
        pcapOut = null
    }

    private fun fail(message: String): Intent? {
        holder.state.update { it.copy(error = message) }
        Log.w(TAG, "capture: $message")
        return null
    }

    private fun event(type: EventType, from: String?, to: String?, sync: ClockSync) =
        SessionEvent(type, sync.wallMs, sync.elapsedNs, from, to, null, null)

    companion object {
        private const val TAG = "NetLogger"
        private const val STATS_MS = 1_000L
        private const val WRITE_BUFFER = 1 shl 20
        private const val DRAIN_TIMEOUT_MS = 5_000L
        private const val WATCH_MS = 2_000L
        private const val SYNC_CHECK_MS = 60_000L
        /** ":vpn" reports every second; this long without a report, and the process gone, means it died. */
        private const val SILENT_RUNNING_MS = 5_000L
        private const val SILENT_STARTING_MS = 15_000L
        const val PCAPDROID_DIR = "Download/PCAPdroid"
        val RECEIVER_CLASS: String = CaptureStatusReceiver::class.java.name
    }
}

/**
 * One reading of both clocks. [elapsedNs] is the midpoint of two elapsedRealtimeNs reads around the wall-clock read,
 * so [wallMinusElapsedNs] is off by at most half their gap (microseconds). A packet's PCAP timestamp (wall clock)
 * maps to the radio log's axis as `elapsedRealtimeNs = pcapTsNs - wallMinusElapsedNs`.
 */
data class ClockSync(
    val wallMs: Long,
    val elapsedNs: Long,
    /** Exact offset when known (native capture: the pcap's own); otherwise from the two readings. */
    val wallMinusElapsedNs: Long = wallMs * 1_000_000 - elapsedNs,
) {
    fun describe() = "wallMinusElapsedNs=$wallMinusElapsedNs"

    companion object {
        fun now(clock: Clock): ClockSync {
            val before = clock.elapsedRealtimeNs()
            val wall = clock.wallTimeMs()
            val after = clock.elapsedRealtimeNs()
            return ClockSync(wall, before + (after - before) / 2)
        }

        const val HEADER = "point,wallTimeMs,elapsedRealtimeNs,wallMinusElapsedNs,counts\n"

        fun writeHeader(file: File) = file.writeText(HEADER)

        fun append(file: File, point: String, sync: ClockSync, counts: String?) =
            file.appendText("$point,${sync.wallMs},${sync.elapsedNs},${sync.wallMinusElapsedNs},${counts?.let { "\"$it\"" } ?: ""}\n")
    }
}
