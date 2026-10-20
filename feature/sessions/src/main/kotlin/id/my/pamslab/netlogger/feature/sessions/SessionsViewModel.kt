package id.my.pamslab.netlogger.feature.sessions

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import id.my.pamslab.netlogger.core.data.CaptureFileStore
import id.my.pamslab.netlogger.core.data.SessionRepository
import id.my.pamslab.netlogger.core.data.db.NetLoggerDatabase
import id.my.pamslab.netlogger.core.diagnostics.CaughtErrors
import id.my.pamslab.netlogger.core.export.SessionExporter
import id.my.pamslab.netlogger.core.export.SizeEstimate
import id.my.pamslab.netlogger.core.model.session.Scenario
import id.my.pamslab.netlogger.core.model.session.SessionSummary
import id.my.pamslab.netlogger.feature.sessions.charts.ChartData
import id.my.pamslab.netlogger.feature.sessions.charts.ChartLoader
import id.my.pamslab.netlogger.feature.sessions.navigation.SessionDetailRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SessionsViewModel @Inject constructor(
    repository: SessionRepository,
    captureFiles: CaptureFileStore,
    @ApplicationContext context: Context,
) : ViewModel() {
    /** null until the first database read. */
    val sessions: StateFlow<List<SessionSummary>?> = repository.observeSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Estimated sizes per session id, from the row counts and the capture files on disk. */
    val sizes: StateFlow<Map<Long, SizeEstimate>> = repository.observeSessions()
        .map { list ->
            list.associate { s ->
                s.id to SizeEstimate.of(s.measurements, s.cells, s.warnings, captureFiles.existing(s.uuid).sumOf { it.length() })
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** All sessions: the database files' real size, the capture files, and the summed export estimate. */
    val totals: StateFlow<StorageTotals?> = sizes
        .map { sizes ->
            val db = context.getDatabasePath(NetLoggerDatabase.NAME)
            val dbBytes = listOf(db, File("${db.path}-wal"), File("${db.path}-shm")).sumOf { if (it.isFile) it.length() else 0L }
            StorageTotals(dbBytes, sizes.values.sumOf { it.captureBytes }, sizes.values.sumOf { it.exportBytes })
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/** [databaseFileBytes]: the database file with its WAL, as on disk (exact; the per-session values are estimates). */
data class StorageTotals(val databaseFileBytes: Long, val captureBytes: Long, val exportBytes: Long)

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
    private val exportRunner: ExportRunner,
    private val chartLoader: ChartLoader,
    private val captureFiles: CaptureFileStore,
) : ViewModel() {

    val id: Long = savedStateHandle.toRoute<SessionDetailRoute>().id

    val session: StateFlow<SessionSummary?> = repository.observeSession(id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Estimated size on the phone and of the export (row counts + capture files on disk). */
    val size: StateFlow<SizeEstimate?> = session
        .map { s -> s?.let { SizeEstimate.of(it.measurements, it.cells, it.warnings, captureFiles.existing(it.uuid).sumOf { f -> f.length() }) } }
        .flowOn(Dispatchers.IO)
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
                CaughtErrors.caught("charts not loaded", e)
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
                // The session's traffic capture (TCP mode) goes with it.
                uuid?.let { withContext(Dispatchers.IO) { captureFiles.delete(it) } }
                onDeleted()
            } catch (e: IllegalArgumentException) {
                _message.value = e.message
            }
        }
    }

    /** This session's export, running or finished (it runs in [ExportRunner], so it survives leaving the screen). */
    val export: StateFlow<ExportJob?> = exportRunner.job
        .map { it?.takeIf { job -> job.sessionId == id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Some export (this or another session) is running; only one at a time. */
    val exporting: StateFlow<Boolean> = exportRunner.job
        .map { it?.running == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Suggested file name for "Save to…"; null on failure. */
    suspend fun exportFileName(): String? = try {
        exporter.fileName(id)
    } catch (e: IllegalArgumentException) {
        _message.value = e.message
        null
    }

    /** Writes the ZIP to a document the user picked (Storage Access Framework). */
    fun saveTo(uri: Uri) {
        if (!exportRunner.save(id, uri)) _message.value = "Another export is still running."
    }

    fun share() {
        if (!exportRunner.share(id)) _message.value = "Another export is still running."
    }

    /** The screen started the share chooser of a finished export. */
    fun shareStarted() = exportRunner.shareStarted()

    /** The screen showed a finished export's result. */
    fun dismissExport() = exportRunner.dismiss(id)

    private companion object {
        const val TAG = "NetLogger"
    }
}
