package id.my.pamslab.netlogger.feature.capture

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import id.my.pamslab.netlogger.core.data.CaptureFileStore
import id.my.pamslab.netlogger.core.data.CaptureSettings
import id.my.pamslab.netlogger.core.data.CaptureStateHolder
import id.my.pamslab.netlogger.core.data.SessionRepository
import id.my.pamslab.netlogger.core.diagnostics.CaughtErrors
import id.my.pamslab.netlogger.core.model.Clock
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
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Why a capture ended, stored as CAPTURE_STOPPED fromValue: USER (stop button), SESSION_END, VPN_REVOKED (another VPN
 * started, or the VPN was turned off in the system settings), ERROR (the capture loop failed), PROCESS_DIED (the
 * ":vpn" process ended without reporting; the pcap is valid up to its last flush). Sessions recorded with the
 * PCAPdroid bridge (removed in CLAUDE.md 1.7.0) may also show PCAPDROID.
 */
enum class StopReason { USER, SESSION_END, VPN_REVOKED, ERROR, PROCESS_DIED }

/**
 * Links a traffic capture to the open logging session (Phase 3b, docs/decisions/002-native-capture.md). The capture
 * runs in NetLogger's own VpnService in the ":vpn" process, which reports its counters here; this class stores the
 * CAPTURE_STARTED / CAPTURE_STOPPED events and capture_sync.csv, stops the capture at the session end and notices a
 * capture process that died. The UI launches the system VPN consent intent and reports the result back.
 *
 * Timing: packet timestamps are elapsedRealtimeNs plus the START row's wallMinusElapsedNs, exactly, for the whole
 * file (capture_sync.csv).
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
    private var syncFile: File? = null
    private var preparedUuid: String? = null

    // The capture itself runs in ":vpn"; this is the main process side.
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

    /** Whether this phone can capture (libnetcapture loads). */
    val available: Boolean get() = NativeCapture.available

    init {
        scope.launch {
            openSession.collect { open ->
                val s = holder.state.value
                if (s.sessionId != null && s.sessionId != open?.id && (s.running || nativePcap != null)) {
                    stopNative(StopReason.SESSION_END)
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

    /**
     * Prepares a capture for the open session. Returns the system's VPN consent intent for the UI to launch, or null:
     * either the consent was given before and the capture is starting, or [CaptureState.error] says why not.
     */
    suspend fun prepareStart(): Intent? = mutex.withLock {
        val session = openSession.value ?: return fail("Start logging first: a capture belongs to a session")
        if (holder.state.value.running) return fail("A capture is already running")
        // One capture per session: a second one would overwrite capture.pcap and capture_sync.csv.
        if (files.sync(session.uuid).exists()) return fail("This session already has a capture. Start a new session for another one.")
        prepareNative(session)
    }

    /** Result of the VPN consent dialog. */
    fun onStartResult(ok: Boolean) {
        scope.launch { mutex.withLock { onNativeConsent(ok) } }
    }

    /**
     * Prepares a native capture. Returns the system's VPN consent intent for the UI to launch, or null: either the
     * consent was given before and the capture is starting, or [CaptureState.error] says why not. Called under [mutex].
     */
    private fun prepareNative(session: SessionSummary): Intent? {
        if (!NativeCapture.available) return fail("Traffic capture is not available on this phone (${NativeCapture.loadError})")
        if (nativePcap != null) return fail("The last native capture is still ending; try again in a moment")
        // Android allows one VPN: starting ours would silently end the other (e.g. a capture started in PCAPdroid).
        if (otherVpnActive()) return fail("Another VPN is active (e.g. PCAPdroid): stop it first, only one VPN can run at a time")
        holder.state.value = CaptureState(sessionId = session.id, fileName = files.pcap(session.uuid).name)
        preparedUuid = session.uuid
        syncFile = files.sync(session.uuid)
        val consent = try {
            VpnService.prepare(context)
        } catch (e: Exception) {
            CaughtErrors.record("VPN consent check failed", e)
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
            Log.i(TAG, "capture: VPN consent denied, native capture not started")
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
        nativeMtu = settings.nativeMtu.value
        try {
            NativeCaptureService.start(context, pcap, files.connections(uuid), settings.appFilter, mtu = nativeMtu)
        } catch (e: Exception) {
            nativePcap = null
            CaughtErrors.record("native capture could not start", e)
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
                CaughtErrors.caught("capture: stopping the native capture failed", e)
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
        repository.addEvent(sessionId, event(EventType.CAPTURE_STARTED, MODE, "${s.fileName} ${start.describe()} mtu=$nativeMtu", start))
        Log.i(TAG, "capture started: $MODE ${s.fileName} ${start.describe()} mtu=$nativeMtu")
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

    /** A VPN is up (not ours: ours isn't running when this is asked). Android does not tell whose it is. */
    private fun otherVpnActive(): Boolean = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm?.allNetworks?.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true } ?: false
    } catch (e: Exception) {
        false
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

    private fun fail(message: String): Intent? {
        holder.state.update { it.copy(error = message) }
        Log.w(TAG, "capture: $message")
        return null
    }

    private fun event(type: EventType, from: String?, to: String?, sync: ClockSync) =
        SessionEvent(type, sync.wallMs, sync.elapsedNs, from, to, null, null)

    companion object {
        private const val TAG = "NetLogger"
        /** CAPTURE_STARTED fromValue (sessions from the PCAPdroid bridge show TCP or FILE). */
        const val MODE = "NATIVE"
        private const val WATCH_MS = 2_000L
        private const val SYNC_CHECK_MS = 60_000L
        /** ":vpn" reports every second; this long without a report, and the process gone, means it died. */
        private const val SILENT_RUNNING_MS = 5_000L
        private const val SILENT_STARTING_MS = 15_000L
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
