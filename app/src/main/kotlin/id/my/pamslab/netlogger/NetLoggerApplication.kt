package id.my.pamslab.netlogger

import android.app.Application
import android.util.Log
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import id.my.pamslab.netlogger.feature.logger.service.LoggingEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class NetLoggerApplication : Application() {

    // Lazy: the native capture's own process (":vpn") must not build the logging engine or open the database.
    @Inject lateinit var loggingEngine: Lazy<LoggingEngine>

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Only the main process: the native capture runs in ":vpn" while a session may be recording here, and this
        // recovery would close that live session.
        if (getProcessName() != packageName) return
        // A new process means no session is recording here: close the ones a killed process left open
        // (endedAt = last measurement). Starting a session does the same, so a race with the service is harmless.
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
