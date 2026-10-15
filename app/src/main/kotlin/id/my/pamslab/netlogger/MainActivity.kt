package id.my.pamslab.netlogger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import id.my.pamslab.netlogger.core.ui.theme.NetLoggerTheme
import id.my.pamslab.netlogger.navigation.NetLoggerApp

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NetLoggerTheme {
                NetLoggerApp()
            }
        }
    }
}
