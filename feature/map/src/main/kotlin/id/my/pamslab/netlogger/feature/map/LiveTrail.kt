package id.my.pamslab.netlogger.feature.map

import id.my.pamslab.netlogger.core.data.unsavedRows
import id.my.pamslab.netlogger.core.export.FlatRow
import id.my.pamslab.netlogger.core.location.LocationProvider
import id.my.pamslab.netlogger.core.model.device.BatterySample
import id.my.pamslab.netlogger.core.model.device.DeviceContext
import id.my.pamslab.netlogger.core.model.device.TrafficSample
import id.my.pamslab.netlogger.core.model.location.LocationState
import id.my.pamslab.netlogger.core.model.location.LocationStatus
import id.my.pamslab.netlogger.core.model.logging.Tick
import id.my.pamslab.netlogger.core.model.radio.RadioSnapshot
import id.my.pamslab.netlogger.core.telephony.RadioSampler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * The Map's live preview while no session records: one radio sample per second with the latest location fix,
 * as a growing track kept in memory only (nothing is stored). Runs only while collected, i.e. while the Map shows
 * "Live session" and is visible; the location stream is the app's shared one (LocationProvider).
 */
class LiveTrail @Inject constructor(
    private val sampler: RadioSampler,
    private val location: LocationProvider,
) {
    /** Emits the whole trail after every sample, at most [MAX_POINTS] points with a fix (oldest dropped). */
    fun track(): Flow<LiveTrack> = channelFlow {
        val loc = location.updates.stateIn(this, SharingStarted.Eagerly, LocationState(LocationStatus.WAITING))
        val points = ArrayDeque<TrackPoint>()
        var seq = 0L
        sampler.samples(INTERVAL_MS).collect { radio ->
            val state = loc.value
            livePoint(seq++, radio, state)?.let {
                points.addLast(it)
                if (points.size > MAX_POINTS) points.removeFirst()
            }
            send(LiveTrack(Track(LIVE_ID, points.toList(), emptyList(), seq), state.status))
        }
    }

    companion object {
        /** Track.sessionId of the live preview (real sessions start at 1). */
        const val LIVE_ID = -1L
        const val INTERVAL_MS = 1_000L

        /** One hour at 1 s. */
        const val MAX_POINTS = 3_600

        /** The point a recorded tick would give (same flat.csv rules); null without a fix. Pure. */
        fun livePoint(seq: Long, radio: RadioSnapshot, state: LocationState): TrackPoint? {
            val fix = state.fix
            val tick = Tick(
                seq = seq,
                radio = radio,
                location = fix,
                locationStatus = state.status,
                locationAgeMs = fix?.let { (radio.elapsedRealtimeNs - it.elapsedRealtimeNs) / 1_000_000 },
                device = NO_DEVICE,
            )
            val (m, cells) = unsavedRows(tick)
            return TrackPoint.of(FlatRow.of(m, cells))
        }

        /** The preview does not read battery, traffic or thermal state. */
        private val NO_DEVICE = DeviceContext(
            BatterySample(null, null, null, null, null), TrafficSample(null, null, null, null), null, null,
        )
    }
}

/** The live trail and the location status (no permission, waiting for a fix, …). */
data class LiveTrack(val track: Track, val locationStatus: LocationStatus)
