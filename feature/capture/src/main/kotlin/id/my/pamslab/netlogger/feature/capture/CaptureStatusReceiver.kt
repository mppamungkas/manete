package id.my.pamslab.netlogger.feature.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** PCAPdroid's "capture stopped" broadcast (`running` = false), named in the start request. */
@AndroidEntryPoint
class CaptureStatusReceiver : BroadcastReceiver() {

    @Inject lateinit var controller: CaptureController

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Pcapdroid.ACTION_CAPTURE_STATUS) return
        if (intent.getBooleanExtra("running", true)) return
        Log.i("NetLogger", "PCAPdroid reports its capture stopped")
        controller.onPcapdroidStopped(Pcapdroid.stats(intent))
    }
}
