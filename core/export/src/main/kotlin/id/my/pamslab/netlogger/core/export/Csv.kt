package id.my.pamslab.netlogger.core.export

import java.math.BigDecimal

/**
 * RFC 4180 CSV: comma separator, CRLF line ends, header row, UTF-8 (set by the caller's writer). Null is an empty
 * field, never 0 or -999. Numbers use "." and never scientific notation, independent of the device locale.
 */
class CsvWriter(private val out: Appendable) {

    fun header(columns: List<String>) = row(columns)

    fun row(values: List<Any?>) {
        values.forEachIndexed { i, v ->
            if (i > 0) out.append(',')
            out.append(escape(csvText(v)))
        }
        out.append("\r\n")
    }

    companion object {
        fun escape(text: String): String {
            val quote = text.any { it == ',' || it == '"' || it == '\n' || it == '\r' } ||
                (text.isNotEmpty() && (text.first().isWhitespace() || text.last().isWhitespace()))
            return if (quote) "\"" + text.replace("\"", "\"\"") + "\"" else text
        }
    }
}

/** Locale-independent text of one value; null, NaN and infinities are empty. */
fun csvText(v: Any?): String = when (v) {
    null -> ""
    is String -> v
    is Boolean -> v.toString()
    is Float -> if (v.isFinite()) plain(BigDecimal(v.toString())) else ""
    is Double -> if (v.isFinite()) plain(BigDecimal(v.toString())) else ""
    is Enum<*> -> v.name
    else -> v.toString() // Int, Long: Kotlin's toString is locale-independent
}

/** Plain decimal without trailing zeros: 28.0 -> "28", 1.0E-5 -> "0.00001". */
private fun plain(d: BigDecimal): String {
    val s = d.stripTrailingZeros().toPlainString()
    return if (s == "-0") "0" else s
}
