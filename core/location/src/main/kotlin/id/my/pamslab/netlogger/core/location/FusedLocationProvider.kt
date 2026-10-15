package id.my.pamslab.netlogger.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import id.my.pamslab.netlogger.core.model.location.LocationFix
import id.my.pamslab.netlogger.core.model.location.LocationState
import id.my.pamslab.netlogger.core.model.location.LocationStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FusedLocationProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : LocationProvider {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val updates: Flow<LocationState> = requestUpdates()
        .shareIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), replay = 1)

    private fun requestUpdates(): Flow<LocationState> = callbackFlow {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            trySend(LocationState(LocationStatus.NO_PERMISSION))
            awaitClose()
            return@callbackFlow
        }
        val playServices = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        if (playServices != ConnectionResult.SUCCESS) {
            trySend(LocationState(LocationStatus.UNAVAILABLE, error = "Play services status $playServices"))
            awaitClose()
            return@callbackFlow
        }

        var last: LocationFix? = null
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    last = location.toFix()
                    trySend(LocationState(LocationStatus.OK, last))
                }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                if (!availability.isLocationAvailable) {
                    trySend(LocationState(if (locationEnabled()) LocationStatus.WAITING else LocationStatus.DISABLED, last))
                }
            }
        }

        trySend(LocationState(if (locationEnabled()) LocationStatus.WAITING else LocationStatus.DISABLED))
        val client = LocationServices.getFusedLocationProviderClient(context)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS)
            .setMinUpdateIntervalMillis(MIN_INTERVAL_MS)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            requestLocationUpdates(client, request, callback)
        } catch (e: SecurityException) {
            Log.w(TAG, "location permission revoked", e)
            trySend(LocationState(LocationStatus.NO_PERMISSION))
        } catch (e: RuntimeException) {
            Log.w(TAG, "location request failed", e)
            trySend(LocationState(LocationStatus.ERROR, error = e.toString()))
        }
        awaitClose { client.removeLocationUpdates(callback) }
    }

    // Permission is checked at the top of requestUpdates(); a later revocation surfaces as SecurityException.
    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates(
        client: com.google.android.gms.location.FusedLocationProviderClient,
        request: LocationRequest,
        callback: LocationCallback,
    ) {
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            .addOnFailureListener { Log.w(TAG, "requestLocationUpdates failed", it) }
    }

    private fun locationEnabled(): Boolean =
        context.getSystemService(LocationManager::class.java)?.isLocationEnabled ?: false

    private fun Location.toFix() = LocationFix(
        latitude = latitude,
        longitude = longitude,
        altitudeM = if (hasAltitude()) altitude else null,
        speedMps = if (hasSpeed()) speed else null,
        bearingDeg = if (hasBearing()) bearing else null,
        accuracyM = if (hasAccuracy()) accuracy else null,
        elapsedRealtimeNs = elapsedRealtimeNanos,
        wallTimeMs = time,
        provider = provider,
    )

    private companion object {
        const val TAG = "NetLogger"
        const val INTERVAL_MS = 1_000L
        const val MIN_INTERVAL_MS = 500L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
