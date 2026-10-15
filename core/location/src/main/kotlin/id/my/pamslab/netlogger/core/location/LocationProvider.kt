package id.my.pamslab.netlogger.core.location

import id.my.pamslab.netlogger.core.model.location.LocationState
import kotlinx.coroutines.flow.Flow

/**
 * The app's single location request stream (~1 Hz, high accuracy). All collectors share one underlying request,
 * which stops a few seconds after the last collector leaves.
 */
interface LocationProvider {
    val updates: Flow<LocationState>
}
