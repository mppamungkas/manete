package id.my.pamslab.netlogger.core.diagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** The phone and app, for the report header; passed in so the builder stays pure. */
data class ReportContext(
    val appVersion: String,
    val buildCommit: String?,
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    val timeZone: TimeZone = TimeZone.getDefault(),
)

/** What a report is built from: a kept [Problem] with its files, or only a user's note ("Report a problem"). */
data class ReportInput(
    val problem: Problem?,
    /** java.txt of a Java crash ([CrashRecorder]). */
    val javaText: String? = null,
    /** trace.bin: the tombstone of a native crash or the thread dump of an ANR. */
    val trace: ByteArray? = null,
    /** log.txt: the ended process' lines read at the next start; used when java.txt has none. */
    val logLines: List<String> = emptyList(),
    val userNote: String? = null,
    /** When the user made the report ("Report a problem"); a problem has its own time. */
    val reportedAtMs: Long? = null,
)

/**
 * Plain-text problem report (decision 005): readable by a teacher, useful to the developer. Contains no location,
 * cell identity, SSID or capture content: the trace is summarised (tombstone: signal and the crashing thread only) and
 * the log lines are filtered again ([OwnLog.filter]).
 */
object ReportBuilder {

    /** Raise when [title] changes, so kept reports get the new title. 2: short ANR reason. */
    const val TITLE_VERSION = 2

    private const val MAX_STACK_LINES = 60
    private const val LABEL = 11

    fun build(input: ReportInput, ctx: ReportContext): String {
        val p = input.problem
        val java = input.javaText?.let(::parseJava)
        val tombstone = input.trace?.takeIf { p?.kind == ProblemKind.CRASH_NATIVE }?.let(TombstoneSummary::parse)
        val time = java?.timeMs ?: p?.timeMs ?: input.reportedAtMs
        return buildString {
            appendLine(if (p == null) "NetLogger problem report (reported by the user)" else "NetLogger problem report")
            line("App", "${ctx.appVersion} (build ${ctx.buildCommit ?: "unknown"})")
            line("Phone", "${ctx.manufacturer} ${ctx.model}, Android ${ctx.androidRelease} (API ${ctx.sdkInt})")
            time?.let { line("Time", formatTime(it, ctx.timeZone)) }
            if (p != null) {
                val crashMs = java?.timeMs
                if (crashMs != null && p.timeMs - crashMs > PROCESS_END_GAP_MS) {
                    line("Ended", "${formatTime(p.timeMs, ctx.timeZone)} (Android kept the process until then)")
                }
                line("Process", "${processLabel(p.processName)} · ${importanceLabel(p.importance)}")
                line("What", what(p, java, tombstone))
                line("Running", running(p.state))
                if (p.kind == ProblemKind.ANR) p.description?.let { line("Android", it) }
            }
            input.userNote?.trim()?.takeIf { it.isNotEmpty() }?.let {
                appendLine()
                appendLine("Your note")
                appendLine(it)
            }
            val trace = when {
                java != null -> java.stack
                tombstone != null -> tombstoneLines(tombstone)
                p?.kind == ProblemKind.ANR -> input.trace?.let { anrMainThread(it.toString(Charsets.UTF_8)) }.orEmpty()
                else -> emptyList()
            }
            if (trace.isNotEmpty()) {
                appendLine()
                appendLine("Trace")
                trace.forEach { appendLine("  $it") }
            }
            val log = OwnLog.filter(java?.log?.takeIf { it.isNotEmpty() } ?: input.logLines)
            appendLine()
            if (log.isEmpty()) {
                appendLine("Log: none readable")
            } else {
                appendLine("Log (NetLogger's own lines before the end, ${log.size})")
                log.forEach { appendLine("  $it") }
            }
        }
    }

    /** The crash time written by [CrashRecorder] into java.txt, if any. */
    fun crashTimeMs(javaText: String?): Long? = javaText?.let { parseJava(it).timeMs }

    /** One line for a list, a notification or an issue title: "Crash (native): SIGSEGV in libmaplibre.so …". */
    fun title(problem: Problem, javaText: String? = null, trace: ByteArray? = null): String {
        val java = javaText?.let(::parseJava)
        val tombstone = trace?.takeIf { problem.kind == ProblemKind.CRASH_NATIVE }?.let(TombstoneSummary::parse)
        return what(problem, java, tombstone)
    }

