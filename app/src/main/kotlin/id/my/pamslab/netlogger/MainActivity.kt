package id.my.pamslab.netlogger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.lifecycleScope
import id.my.pamslab.netlogger.core.diagnostics.ProblemReports
import id.my.pamslab.netlogger.core.diagnostics.ProcessState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import id.my.pamslab.netlogger.core.ui.theme.NetLoggerTheme
import id.my.pamslab.netlogger.navigation.NetLoggerApp
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var processState: ProcessState

    @Inject lateinit var problemReports: ProblemReports

    override fun onStart() {
        super.onStart()
        // Also on return to the app: a :vpn crash while the main process kept running is reported without a restart.
        lifecycleScope.launch(Dispatchers.IO) { problemReports.refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NetLoggerTheme {
                NetLoggerApp(onScreen = processState::setScreen)
            }
        }
    }
}
