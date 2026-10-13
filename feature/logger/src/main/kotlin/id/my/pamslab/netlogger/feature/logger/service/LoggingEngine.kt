package id.my.pamslab.netlogger.feature.logger.service

import id.my.pamslab.netlogger.core.device.DeviceContextSource
import id.my.pamslab.netlogger.core.location.LocationProvider
import id.my.pamslab.netlogger.core.model.Clock
import id.my.pamslab.netlogger.core.model.device.DeviceContext
import id.my.pamslab.netlogger.core.model.device.DeviceInfo
import id.my.pamslab.netlogger.core.model.location.LocationFix
import id.my.pamslab.netlogger.core.model.location.LocationState
import id.my.pamslab.netlogger.core.model.location.LocationStatus
import id.my.pamslab.netlogger.core.model.logging.Tick
import id.my.pamslab.netlogger.core.model.logging.TickStats
import id.my.pamslab.netlogger.core.model.logging.UserMarker
import id.my.pamslab.netlogger.core.model.radio.CellSource
import id.my.pamslab.netlogger.core.model.radio.RadioSnapshot
import id.my.pamslab.netlogger.core.model.raw.CellInfoOrigin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/** Radio snapshots at a fixed interval; bound to RadioSampler, faked in tests. */
fun interface RadioSamples {
    fun samples(intervalMs: Long): Flow<RadioSnapshot>
}

sealed interface LoggingState {
    /** [lastSession] holds the stats of the session that just ended, if any. */
    data class Idle(val lastSession: TickStats? = null) : LoggingState

    data class Running(
        val stats: TickStats,
        val latest: Tick?,
        val markers: List<UserMarker>,
        val deviceInfo: DeviceInfo,
    ) : LoggingState
}

/**
 * Builds one [Tick] per radio snapshot: the snapshot plus the GPS fix and device context at that moment. Runs inside
 * LoggingService; Room persistence plugs in here in milestone 4. Each fix is matched to the tick by time, so a fix
 * that arrives during the cell info request is not used for the tick that started before it.
 */
@Singleton
class LoggingEngine @Inject constructor(
    private val radio: RadioSamples,
    private val location: LocationProvider,
    private val device: DeviceContextSource,
    private val clock: Clock,
) {
    private val _state = MutableStateFlow<LoggingState>(LoggingState.Idle())
    val state: StateFlow<LoggingState> = _state.asStateFlow()

    private val locationState = AtomicReference(LocationState(LocationStatus.WAITING))
    private val recentFixes = AtomicReference<List<LocationFix>>(emptyList())

    /** Runs until the calling coroutine is cancelled. */
    suspend fun run(intervalMs: Long) {
        require(intervalMs > 0)
        val startNs = clock.elapsedRealtimeNs()
        locationState.set(LocationState(LocationStatus.WAITING))
        recentFixes.set(emptyList())
        _state.value = LoggingState.Running(TickStats(startNs, intervalMs, 0, 0), null, emptyList(), device.deviceInfo())
        try {
            coroutineScope {
                launch {
                    location.updates.collect { update ->
                        locationState.set(update)
                        val fix = update.fix ?: return@collect
                        recentFixes.update { (it + fix).distinctBy(LocationFix::elapsedRealtimeNs).takeLast(FIX_HISTORY) }
                    }
                }
                var seq = 0L
                val window = TickWindow()
                radio.samples(intervalMs).collect { snapshot ->
                    val tick = buildTick(seq, snapshot, locationState.get(), recentFixes.get(), device.snapshot())
                    seq++
                    window.add(tick)
                    val expected = (snapshot.elapsedRealtimeNs - startNs) / (intervalMs * NS_PER_MS) + 1
                    _state.update { s ->
                        if (s !is LoggingState.Running) s
                        else s.copy(stats = s.stats.copy(recorded = seq, expected = maxOf(expected, seq)), latest = tick)
                    }
                    if (seq % SUMMARY_EVERY_TICKS == 0L) {
                        log(window.summary(seq, maxOf(expected, seq)))
                        window.clear()
                    }
                }
            }
        } finally {
            _state.update { s -> LoggingState.Idle((s as? LoggingState.Running)?.stats) }
        }
    }

    /** Records a marker at the current time and last known position; null when not logging. */
    fun addMarker(): UserMarker? {
        val fix = recentFixes.get().lastOrNull()
        val marker = UserMarker(clock.wallTimeMs(), clock.elapsedRealtimeNs(), fix?.latitude, fix?.longitude)
        var added = false
        _state.update { s ->
            if (s is LoggingState.Running) {
                added = true
                s.copy(markers = s.markers + marker)
            } else {
                s
            }
        }
        return marker.takeIf { added }
    }

    private fun <T> AtomicReference<T>.update(transform: (T) -> T) {
        while (true) {
            val current = get()
            if (compareAndSet(current, transform(current))) return
        }
    }

    /** Per-minute counters for the log summary, used to check behavior with the screen off. */
    private class TickWindow {
        private val origins = mutableMapOf<CellInfoOrigin, Int>()
        private var maxCellAgeMs = 0L
        private var maxLocationAgeMs = 0L
        private var screenOff = 0
        private var nrTicks = 0

        fun add(tick: Tick) {
            origins.merge(tick.radio.cellInfoOrigin, 1, Int::plus)
            tick.radio.cells.filter { it.source == CellSource.CELL_INFO && it.isServing }
                .mapNotNull { it.cellInfoAgeMs }.maxOrNull()?.let { maxCellAgeMs = maxOf(maxCellAgeMs, it) }
            tick.locationAgeMs?.let { maxLocationAgeMs = maxOf(maxLocationAgeMs, it) }
            if (tick.device.screenOn == false) screenOff++
            if (tick.radio.nsaInputs.nrSignalStrength || tick.radio.nsaInputs.nrServingCellInfo) nrTicks++
        }

        fun summary(recorded: Long, expected: Long) =
            "ticks $recorded/$expected · cellInfo $origins · maxServingCellAge ${maxCellAgeMs}ms · " +
                "maxGpsAge ${maxLocationAgeMs}ms · screenOff $screenOff · nr $nrTicks"

        fun clear() {
            origins.clear()
            maxCellAgeMs = 0
            maxLocationAgeMs = 0
            screenOff = 0
            nrTicks = 0
        }
    }

    private fun log(message: String) {
        try {
            android.util.Log.i(TAG, message)
        } catch (_: RuntimeException) {
            // android.util.Log is not available in JVM unit tests.
        }
    }

    internal companion object {
        const val TAG = "NetLogger"
        const val SUMMARY_EVERY_TICKS = 60L
        const val NS_PER_MS = 1_000_000L
        const val FIX_HISTORY = 5

        /** Pure: newest fix taken at or before the tick; the oldest one if all are newer. */
        fun buildTick(
            seq: Long,
            radio: RadioSnapshot,
            state: LocationState,
            fixes: List<LocationFix>,
            device: DeviceContext,
        ): Tick {
            val fix = fixes.lastOrNull { it.elapsedRealtimeNs <= radio.elapsedRealtimeNs } ?: fixes.firstOrNull()
            return Tick(
                seq = seq,
                radio = radio,
                location = fix,
                locationStatus = state.status,
                locationAgeMs = fix?.let { (radio.elapsedRealtimeNs - it.elapsedRealtimeNs) / NS_PER_MS },
                device = device,
            )
        }
    }
}
