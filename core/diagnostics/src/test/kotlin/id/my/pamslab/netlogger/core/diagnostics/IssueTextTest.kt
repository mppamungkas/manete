package id.my.pamslab.netlogger.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IssueTextTest {

    private val header = "NetLogger problem report\nApp        0.1.0 (build d86e809)\nWhat       Crash (native): SIGSEGV\n\nTrace\n"

    @Test
    fun `short reports go in whole`() {
        val report = header + "  #00 libmaplibre.so render+144\n\nLog (NetLogger's own lines before the end, 1)\n  a line\n"
        assertEquals("```\n$report\n```", IssueText.body("t", report))
    }

    @Test
    fun `long reports drop the log, then trace lines, until the encoded length fits`() {
        // Like the moto's 9.8 KB Java report whose issue URL GitHub refused.
        val frames = (0 until 120).joinToString("\n") { "  #%02d libart.so bool art::interpreter::DoCall<false>(art::ArtMethod*, art::Thread*)+2416".format(it) }
        val log = (0 until 60).joinToString("\n") { "  10-16 12:16:40.554 15805 15805 D nativeloader: Configuring clns-7 for other apk /data/app/…/base.apk" }
        val report = "$header$frames\n\nLog (NetLogger's own lines before the end, 60)\n$log\n"
        val title = "Crash (native): SIGSEGV (SEGV_MAPERR) in libmaplibre.so mln::android::MapRenderer::render(_JNIEnv&)"
        val body = IssueText.body(title, report)

        assertTrue(IssueText.encodedLength(title) + IssueText.encodedLength(body) <= IssueText.MAX_ENCODED)
        assertTrue(body.startsWith("```\nNetLogger problem report\n"))
        assertTrue(body.contains("  #00 libart.so"))
        assertFalse(body.contains("nativeloader"))
        assertTrue(body.endsWith("full report, with the log, is on the clipboard: paste it below.)"))
    }

    @Test
    fun `encoded length as Android's Uri encode`() {
        // a, b, *, ~, -, _, . stay (7); space, \n, <, >, &, : are %XX (6 x 3); é is two UTF-8 bytes (2 x 3).
        assertEquals(31, IssueText.encodedLength("a b\n<>&*:é~-_."))
    }
}
