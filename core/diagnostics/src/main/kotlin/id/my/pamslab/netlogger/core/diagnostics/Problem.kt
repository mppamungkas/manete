package id.my.pamslab.netlogger.core.diagnostics

import kotlinx.serialization.Serializable

/** What kind of unexpected end a [Problem] is (decision 005). */
enum class ProblemKind {
    /** Uncaught Java/Kotlin exception. */
    CRASH,

    /** Native crash (signal); Android keeps a tombstone. */
    CRASH_NATIVE,

    /** The app froze ("isn't responding"); Android keeps a thread dump. */
    ANR,

    /** Android ended the process (low memory, battery or resource limits) while a session was recording. */
    KILLED_WHILE_RECORDING,
}

/** One kept problem report: an unexpected end of one of NetLogger's processes, as Android recorded it. */
@Serializable
data class Problem(
    /** Directory name under files/problems/. */
    val id: String,
    val kind: ProblemKind,
    /** When the process ended (wall clock, ms). */
    val timeMs: Long,
    val pid: Int,
    /** "id.my.pamslab.netlogger" or "id.my.pamslab.netlogger:vpn". */
    val processName: String,
    /** ApplicationExitInfo reason and its name, e.g. 5 / "CRASH_NATIVE". */
    val reason: Int,
    val reasonName: String,
    /** Signal number for a native crash or a kill, exit status otherwise. */
    val status: Int,
    /** RunningAppProcessInfo importance when it ended (100 = foreground). */
    val importance: Int,
    val description: String?,
    val pssKb: Long,
    val rssKb: Long,
    /** What was running, from the process state summary ([ProcessStateSummary]); null if it was never set. */
    val state: ProcessStateSummary?,
    /** Files kept with the report. */
    val hasTrace: Boolean,
    val hasJavaStack: Boolean,
    val hasLog: Boolean,
    /** The user has seen it on Live (or dismissed it). */
    val seen: Boolean = false,
    /** One line, [ReportBuilder.title]; set when the report is kept. */
    val title: String? = null,
    /** When the crash happened, from the Java stack trace; can be well before [timeMs] (crash dialog left open). */
    val crashTimeMs: Long? = null,
) {
    /** The time to show: the crash, else the end of the process. */
    val shownTimeMs: Long get() = crashTimeMs ?: timeMs
}

/**
 * What was running in a process, kept by Android with its next exit record (ActivityManager.setProcessStateSummary,
 * at most 128 bytes). Encoded as "s=12;c=1;v=map".
 */
@Serializable
data class ProcessStateSummary(
    /** The recording session's id; null when not recording. */
    val sessionId: Long? = null,
    val capture: Boolean = false,
    /** The open screen (navigation route name, shortened). */
    val screen: String? = null,
) {
    fun encode(): ByteArray {
        val parts = listOfNotNull(
            sessionId?.let { "s=$it" },
            "c=${if (capture) 1 else 0}",
            screen?.let { "v=" + it.filter { c -> c.isLetterOrDigit() || c == '_' }.take(MAX_SCREEN) },
        )
        return parts.joinToString(";").toByteArray(Charsets.US_ASCII).take(MAX_BYTES).toByteArray()
    }

    companion object {
        /** ActivityManager's limit. */
        const val MAX_BYTES = 128
        private const val MAX_SCREEN = 40

        /** Null for null, empty or unreadable bytes. */
        fun decode(bytes: ByteArray?): ProcessStateSummary? {
            val text = bytes?.toString(Charsets.US_ASCII)?.takeIf { it.isNotBlank() } ?: return null
            val map = text.split(';').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            if (map.isEmpty()) return null
            return ProcessStateSummary(
                sessionId = map["s"]?.toLongOrNull(),
                capture = map["c"] == "1",
                screen = map["v"]?.takeIf { it.isNotEmpty() },
            )
        }
    }
}

/** ApplicationExitInfo reasons used here (public constants, mirrored so the decision logic stays unit-testable). */
object ExitReason {
    const val UNKNOWN = 0
    const val EXIT_SELF = 1
    const val SIGNALED = 2
    const val LOW_MEMORY = 3
    const val CRASH = 4
    const val CRASH_NATIVE = 5
    const val ANR = 6
    const val INITIALIZATION_FAILURE = 7
    const val PERMISSION_CHANGE = 8
    const val EXCESSIVE_RESOURCE_USAGE = 9
    const val USER_REQUESTED = 10
    const val USER_STOPPED = 11
    const val DEPENDENCY_DIED = 12
    const val OTHER = 13
    const val FREEZER = 14
    const val PACKAGE_STATE_CHANGE = 15
    const val PACKAGE_UPDATED = 16

    fun name(reason: Int): String = when (reason) {
        EXIT_SELF -> "EXIT_SELF"
        SIGNALED -> "SIGNALED"
        LOW_MEMORY -> "LOW_MEMORY"
        CRASH -> "CRASH"
        CRASH_NATIVE -> "CRASH_NATIVE"
        ANR -> "ANR"
        INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        USER_REQUESTED -> "USER_REQUESTED"
        USER_STOPPED -> "USER_STOPPED"
        DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        OTHER -> "OTHER"
        FREEZER -> "FREEZER"
        PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        else -> "UNKNOWN($reason)"
    }

    /**
     * Which exits become a report (decision 005, D2): every crash and ANR; Android ending the process only while a
     * session was recording; never a force stop, a normal exit, an update or a permission change.
     */
    fun classify(reason: Int, state: ProcessStateSummary?): ProblemKind? = when (reason) {
        CRASH -> ProblemKind.CRASH
        CRASH_NATIVE -> ProblemKind.CRASH_NATIVE
        ANR -> ProblemKind.ANR
        LOW_MEMORY, SIGNALED, EXCESSIVE_RESOURCE_USAGE, FREEZER, OTHER, UNKNOWN ->
            ProblemKind.KILLED_WHILE_RECORDING.takeIf { state?.sessionId != null }
        else -> null
    }
}
