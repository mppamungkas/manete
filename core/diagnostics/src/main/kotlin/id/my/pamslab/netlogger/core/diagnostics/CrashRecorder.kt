package id.my.pamslab.netlogger.core.diagnostics

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File

/**
 * Writes the Java stack trace of an uncaught exception, with the process' own last log lines, to
 * files/problems/pending/java_<pid>.txt, then hands over to the previous handler (Android's crash dialog and exit
 * record stay as they are). The exit record of a Java crash has no stack trace; [ProblemReports] joins the two by pid.
 * Installed first thing in every process (main and :vpn).
 */
object CrashRecorder {

    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val dir = pendingDir(context)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                write(dir, thread, error)
            } catch (t: Throwable) { // the original crash must still go through
                Log.w(TAG, "crash not recorded", t)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(dir: File, thread: Thread, error: Throwable) {
        dir.mkdirs()
        val pid = Process.myPid()
        val text = buildString {
            appendLine("thread: ${thread.name}")
            appendLine("timeMs: ${System.currentTimeMillis()}")
            appendLine()
            appendLine(Log.getStackTraceString(error).trimEnd())
            appendLine()
            appendLine("log:")
            OwnLog.linesOf(pid).forEach(::appendLine)
        }
        File(dir, "java_$pid.txt").writeText(text)
    }

    internal fun pendingDir(context: Context) = File(context.filesDir, "problems/pending")
}
