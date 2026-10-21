package id.my.pamslab.netlogger.feature.settings

import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource

private val SECTIONS: List<Pair<Int, Int>> = listOf(
    R.string.help_start_title to R.array.help_start,
    R.string.help_test_title to R.array.help_test,
    R.string.help_indoor_title to R.array.help_indoor,
    R.string.help_speed_title to R.array.help_speed,
    R.string.help_capture_title to R.array.help_capture,
    R.string.help_results_title to R.array.help_results,
    R.string.help_problems_title to R.array.help_problems,
    R.string.help_glossary_title to R.array.help_glossary,
)

/** Settings › How to use, for teachers and students (decision 003, step 5). */
@Composable
internal fun HelpScreen(onBack: () -> Unit) {
    SettingsPage(stringResource(R.string.settings_help), onBack) {
        Text(stringResource(R.string.help_intro), style = MaterialTheme.typography.bodyMedium)
        SECTIONS.forEach { (title, items) -> HelpSection(title, items) }
    }
}

@Composable
private fun HelpSection(@StringRes title: Int, @ArrayRes items: Int) {
    SettingCard(stringResource(title), "") {
        stringArrayResource(items).forEach { Bullet(it) }
    }
}
