package id.my.pamslab.netlogger.permissions

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class PermissionViewModel @Inject constructor(
    private val checker: PermissionChecker,
) : ViewModel() {

    // Not persisted: after process death the next request is auto-denied by the system and re-marked here.
    private val permanentlyDenied = mutableSetOf<String>()

    private val _state = MutableStateFlow(evaluate())
    val state: StateFlow<PermissionUiState> = _state.asStateFlow()

    /** Call on resume: the user may have changed permissions in system settings. */
    fun refresh() {
        permanentlyDenied.removeAll { checker.isGranted(it) }
        _state.value = evaluate()
    }

    fun permissionsToRequest(): List<String> =
        permissionsToRequest(checker.sdkInt, checker::isGranted, permanentlyDenied)

    /**
     * @param shouldShowRationale Activity.shouldShowRequestPermissionRationale. After a denial it is
     * false only when the system will no longer show the dialog.
     */
    fun onPermissionResult(results: Map<String, Boolean>, shouldShowRationale: (String) -> Boolean) {
        results.forEach { (permission, granted) ->
            if (!granted && !shouldShowRationale(permission)) permanentlyDenied += permission
        }
        refresh()
    }

    private fun evaluate(): PermissionUiState =
        evaluatePermissions(checker.sdkInt, checker::isGranted, permanentlyDenied)
}
