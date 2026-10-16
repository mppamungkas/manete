package id.my.pamslab.netlogger

import android.app.Application
import android.util.Log
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import id.my.pamslab.netlogger.core.data.CaptureStateHolder
import id.my.pamslab.netlogger.core.diagnostics.CrashRecorder
import id.my.pamslab.netlogger.core.diagnostics.ProblemReports
import id.my.pamslab.netlogger.core.diagnostics.ProcessState
import id.my.pamslab.netlogger.feature.logger.service.LoggingEngine
import id.my.pamslab.netlogger.feature.logger.service.LoggingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class NetLoggerApplication : Application() {

    // Lazy: the native capture's own process (":vpn") must not build the logging engine or open the database.
    @Inject lateinit var loggingEngine: Lazy<LoggingEngine>

    @Inject lateinit var problemReports: Lazy<ProblemReports>

    @Inject lateinit var processState: Lazy<ProcessState>

    @Inject lateinit var captureState: Lazy<CaptureStateHolder>

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        // First, in every process (main and :vpn): a crash from here on leaves its stack trace (decision 005).
        CrashRecorder.install(this)
        super.onCreate()
        // Only the main process: the native capture runs in ":vpn" while a session may be recording here, and this
        // recovery would close that live session.
        if (getProcessName() != packageName) return
        // A new process means no session is recording here: close the ones a killed process left open
        // (endedAt = last measurement). Starting a session does the same, so a race with the service is harmless.
        appScope.launch { problemReports.get().refresh() } // also when the process starts without the UI (service)
        // What is running goes with this process' next exit record, so a report can say "a recording was running".
        appScope.launch {
            loggingEngine.get().state.collect { processState.get().setRecording((it as? LoggingState.Running)?.sessionId) }
        }
        appScope.launch {
            captureState.get().state.map { it.running }.distinctUntilChanged().collect { processState.get().setCapture(it) }
        }
        appScope.launch {
            try {
                val closed = loggingEngine.get().recoverAbandonedSessions()
                if (closed > 0) Log.i("NetLogger", "recovered $closed abandoned session(s)")
            } catch (e: Exception) {
                Log.w("NetLogger", "session recovery failed", e)
            }
        }
    }
}
