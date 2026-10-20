package id.my.pamslab.netlogger.feature.settings

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import id.my.pamslab.netlogger.core.diagnostics.CaughtErrors
import java.util.Locale

/** One row of the capture app picker. [label] null: a selected package that is not (or no longer) visible. */
internal data class AppRow(val packageName: String, val label: String?, val icon: ImageBitmap?, val selected: Boolean)

internal data class InstalledApp(val packageName: String, val label: String, val icon: ImageBitmap?)

/**
 * Apps with a launcher icon, visible without QUERY_ALL_PACKAGES through the launcher `<queries>` entry in this
 * module's manifest. Never throws.
 */
internal fun launcherApps(context: Context, iconPx: Int): List<InstalledApp> = try {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    pm.queryIntentActivities(intent, 0)
        .distinctBy { it.activityInfo.packageName }
        .map { info ->
            val app = info.activityInfo.applicationInfo
            InstalledApp(
                packageName = info.activityInfo.packageName,
                label = runCatching { app.loadLabel(pm).toString() }.getOrNull() ?: info.activityInfo.packageName,
                icon = runCatching { app.loadIcon(pm).toBitmap(iconPx, iconPx).asImageBitmap() }.getOrNull(),
            )
        }
} catch (e: Exception) {
    CaughtErrors.caught("settings: listing launcher apps failed", e)
    emptyList()
}

/**
 * The picker's rows: apps [pinned] (selected when the page opened) first, also those not installed or hidden so
 * they can be removed; then the rest. Each part is sorted by name, and a tick never moves a row ([selected] only
 * sets the checkbox). [query] matches the name or the package name, ignoring case; selected apps always stay visible.
 */
internal fun pickerRows(installed: List<InstalledApp>, selected: List<String>, pinned: List<String>, query: String): List<AppRow> {
    val byPackage = installed.associateBy { it.packageName }
    val q = query.trim().lowercase(Locale.ROOT)
    val pinnedRows = (pinned + selected.filter { it !in byPackage && it !in pinned })
        .map { pkg -> byPackage[pkg].let { AppRow(pkg, it?.label, it?.icon, selected = pkg in selected) } }
    val rest = installed.filter { it.packageName !in pinned }
        .filter { it.packageName in selected || q.isEmpty() || q in it.label.lowercase(Locale.ROOT) || q in it.packageName.lowercase(Locale.ROOT) }
        .map { AppRow(it.packageName, it.label, it.icon, selected = it.packageName in selected) }
    val order = compareBy<AppRow, String>(String.CASE_INSENSITIVE_ORDER) { it.label ?: it.packageName }
    return pinnedRows.sortedWith(order) + rest.sortedWith(order)
}