    private fun StringBuilder.line(label: String, value: String) {
        appendLine(label.padEnd(LABEL) + value)
    }

    private fun what(p: Problem, java: JavaCrash?, tombstone: TombstoneSummary?): String = when (p.kind) {
        ProblemKind.CRASH -> java?.exception?.let { "Crash: $it" } ?: "Crash (no stack trace kept)"
        ProblemKind.CRASH_NATIVE -> "Crash (native): " + (tombstone?.headline() ?: "signal ${p.status}")
        ProblemKind.ANR -> "Not responding (ANR)" + (anrReason(p.description)?.let { ": $it" } ?: "")
        ProblemKind.KILLED_WHILE_RECORDING -> "Ended by Android during a recording (${p.reasonName})"
    }

    /**
     * "user request after error: Input dispatching timed out (a9dc… is not responding. Waited 10000ms …)." (A56) ->
     * "Input dispatching timed out"; the full text stays in the report's "Android" line.
     */
    internal fun anrReason(description: String?): String? =
        description?.substringAfter("after error: ")?.substringBefore(" (")?.trim()?.trimEnd('.')?.takeIf { it.isNotEmpty() }

    private fun running(state: ProcessStateSummary?): String {
        if (state == null) return "unknown"
        val screen = state.screen?.removeSuffix("Route")?.let { "$it screen" }
        val recording = state.sessionId?.let { "recording session $it" } ?: "no recording"
        val capture = if (state.capture) "capture on" else "no capture"
        return listOfNotNull(screen, recording, capture).joinToString("; ")
    }

    private fun processLabel(name: String) = if (name.endsWith(":vpn")) "capture (:vpn)" else "main"

    /** RunningAppProcessInfo importance in words. */
    internal fun importanceLabel(importance: Int) = when {
        importance <= 100 -> "foreground"
        importance <= 125 -> "foreground service"
        importance <= 230 -> "visible"
        importance <= 300 -> "service"
        else -> "background"
    }

    private fun tombstoneLines(t: TombstoneSummary): List<String> = buildList {
        add(listOfNotNull(t.signal, t.faultAddress?.let { "fault address 0x%x".format(it) }).joinToString(", "))
        t.cause?.let { add("Cause: $it") }
        t.abortMessage?.let { add("Abort: $it") }
        t.threadName?.let { add("Thread: $it") }
        addAll(t.frames)
    }

    private fun formatTime(ms: Long, zone: TimeZone): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss XXX", Locale.ROOT).apply { timeZone = zone }.format(Date(ms))

    /** A gap above this between the crash and the process end is worth a line (crash dialog left open). */
    private const val PROCESS_END_GAP_MS = 5_000L

    internal data class JavaCrash(val timeMs: Long?, val exception: String?, val stack: List<String>, val log: List<String>)

    /** java.txt: "thread: …", "timeMs: …", blank, the stack trace, blank, "log:", the log lines. */
    internal fun parseJava(text: String): JavaCrash {
        val lines = text.lines()
        val timeMs = lines.firstOrNull { it.startsWith("timeMs: ") }?.removePrefix("timeMs: ")?.trim()?.toLongOrNull()
        val logAt = lines.indexOf("log:")
        val body = (if (logAt >= 0) lines.subList(0, logAt) else lines)
            .dropWhile { it.startsWith("thread: ") || it.startsWith("timeMs: ") || it.isBlank() }
            .dropLastWhile { it.isBlank() }
        val log = if (logAt >= 0) lines.drop(logAt + 1).filter { it.isNotBlank() } else emptyList()
        // The innermost "Caused by" names the real error.
        val exception = (body.lastOrNull { it.startsWith("Caused by: ") }?.removePrefix("Caused by: ") ?: body.firstOrNull())
        return JavaCrash(timeMs, exception, body.take(MAX_STACK_LINES), log)
    }

    /** The main thread's block of an ANR thread dump ("\"main\" prio=…" to the next blank line). */
    internal fun anrMainThread(dump: String): List<String> {
        val lines = dump.lines()
        val start = lines.indexOfFirst { it.startsWith("\"main\"") }
        if (start < 0) return emptyList()
        return lines.drop(start).takeWhile { it.isNotBlank() }.take(MAX_STACK_LINES)
    }
}
