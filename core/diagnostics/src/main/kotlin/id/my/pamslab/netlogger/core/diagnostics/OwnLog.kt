package id.my.pamslab.netlogger.core.diagnostics

import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * NetLogger's own log lines. An app may read the lines of its own UID with `logcat` (no permission). Lines with a
 * coordinate-like number pair or a map tile URL are dropped (decision 005: no location in a report). The lines are
 * those of every tag in NetLogger's processes, including libraries (MapLibre logs its tile requests).
 */
object OwnLog {
    const val MAX_LINES = 200
    private const val TIMEOUT_MS = 1_500L

    /** The last [MAX_LINES] lines of process [pid] still in the log buffer; empty if none or unreadable. */
    fun linesOf(pid: Int): List<String> = read(listOf("logcat", "-d", "-v", "threadtime", "-t", MAX_LINES.toString(), "--pid", pid.toString()))

    private fun read(command: List<String>): List<String> = try {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val lines = process.inputStream.bufferedReader().useLines { seq -> seq.toList() }
        if (!process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) process.destroy()
        filter(lines)
    } catch (e: Exception) {
        Log.w(TAG, "own log not readable", e)
        emptyList()
    }

    private val COORDINATES = Regex("""-?\d{1,3}\.\d{4,}\s*[,/ ]\s*-?\d{1,3}\.\d{4,}""")

    /** Map tile requests ("…/17/102092/65867.png", MapLibre logs them) give away the area viewed. */
    private val TILE = Regex("""/\d{1,2}/\d+/\d+\.(png|pbf|jpg|webp)""")

    /** Drops logcat's "--------- beginning of" separators and any line with a coordinate-like pair or a map tile. */
    fun filter(lines: List<String>): List<String> =
        lines.filterNot { it.startsWith("--------- beginning of") || COORDINATES.containsMatchIn(it) || TILE.containsMatchIn(it) }
            .takeLast(MAX_LINES)
}
