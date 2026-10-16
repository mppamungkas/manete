package id.my.pamslab.netlogger

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log

/**
 * Debug builds only (decision 005, D6): ends the process on purpose, so every problem-report path can be checked on
 * a phone. Only the shell can send it (the receiver requires the DUMP permission):
 *
 *     adb shell am broadcast -n id.my.pamslab.netlogger.debug/id.my.pamslab.netlogger.CrashTestReceiver --es kind java|native|anr [--receiver-foreground]
 *
 * `CrashTestVpnReceiver` does the same in the :vpn process. (Debug builds have the app ID id.my.pamslab.netlogger.debug.)
 */
open class CrashTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = intent.getStringExtra("kind")
        Log.w("NetLogger", "crash test: $kind in ${Application.getProcessName()}")
        when (kind) {
            "java" -> throw IllegalStateException("NetLogger crash test (debug build)")
            "native" -> Process.sendSignal(Process.myPid(), SIGSEGV)
            // Blocks the main thread past the broadcast timeout (10 s with --receiver-foreground, else 60 s).
            "anr" -> Thread.sleep(ANR_SLEEP_MS)
        }
    }

    private companion object {
        const val SIGSEGV = 11
        const val ANR_SLEEP_MS = 75_000L
    }
}

/** [CrashTestReceiver] in the :vpn process. */
class CrashTestVpnReceiver : CrashTestReceiver()
