package id.my.pamslab.netlogger.core.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A floor plan with how many sessions and (active) waypoints use it. */
data class FloorPlanUsageRow(
    @Embedded val plan: FloorPlanEntity,
    val sessions: Int,
    val waypoints: Int,
)

/** A plan image used by a session. */
data class SessionPlanFileRow(val sessionId: Long, val imageFile: String)

/** Floor plans for indoor tests (decision 006). */
@Dao
interface FloorPlanDao {
    @Query(
        "SELECT p.*, " +
            "(SELECT COUNT(DISTINCT w.sessionId) FROM plan_waypoint w WHERE w.planId = p.id) AS sessions, " +
            "(SELECT COUNT(*) FROM plan_waypoint w WHERE w.planId = p.id AND w.removed = 0) AS waypoints " +
            "FROM floor_plan p ORDER BY p.createdAt DESC, p.id DESC",
    )
    fun observeWithUsage(): Flow<List<FloorPlanUsageRow>>

    @Query("SELECT * FROM floor_plan WHERE id = :id")
    suspend fun get(id: Long): FloorPlanEntity?

    /** Which plan images each session uses (for the export size estimate). */
    @Query("SELECT DISTINCT w.sessionId AS sessionId, p.imageFile AS imageFile FROM plan_waypoint w JOIN floor_plan p ON p.id = w.planId")
    fun observeSessionPlanFiles(): Flow<List<SessionPlanFileRow>>

    @Insert
    suspend fun insert(plan: FloorPlanEntity): Long

    @Query("UPDATE floor_plan SET name = :name, floor = :floor WHERE id = :id")
    suspend fun rename(id: Long, name: String, floor: String?)

    @Query("UPDATE floor_plan SET metresPerPx = :metresPerPx WHERE id = :id")
    suspend fun setScale(id: Long, metresPerPx: Double?)

    /** Every waypoint, removed ones too: any of them keeps the plan (RESTRICT). */
    @Query("SELECT COUNT(*) FROM plan_waypoint WHERE planId = :id")
    suspend fun waypointCount(id: Long): Int

    @Query("SELECT COUNT(DISTINCT sessionId) FROM plan_waypoint WHERE planId = :id")
    suspend fun sessionCount(id: Long): Int

    @Query("SELECT COUNT(*) FROM plan_waypoint WHERE planId = :id AND removed = 0")
    suspend fun activeWaypointCount(id: Long): Int

    @Query("DELETE FROM floor_plan WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Insert
    suspend fun insertWaypoint(waypoint: PlanWaypointEntity): Long

    /** Undo: the tap stays stored, marked removed, so the export can show it. */
    @Query("UPDATE plan_waypoint SET removed = 1 WHERE id = :id")
    suspend fun markWaypointRemoved(id: Long)

    /** A session's taps in time order, removed ones too; updates while the session records. */
    @Query("SELECT * FROM plan_waypoint WHERE sessionId = :sessionId ORDER BY elapsedRealtimeNs, id")
    fun observeWaypoints(sessionId: Long): Flow<List<PlanWaypointEntity>>

    /** A session's taps in time order, removed ones too. */
    @Query("SELECT * FROM plan_waypoint WHERE sessionId = :sessionId ORDER BY elapsedRealtimeNs, id")
    suspend fun waypoints(sessionId: Long): List<PlanWaypointEntity>
}
