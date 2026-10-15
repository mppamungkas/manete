package id.my.pamslab.netlogger.feature.settings

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Version, build commit and launcher icon of the installed app. Never throws. */
internal data class AppFacts(val version: String, val commit: String, val icon: ImageBitmap?)

internal fun appFacts(context: Context, iconPx: Int): AppFacts {
    val pm = context.packageManager
    val info = runCatching { pm.getPackageInfo(context.packageName, 0) }.getOrNull()
    val meta = runCatching { pm.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData }.getOrNull()
    return AppFacts(
        version = info?.versionName ?: "?",
        commit = meta?.getString(BUILD_COMMIT_KEY) ?: "unknown",
        icon = runCatching { pm.getApplicationIcon(context.packageName).toBitmap(iconPx, iconPx).asImageBitmap() }
            .onFailure { Log.w("NetLogger", "about: app icon not loaded", it) }.getOrNull(),
    )
}

/** Manifest meta-data written by app/build.gradle.kts. */
private const val BUILD_COMMIT_KEY = "id.my.pamslab.netlogger.BUILD_COMMIT"

/** Settings › About (decision 003, step 5). */
@Composable
internal fun AboutScreen(onBack: () -> Unit, onLicence: (Licence) -> Unit) {
    val context = LocalContext.current
    val iconPx = with(LocalDensity.current) { 56.dp.roundToPx() }
    val facts by produceState<AppFacts?>(null) { value = withContext(Dispatchers.Default) { appFacts(context, iconPx) } }

    SettingsPage(stringResource(R.string.settings_about), onBack) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            facts?.icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(56.dp)) }
            Column {
                Text("NetLogger", style = MaterialTheme.typography.titleLarge)
                facts?.let { Text(stringResource(R.string.about_version, it.version, it.commit), style = MaterialTheme.typography.bodyMedium) }
            }
        }
        Text(stringResource(R.string.about_tagline), style = MaterialTheme.typography.bodyMedium)
        SettingCard(stringResource(R.string.about_licence_title), stringResource(R.string.about_licence_info)) {
            Text(stringResource(R.string.about_source, SOURCE_URL), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { onLicence(Licence.GPL_3) }) { Text(stringResource(R.string.about_show_licence)) }
        }
        SettingCard(stringResource(R.string.about_privacy_title), "") {
            stringArrayResource(R.array.about_privacy).forEach { Bullet(it) }
        }
        SettingCard(stringResource(R.string.about_map_title), stringResource(R.string.about_map_info)) {}
        SettingCard(stringResource(R.string.about_credits_title), stringResource(R.string.about_credits_info)) {
            CREDITS.forEach { credit -> CreditItem(credit, onLicence) }
        }
    }
}

@Composable
private fun CreditItem(credit: Credit, onLicence: (Licence) -> Unit) {
    val licence = credit.licence
    Column(
        Modifier.fillMaxWidth()
            .then(if (licence != null) Modifier.clickable { onLicence(licence) } else Modifier)
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        HorizontalDivider()
        Text(credit.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
        Text("${credit.holder} · ${credit.licenceName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        Text(credit.note ?: credit.use, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One licence's full text. */
@Composable
internal fun LicenceScreen(licence: Licence?, onBack: () -> Unit) {
    val context = LocalContext.current
    val text by produceState<String?>(null, licence) {
        value = licence?.let { l ->
            withContext(Dispatchers.IO) {
                runCatching { context.resources.openRawResource(l.raw).bufferedReader().use { it.readText() } }
                    .onFailure { Log.w("NetLogger", "about: licence text not loaded", it) }.getOrNull()
            }
        }
    }
    val title = remember(licence) { licence?.title ?: "" }
    SettingsPage(stringResource(R.string.about_licence_page, title), onBack, backLabel = stringResource(R.string.settings_back_about)) {
        Text(
            text ?: if (licence == null) stringResource(R.string.about_licence_not_found) else "",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}
