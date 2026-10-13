package id.my.pamslab.netlogger

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import id.my.pamslab.netlogger.feature.logger.service.LoggingEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class NetLoggerApplication : Application() {

    @Inject lateinit var loggingEngine: LoggingEngine

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // A new process means no session is recording here: close the ones a killed process left open
        // (endedAt = last measurement). Starting a session does the same, so a race with the service is harmless.
        appScope.launch {
            try {
                val closed = loggingEngine.recoverAbandonedSessions()
                if (closed > 0) Log.i("NetLogger", "recovered $closed abandoned session(s)")
            } catch (e: Exception) {
                Log.w("NetLogger", "session recovery failed", e)
            }
        }
    }
}
