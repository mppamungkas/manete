package id.my.pamslab.netlogger.core.diagnostics

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File

/** Shares the raw tombstone from cache/problems/ (read-only, per-URI grant); separate from the export provider. */
class ProblemFileProvider : FileProvider()

/** Building and sending reports (decision 005). Nothing is sent without the user choosing an app. */
object ProblemSharing {

    const val SOURCE_URL = "https://github.com/mppamungkas/manete"

    /** Manifest meta-data written by app/build.gradle.kts. */
    private const val BUILD_COMMIT_KEY = "id.my.pamslab.netlogger.BUILD_COMMIT"

    /** The phone and the installed app. Never throws. */
    fun reportContext(context: Context): ReportContext {
        val pm = context.packageManager
        val version = runCatching { pm.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
        val commit = runCatching { pm.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData?.getString(BUILD_COMMIT_KEY) }.getOrNull()
        return ReportContext(version, commit, Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT)
    }

    /**
     * The share sheet with the report as text; with [rawTrace] (only when the user ticked the box, decision 005 D5)
     * the tombstone is attached as a file.
     */
    fun shareIntent(context: Context, title: String, report: String, rawTrace: File?): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Manete: $title")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        rawTrace?.let { trace ->
            val dir = File(context.cacheDir, "problems").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val copy = File(dir, "manete-tombstone-${trace.parentFile?.name ?: "crash"}.pb")
            trace.copyTo(copy, overwrite = true)
            val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.problems", copy)
            send.putExtra(Intent.EXTRA_STREAM, uri)
            send.clipData = ClipData.newRawUri(copy.name, uri)
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, title)
    }

    /** A new GitHub issue with title and (shortened) body filled in; the caller copies the full report first. */
    fun githubIssueIntent(title: String, report: String): Intent {
        val uri = Uri.parse("$SOURCE_URL/issues/new").buildUpon()
            .appendQueryParameter("title", title)
            .appendQueryParameter("body", IssueText.body(title, report))
            .build()
        return Intent(Intent.ACTION_VIEW, uri)
    }
}
