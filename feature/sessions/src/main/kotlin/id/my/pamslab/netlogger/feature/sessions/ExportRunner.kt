package id.my.pamslab.netlogger.feature.sessions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import id.my.pamslab.netlogger.core.diagnostics.CaughtErrors
import id.my.pamslab.netlogger.core.export.ExportProgress
import id.my.pamslab.netlogger.core.export.ExportResult
import id.my.pamslab.netlogger.core.export.SessionExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** One export and where it is; kept after it ends so the session screen can show the result when it comes back. */
data class ExportJob(
    val sessionId: Long,
    val kind: Kind,
    val progress: ExportProgress? = null,
    /** Set when finished: the success line or the error. */
    val message: String? = null,
    val failed: Boolean = false,
    /** Share: the chooser to start, until the screen has started it. */
    val shareIntent: Intent? = null,
) {
    enum class Kind { SAVE, SHARE }

    val running: Boolean get() = message == null
}

/**
 * Runs session exports outside any screen (2026-10-16: an export in the session screen's ViewModel was cancelled when
 * the screen was left and left half a ZIP in Download). One export at a time, in its own scope for the app's
 * lifetime. "Save ZIP" writes to a cache file first and copies it to the picked document only when it is complete; on
 * any failure the document is deleted, so no partial file is left behind.
 */
@Singleton
class ExportRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val exporter: SessionExporter,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _job = MutableStateFlow<ExportJob?>(null)

    /** The current or last export (any session). */
    val job: StateFlow<ExportJob?> = _job.asStateFlow()

    /** False while another export runs. [includePlanImages]: decision 006, D8. */
    fun save(sessionId: Long, uri: Uri, includePlanImages: Boolean = true): Boolean = start(sessionId, ExportJob.Kind.SAVE) { progress ->
        val tmp = tempFile()
        try {
            val result = tmp.outputStream().use { exporter.export(sessionId, it, includePlanImages = includePlanImages, onProgress = progress) }
            val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("cannot open the chosen file")
            out.use { o -> tmp.inputStream().use { it.copyTo(o, COPY_BUFFER) } }
            result to null
        } catch (e: Throwable) {
            deleteDocument(uri)
            throw e
        } finally {
            tmp.delete()
        }
    }

    /**
     * Writes the ZIP to cache/exports and offers it to the app the user picks. Older exports are deleted first: the
     * receiving app may still read the previous file after we return, so it is kept until the next one.
     */
    fun share(sessionId: Long, includePlanImages: Boolean = true): Boolean = start(sessionId, ExportJob.Kind.SHARE) { progress ->
        val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, exporter.fileName(sessionId))
        val result = try {
            file.outputStream().use { exporter.export(sessionId, it, includePlanImages = includePlanImages, onProgress = progress) }
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        result to Intent.createChooser(send, "Share ${file.name}")
    }

    /** The screen started the chooser. */
    fun shareStarted() = _job.update { it?.copy(shareIntent = null) }

    /** The screen showed the result; a finished job is forgotten. */
    fun dismiss(sessionId: Long) = _job.update { if (it?.sessionId == sessionId && !it.running) null else it }

    private fun start(
        sessionId: Long,
        kind: ExportJob.Kind,
        block: suspend (progress: (ExportProgress) -> Unit) -> Pair<ExportResult, Intent?>,
    ): Boolean {
        synchronized(this) {
            if (_job.value?.running == true) return false
            _job.value = ExportJob(sessionId, kind)
        }
        scope.launch {
            val ended = try {
                val (r, intent) = block { p -> _job.update { it?.copy(progress = p) } }
                ExportJob(
                    sessionId, kind,
                    message = "Exported ${r.counts.measurements} ticks, ${r.counts.cells} cells, ${r.counts.events} events: ${r.fileName}",
                    shareIntent = intent,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) { // missing or still-recording session
                ExportJob(sessionId, kind, message = e.message ?: "Export not possible", failed = true)
            } catch (e: Exception) { // IOException, SecurityException, a full disk …
                CaughtErrors.caught("export failed", e)
                ExportJob(sessionId, kind, message = "Export failed: ${e.message ?: e.javaClass.simpleName}. No file was kept.", failed = true)
            }
            _job.value = ended
        }
        return true
    }

    private fun tempFile(): File {
        val dir = File(context.cacheDir, TEMP_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // left by an export the system ended
        return File(dir, "export.zip.part")
    }

    private fun deleteDocument(uri: Uri) {
        try {
            if (!DocumentsContract.deleteDocument(context.contentResolver, uri)) Log.w(TAG, "partial export not deleted")
        } catch (e: Exception) { // provider without delete support, or already gone
            CaughtErrors.caught("partial export not deleted", e)
        }
    }

    private companion object {
        const val TAG = "NetLogger"
        const val EXPORT_DIR = "exports"
        const val TEMP_DIR = "export-tmp"
        const val COPY_BUFFER = 256 * 1024
    }
}
