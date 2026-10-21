package id.my.pamslab.netlogger.core.data

import id.my.pamslab.netlogger.core.model.indoor.Waypoint
import id.my.pamslab.netlogger.core.model.logging.SessionEvent
import id.my.pamslab.netlogger.core.model.logging.Tick
import id.my.pamslab.netlogger.core.model.logging.UserMarker
import id.my.pamslab.netlogger.core.model.session.EndReason
import id.my.pamslab.netlogger.core.model.session.Scenario
import id.my.pamslab.netlogger.core.model.session.SessionStart
import id.my.pamslab.netlogger.core.model.session.SessionSummary
import id.my.pamslab.netlogger.core.model.speedtest.ThroughputResult
import kotlinx.coroutines.flow.Flow

interface SessionRepository {
    /** Closes abandoned sessions, then opens a new one. Only one session records at a time. */
    suspend fun startSession(start: SessionStart): SessionRecorder

    /**
     * Closes sessions left open by a killed process: endedAt = last measurement (or start), endReason
     * RECOVERED_AFTER_KILL. The session currently recording in this process is never touched. Returns the count.
     */
    suspend fun closeAbandonedSessions(): Int

    fun observeSessions(): Flow<List<SessionSummary>>
    fun observeSession(id: Long): Flow<SessionSummary?>
    suspend fun updateDetails(id: Long, label: String?, notes: String?, scenario: Scenario?)
    suspend fun delete(id: Long)
    suspend fun latestScenario(): Scenario?

    /** An event from outside the tick loop (traffic capture), stored in [sessionId]. */
    suspend fun addEvent(sessionId: Long, event: SessionEvent)

    /** Links a traffic capture file to the session (session.captureFile). */
    suspend fun setCaptureFile(sessionId: Long, captureFile: String?)
}

/** Writes one session. [record] is called from the tick loop only; [addMarker] may come from anywhere. */
interface SessionRecorder {
    val sessionId: Long

    /** Measurement, cells and warnings of one tick in one transaction. Returns the write time in ms. */
    suspend fun record(tick: Tick): Long

    suspend fun addMarker(marker: UserMarker)

    suspend fun addEvent(event: SessionEvent)

    suspend fun addThroughput(result: ThroughputResult)

    /** A tap on a floor plan (decision 006). Returns its id, for [removeWaypoint]. */
    suspend fun addWaypoint(waypoint: Waypoint): Long

    /** Undo of tap [id]: it stays stored, marked removed. */
    suspend fun removeWaypoint(id: Long)

    suspend fun close(reason: EndReason, endedAtMs: Long, endedElapsedNs: Long)
}
