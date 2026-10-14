package id.my.pamslab.netlogger.feature.capture

import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import javax.inject.Inject
import javax.inject.Singleton

/** Why a capture ended, stored as CAPTURE_STOPPED fromValue. */
enum class StopReason { USER, SESSION_END, PCAPDROID, START_FAILED }

/**
 * Links a PCAPdroid capture to the open logging session (Phase 3a). The UI launches the intents this class builds
 * (PCAPdroid wants startActivityForResult) and reports the results back. In UDP mode this class owns the listener
 * and capture.pcap; in FILE mode PCAPdroid owns the file and only its name is linked.
 *
 * Start and stop are stored as CAPTURE_STARTED / CAPTURE_STOPPED events (both time bases), so the capture can be
 * aligned with the radio log. When the session ends, the listener is closed; PCAPdroid itself keeps capturing until
 * stopped from the capture card or PCAPdroid.
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
    private var receiver: UdpPcapReceiver? = null
    private var streams: List<Closeable> = emptyList()

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
                if (s.running && s.sessionId != open?.id) finish(StopReason.SESSION_END, null)
            }
        }
    }

    fun pcapdroidPackage(): String? = Pcapdroid.installedPackage(context.packageManager)

    /**
     * Prepares a capture for the open session and returns PCAPdroid's start intent, or null with
     * [CaptureState.error] set. In UDP mode the listener is already bound when this returns.
     */
    suspend fun prepareStart(): Intent? = mutex.withLock {
        val session = openSession.value ?: return fail("Start logging first: a capture belongs to a session")
        if (holder.state.value.running) return fail("A capture is already running")
        val pkg = pcapdroidPackage() ?: return fail("PCAPdroid is not installed")
        val mode = settings.mode
        val extras = try {
            when (mode) {
                CaptureMode.UDP -> {
                    val pcap = FileOutputStream(files.pcap(session.uuid))
                    val out = BufferedOutputStream(pcap)
                    val sync = OutputStreamWriter(FileOutputStream(files.sync(session.uuid)), Charsets.UTF_8)
                    val r = UdpPcapReceiver(out, sync, clock::elapsedRealtimeNs, clock::wallTimeMs)
                    receiver = r
                    streams = listOf(out, sync)
                    holder.state.value = CaptureState(sessionId = session.id, mode = mode, fileName = files.pcap(session.uuid).name)
                    Pcapdroid.startExtras(mode, r.port, null, settings.appFilter, RECEIVER_CLASS)
                }
                CaptureMode.FILE -> {
                    val name = "netlogger_${session.uuid}.pcap"
                    holder.state.value = CaptureState(sessionId = session.id, mode = mode, fileName = name)
                    Pcapdroid.startExtras(mode, null, name, settings.appFilter, RECEIVER_CLASS)
                }
            }
        } catch (e: IOException) {
            closeReceiver()
            return fail("Capture file or UDP port: ${e.message}")
        }
        Log.i(TAG, "capture: asking $pkg to start ($mode)")
        Pcapdroid.intent(pkg, extras)
    }

    /** Result of the start request: RESULT_OK means PCAPdroid started (consent given). */
    fun onStartResult(ok: Boolean) {
        scope.launch {
            mutex.withLock {
                val s = holder.state.value
                val sessionId = s.sessionId ?: return@withLock
                if (!ok) {
                    closeReceiver()
                    openSession.value?.uuid?.let { uuid -> if (s.mode == CaptureMode.UDP) files.delete(uuid) }
                    holder.state.value = CaptureState(error = "PCAPdroid did not start the capture (consent denied or error)")
                    return@withLock
                }
                val now = clock.elapsedRealtimeNs()
                holder.state.value = s.copy(running = true, startedElapsedNs = now, error = null)
                if (openSession.value?.id != sessionId) {
                    // The session ended while PCAPdroid asked for consent.
                    _pcapdroidMayRun.value = true
                    closeReceiver()
                    holder.state.value = s.copy(error = "The session ended before the capture started; stop PCAPdroid")
                    return@withLock
                }
                _pcapdroidMayRun.value = true
                repository.setCaptureFile(sessionId, s.fileName?.let { if (s.mode == CaptureMode.FILE) "$PCAPDROID_DIR/$it" else it })
                repository.addEvent(sessionId, event(EventType.CAPTURE_STARTED, s.mode?.name, s.fileName, now))
                Log.i(TAG, "capture started: ${s.mode} ${s.fileName}")
                if (s.mode == CaptureMode.UDP) launch { publishStats(sessionId) }
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
    fun onPcapdroidStopped(stats: String?) {
        _pcapdroidMayRun.value = false
        scope.launch { finish(StopReason.PCAPDROID, stats) }
    }

    private suspend fun finish(reason: StopReason, pcapdroidStats: String?) = mutex.withLock {
        val s = holder.state.value
        if (!s.running) return@withLock
        val r = receiver
        closeReceiver()
        val counts = "packets=${r?.packets ?: "–"} bytes=${r?.bytes ?: "–"} rejected=${r?.rejectedDatagrams ?: "–"}" +
            (pcapdroidStats?.let { " · $it" } ?: "")
        s.sessionId?.let { repository.addEvent(it, event(EventType.CAPTURE_STOPPED, reason.name, counts, clock.elapsedRealtimeNs())) }
        holder.state.value = s.copy(running = false, packets = r?.packets ?: s.packets, bytes = r?.bytes ?: s.bytes)
        Log.i(TAG, "capture stopped ($reason): $counts")
    }

    private suspend fun publishStats(sessionId: Long) {
        while (scope.isActive) {
            val r = receiver ?: return
            holder.state.update { s ->
                if (!s.running || s.sessionId != sessionId) return
                s.copy(packets = r.packets, bytes = r.bytes, rejectedDatagrams = r.rejectedDatagrams, error = r.error)
            }
            delay(STATS_MS)
        }
    }

    private fun closeReceiver() {
        receiver?.close()
        receiver = null
        streams.forEach { runCatching { it.close() } }
        streams = emptyList()
    }

    private fun fail(message: String): Intent? {
        holder.state.update { it.copy(error = message) }
        Log.w(TAG, "capture: $message")
        return null
    }

    private fun event(type: EventType, from: String?, to: String?, elapsedNs: Long) =
        SessionEvent(type, clock.wallTimeMs(), elapsedNs, from, to, null, null)

    companion object {
        private const val TAG = "NetLogger"
        private const val STATS_MS = 1_000L
        const val PCAPDROID_DIR = "Download/PCAPdroid"
        val RECEIVER_CLASS: String = CaptureStatusReceiver::class.java.name
    }
}
