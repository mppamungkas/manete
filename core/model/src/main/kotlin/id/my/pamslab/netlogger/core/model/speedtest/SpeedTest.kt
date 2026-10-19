package id.my.pamslab.netlogger.core.model.speedtest

enum class ThroughputType { PING, DOWNLOAD, UPLOAD }

/**
 * Which server a test runs against. [PRIMARY]: the user's own server from Settings (the default, also for the
 * notification action). [PUBLIC]: [SpeedTestConfig.cloudflare], a secondary target the user opts in to.
 */
enum class SpeedTestTarget { PRIMARY, PUBLIC }

/** What started a test: the user (Live or the notification) or the periodic schedule ([PeriodicSpeedTest]). */
enum class SpeedTestTrigger { MANUAL, PERIODIC }

/**
 * Server endpoints. Works with a LibreSpeed backend ([libreSpeed]) or any web server: [pingUrl] answers small GETs,
 * [downloadUrl] serves a large body, [uploadUrl] accepts a POST and discards it.
 */
data class SpeedTestConfig(
    val pingUrl: String,
    val downloadUrl: String,
    val uploadUrl: String,
    /** HTTP requests for the ping; the first one only opens the connection and is not counted. */
    val pingCount: Int = 10,
    /** Duration of each throughput phase. */
    val durationMs: Long = 10_000,
    /** Parallel connections for download and upload. */
    val streams: Int = 4,
    /** Stops a download early after this many bytes (all streams), to cap mobile data. */
    val maxDownloadBytes: Long = 200L * 1024 * 1024,
    /** Stops an upload early after this many bytes (all streams). */
    val maxUploadBytes: Long = 50L * 1024 * 1024,
    /** Phases to run, always in the order ping, download, upload; a periodic test may leave some out. */
    val phases: Set<ThroughputType> = ThroughputType.entries.toSet(),
) {
    val isComplete: Boolean get() = listOf(pingUrl, downloadUrl, uploadUrl).all { it.startsWith("http://") || it.startsWith("https://") }

    companion object {
        /** LibreSpeed backend endpoints under [baseUrl] (e.g. https://speed.example.org/). */
        fun libreSpeed(baseUrl: String): SpeedTestConfig {
            val base = baseUrl.trim().trimEnd('/')
            return SpeedTestConfig(
                pingUrl = "$base/backend/empty.php",
                downloadUrl = "$base/backend/garbage.php?ckSize=100",
                uploadUrl = "$base/backend/empty.php",
            )
        }

        /**
         * Cloudflare's public speed test endpoints, the secondary target ([SpeedTestTarget.PUBLIC]). Not our server:
         * a far-away or busy edge lowers the result, requests above ~100 MB answer HTTP 403 (hence 25 MB per
         * request, repeated by each stream), and many large downloads in a row answer HTTP 429 (rate limit).
         */
        fun cloudflare(): SpeedTestConfig = SpeedTestConfig(
            pingUrl = "$CLOUDFLARE/__down?bytes=0",
            downloadUrl = "$CLOUDFLARE/__down?bytes=25000000",
            uploadUrl = "$CLOUDFLARE/__up",
        )

        const val CLOUDFLARE = "https://speed.cloudflare.com"

    }
}

/** One phase of a speed test; a failed phase has [error] set and keeps what was measured until then. */
data class ThroughputResult(
    val type: ThroughputType,
    val startedAtMs: Long,
    val startedElapsedNs: Long,
    val serverUrl: String,
    val durationMs: Long?,
    val bytes: Long?,
    val mbps: Double?,
    val streams: Int?,
    val rttMinMs: Double?,
    val rttAvgMs: Double?,
    val rttMaxMs: Double?,
    /** Mean absolute difference between consecutive RTTs. */
    val jitterMs: Double?,
    val lossPct: Double?,
    /** Phase 3: traffic capture was running (adds overhead). */
    val captureActive: Boolean = false,
    val error: String? = null,
    /** Where the server answered from, if it says: Cloudflare's location code from CF-RAY (e.g. "SIN"); null otherwise. */
    val serverLocation: String? = null,
    /** Schema v4: started by the user or by the periodic schedule. */
    val trigger: SpeedTestTrigger = SpeedTestTrigger.MANUAL,
)

/**
 * Speed test repeated during a session (Settings › Speed test), for throughput-vs-radio studies. Runs against the
 * private server only: Cloudflare answers HTTP 429 to many tests in a row. [intervalMin] 0 = off. The defaults are the
 * manual test's, so periodic and manual results compare; [phases], [durationMs] and the caps are optional limits.
 */
data class PeriodicSpeedTest(
    val intervalMin: Int = 0,
    val phases: Set<ThroughputType> = ThroughputType.entries.toSet(),
    val durationMs: Long = DEFAULT.durationMs,
    val maxDownloadBytes: Long = DEFAULT.maxDownloadBytes,
    val maxUploadBytes: Long = DEFAULT.maxUploadBytes,
) {
    val enabled: Boolean get() = intervalMin > 0 && phases.isNotEmpty()

    /** True when nothing but the interval differs from the manual test. */
    val isDefaultTest: Boolean
        get() = copy(intervalMin = 0) == PeriodicSpeedTest()

    /** [config] (the private server) with this schedule's phases, duration and caps. */
    fun apply(config: SpeedTestConfig) = config.copy(
        phases = phases, durationMs = durationMs, maxDownloadBytes = maxDownloadBytes, maxUploadBytes = maxUploadBytes,
    )

    /** Most mobile data one test can use: the caps of the phases it runs (a ping is a few kB). */
    val maxBytesPerTest: Long
        get() = (if (ThroughputType.DOWNLOAD in phases) maxDownloadBytes else 0) +
            (if (ThroughputType.UPLOAD in phases) maxUploadBytes else 0)

    /** Most mobile data per hour of recording; 0 when off. */
    val maxBytesPerHour: Long get() = if (enabled) maxBytesPerTest * (60 / intervalMin) else 0

    companion object {
        private val DEFAULT = SpeedTestConfig("", "", "")
        const val MB = 1024L * 1024
        val INTERVALS_MIN = listOf(0, 5, 10, 15, 30)
        val DURATIONS_MS = listOf(5_000L, 10_000L)
        val DOWNLOAD_CAPS = listOf(25 * MB, 50 * MB, 100 * MB, 200 * MB)
        val UPLOAD_CAPS = listOf(10 * MB, 25 * MB, 50 * MB)
    }
}

/** Progress for the UI. */
data class SpeedTestProgress(val phase: ThroughputType, val mbps: Double? = null, val rttMs: Double? = null)

/** Runs ping, download and upload in that order against [SpeedTestConfig]; implemented in feature:speedtest. */
interface SpeedTestRunner {
    /**
     * [onResult] gets each phase as soon as it is done, so a test stopped half-way keeps the finished phases. It comes
     * before [onProgress], so a trailing lambda stays the progress callback.
     */
    suspend fun run(
        config: SpeedTestConfig,
        onResult: (ThroughputResult) -> Unit = {},
        onProgress: (SpeedTestProgress) -> Unit,
    ): List<ThroughputResult>
}

/**
 * Cloudflare's location code from a CF-RAY header ("a4bb84683e5ffd90-SIN" -> "SIN": the edge's IATA airport code);
 * null for anything else.
 */
fun cloudflareLocation(cfRay: String?): String? =
    cfRay?.substringAfterLast('-', "")?.trim()?.takeIf { it.length == 3 && it.all(Char::isUpperCase) }
