package id.my.pamslab.netlogger.core.export

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.ZoneOffset

/** docs/columns.md must document exactly the exported CSV columns, in order. */
class ColumnsDocTest {

    private val doc = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "docs/columns.md") }
        .first { it.exists() }
        .readText()

    /** First-column names (`name`) of the table under "## <file>". */
    private fun documented(file: String): List<String> = doc.substringAfter("## $file\n").substringBefore("\n## ")
        .lines()
        .mapNotNull { Regex("""^\| `([A-Za-z]+)` \|""").find(it)?.groupValues?.get(1) }

    @Test
    fun `every CSV column is documented, in export order`() {
        val columns = Columns(IsoTime(ZoneOffset.UTC))
        mapOf(
            "measurements.csv" to columns.measurements.map { it.name },
            "cells.csv" to columns.cells.map { it.name },
            "flat.csv" to columns.flat.map { it.name },
            "events.csv" to columns.events.map { it.name },
            "warnings.csv" to columns.warnings.map { it.name },
        ).forEach { (file, names) -> assertEquals(file, names, documented(file)) }
    }
}
