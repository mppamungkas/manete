package id.my.pamslab.netlogger.feature.sessions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import id.my.pamslab.netlogger.core.data.CaptureFileStore
import id.my.pamslab.netlogger.core.data.SessionRepository
import id.my.pamslab.netlogger.core.export.ExportResult
import id.my.pamslab.netlogger.core.export.SessionExporter
import id.my.pamslab.netlogger.core.model.session.Scenario
import id.my.pamslab.netlogger.core.model.session.SessionSummary
import id.my.pamslab.netlogger.feature.sessions.charts.ChartData
import id.my.pamslab.netlogger.feature.sessions.charts.ChartLoader
import id.my.pamslab.netlogger.feature.sessions.navigation.SessionDetailRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class SessionsViewModel @Inject constructor(repository: SessionRepository) : ViewModel() {
    /** null until the first database read. */
    val sessions: StateFlow<List<SessionSummary>?> = repository.observeSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

sealed interface ChartState {
    data object Loading : ChartState
    data object Empty : ChartState
    data class Ready(val data: ChartData) : ChartState
    data class Failed(val message: String) : ChartState
}

@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: SessionRepository,
    private val exporter: SessionExporter,
    private val chartLoader: ChartLoader,
    private val captureFiles: CaptureFileStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val id: Long = savedStateHandle.toRoute<SessionDetailRoute>().id

    val session: StateFlow<SessionSummary?> = repository.observeSession(id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _charts = MutableStateFlow<ChartState>(ChartState.Loading)
    val charts: StateFlow<ChartState> = _charts.asStateFlow()

    init {
        reloadCharts()
    }

    /** Charts are a snapshot; a recording session needs a reload to show newer ticks. */
    fun reloadCharts() {
        _charts.value = ChartState.Loading
        viewModelScope.launch {
            _charts.value = try {
                val data = withContext(Dispatchers.IO) { chartLoader.load(id) }
                if (data == null || data.ticks.isEmpty()) ChartState.Empty else ChartState.Ready(data)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "charts not loaded", e)
                ChartState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun save(label: String, notes: String, scenario: Scenario?) {
        viewModelScope.launch {
            repository.updateDetails(id, label.trim(), notes.trim(), scenario)
            _message.value = "Saved"
        }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            try {
                val uuid = session.value?.uuid
                repository.delete(id)
                // The session's traffic capture (UDP mode) goes with it.
                uuid?.let { withContext(Dispatchers.IO) { captureFiles.delete(it) } }
                onDeleted()
            } catch (e: IllegalArgumentException) {
                _message.value = e.message
            }
        }
    }

    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    private val _shareIntents = Channel<Intent>(Channel.BUFFERED)

    /** Chooser intents for the screen to start; one per Share click. */
    val shareIntents: Flow<Intent> = _shareIntents.receiveAsFlow()

    /** Suggested file name for "Save to…"; null on failure. */
    suspend fun exportFileName(): String? = try {
        exporter.fileName(id)
    } catch (e: IllegalArgumentException) {
        _message.value = e.message
        null
    }

    /** Writes the ZIP to a document the user picked (Storage Access Framework). */
    fun saveTo(uri: Uri) = runExport {
        val out = context.contentResolver.openOutputStream(uri) ?: throw IOException("cannot open $uri")
        out.use { exporter.export(id, it) }
    }

    /**
     * Writes the ZIP to cache/exports and hands a read-only content URI to the app the user picks. Older exports are
     * deleted first: the receiving app may still read the previous file after we return, so it is kept until the next one.
     */
    fun share() = runExport {
        val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, exporter.fileName(id))
        val result = file.outputStream().use { exporter.export(id, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        _shareIntents.send(Intent.createChooser(send, "Share ${file.name}"))
        result
    }

    private fun runExport(block: suspend () -> ExportResult) {
        if (_exporting.value) return
        _exporting.value = true
        _message.value = "Exporting…"
        viewModelScope.launch {
            _message.value = try {
                val r = withContext(Dispatchers.IO) { block() }
                "Exported ${r.counts.measurements} ticks, ${r.counts.cells} cells, ${r.counts.events} events: ${r.fileName}"
            } catch (e: IllegalArgumentException) {
                e.message
            } catch (e: IOException) {
                Log.w(TAG, "export failed", e)
                "Export failed: ${e.message}"
            } catch (e: SecurityException) {
                Log.w(TAG, "export failed", e)
                "Export failed: ${e.message}"
            } finally {
                _exporting.value = false
            }
        }
    }

    private companion object {
        const val TAG = "NetLogger"
        const val EXPORT_DIR = "exports"
    }
}
