package id.my.pamslab.netlogger.core.diagnostics

/**
 * The body of a pre-filled GitHub issue (decision 005). It travels in the URL, and GitHub refuses URLs above about
 * 8 KB ("your request URL is too long", seen 2026-10-16 with a 9.8 KB report). Percent-encoding makes spaces, line
 * breaks and the `<>&*:` of C++ frames three characters each, so the limit is applied to the encoded length: the
 * header, the note and as much of the trace as fits; the log lines are left out (the full report is on the clipboard).
 */
object IssueText {

    /** Encoded title + body, leaving room for the rest of the URL. */
    const val MAX_ENCODED = 7_000

    private const val FENCE = "```"
    private const val CUT_NOTE = "(Shortened for GitHub. The full report, with the log, is on the clipboard: paste it below.)"

    fun body(title: String, report: String): String {
        val budget = MAX_ENCODED - encodedLength(title)
        fun wrap(text: String, cut: Boolean) = "$FENCE\n$text\n$FENCE" + if (cut) "\n$CUT_NOTE" else ""
        if (encodedLength(wrap(report, cut = false)) <= budget) return wrap(report, cut = false)
        // Without the log, then trace lines from the end until it fits.
        val lines = report.lines().let { all ->
            val log = all.indexOfFirst { it.startsWith("Log (") || it.startsWith("Log:") }
            if (log > 0) all.subList(0, log).dropLastWhile { it.isBlank() } else all
        }.toMutableList()
        while (lines.isNotEmpty() && encodedLength(wrap(lines.joinToString("\n") + "\n…", cut = true)) > budget) {
            lines.removeAt(lines.lastIndex)
        }
        return wrap(lines.joinToString("\n") + "\n…", cut = true)
    }

    /** Length after Android's Uri.encode (letters, digits and `_-!.~'()*` stay; every other UTF-8 byte becomes %XX). */
    fun encodedLength(text: String): Int = text.toByteArray(Charsets.UTF_8).sumOf { b ->
        val c = b.toInt() and 0xFF
        val kept = c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code || c.toChar() in KEPT
        if (kept) 1 else 3
    }

    private const val KEPT = "_-!.~'()*"
}
