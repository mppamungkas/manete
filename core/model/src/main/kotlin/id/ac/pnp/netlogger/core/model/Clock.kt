package id.ac.pnp.netlogger.core.model

/** Both time bases stored on every row. `elapsedRealtimeNs` is the sync key and survives clock changes. */
interface Clock {
    fun wallTimeMs(): Long
    fun elapsedRealtimeNs(): Long
}
