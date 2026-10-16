package id.my.pamslab.netlogger.core.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tells Android what this process is doing, so the next exit record of it says whether a recording or a capture was
 * running and which screen was open (decision 005). One instance per process; each change replaces the summary.
 */
@Singleton
class ProcessState @Inject constructor(@ApplicationContext private val context: Context) {

    private var current = ProcessStateSummary()

    @Synchronized
    fun setRecording(sessionId: Long?) = update(current.copy(sessionId = sessionId))

    @Synchronized
    fun setCapture(on: Boolean) = update(current.copy(capture = on))

    @Synchronized
    fun setScreen(screen: String?) = update(current.copy(screen = screen))

    private fun update(next: ProcessStateSummary) {
        if (next == current) return
        current = next
        try {
            context.getSystemService(ActivityManager::class.java)?.setProcessStateSummary(next.encode())
        } catch (e: RuntimeException) { // never let diagnostics break the app
            Log.w(TAG, "process state summary not set", e)
        }
    }
}

internal const val TAG = "NetLogger"
