package id.my.pamslab.netlogger.feature.capture.vpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** libnetcapture is packaged for the phone's ABI and its JNI entry points link. */
@RunWith(AndroidJUnit4::class)
class NativeCaptureTest {

    @Test
    fun libraryLoadsAndAnswers() {
        assertNull(NativeCapture.loadError)
        val version = NativeCapture.version()
        assertTrue("version: $version", version?.startsWith("netcapture ") == true)
    }
}
