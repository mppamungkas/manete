package id.my.pamslab.netlogger.feature.indoor

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import id.my.pamslab.netlogger.core.data.FloorPlanRepository
import id.my.pamslab.netlogger.core.data.FloorPlanSummary
import id.my.pamslab.netlogger.core.data.PlanImageException
import id.my.pamslab.netlogger.core.data.PlanImportError
import id.my.pamslab.netlogger.core.data.PlanImportResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The import of a picked file: confirm name, floor and PDF page, then import. */
sealed interface ImportState {
    data object Idle : ImportState

    data object Inspecting : ImportState

    /** [pdfPages] null for an image. */
    data class Confirm(val uri: Uri, val name: String, val pdfPages: Int?) : ImportState

    data object Importing : ImportState

    data class Failed(val reason: PlanImportError) : ImportState
}

@HiltViewModel
class FloorPlansViewModel @Inject constructor(private val repository: FloorPlanRepository) : ViewModel() {

    /** Null until the first load. */
    val plans: StateFlow<List<FloorPlanSummary>?> =
        repository.plans.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _import = MutableStateFlow<ImportState>(ImportState.Idle)
    val import: StateFlow<ImportState> = _import.asStateFlow()

    /** Shown once after a refused delete (the plan is used by a session). */
    private val _deleteRefused = MutableStateFlow<String?>(null)
    val deleteRefused: StateFlow<String?> = _deleteRefused.asStateFlow()

    fun onPicked(uri: Uri?) {
        if (uri == null) return
        _import.value = ImportState.Inspecting
        viewModelScope.launch {
            _import.value = repository.inspect(uri).fold(
                onSuccess = { ImportState.Confirm(uri, defaultName(it.displayName), it.pdfPages) },
                onFailure = { ImportState.Failed((it as? PlanImageException)?.reason ?: PlanImportError.UNREADABLE) },
            )
        }
    }

    /** [pdfPage] is 1-based, as the user sees it. */
    fun confirmImport(name: String, floor: String, pdfPage: Int) {
        val confirm = _import.value as? ImportState.Confirm ?: return
        _import.value = ImportState.Importing
        viewModelScope.launch {
            val result = repository.import(confirm.uri, (pdfPage - 1).coerceAtLeast(0), name, floor)
            _import.value = when (result) {
                is PlanImportResult.Imported -> ImportState.Idle
                is PlanImportResult.Failed -> ImportState.Failed(result.reason)
            }
        }
    }

    fun dismissImport() {
        _import.value = ImportState.Idle
    }

    fun rename(id: Long, name: String, floor: String) = viewModelScope.launch { repository.rename(id, name, floor) }

    fun delete(plan: FloorPlanSummary) = viewModelScope.launch {
        if (!repository.delete(plan.plan.id)) _deleteRefused.value = plan.plan.name
    }

    fun dismissDeleteRefused() {
        _deleteRefused.value = null
    }

    internal companion object {
        /** "Building C 2F.pdf" -> "Building C 2F"; the user can change it before importing. */
        fun defaultName(displayName: String?): String =
            displayName?.substringBeforeLast('.')?.trim()?.takeIf { it.isNotEmpty() } ?: ""
    }
}
