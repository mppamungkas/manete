package id.my.pamslab.netlogger.feature.speedtest

import id.my.pamslab.netlogger.core.model.Clock
import id.my.pamslab.netlogger.core.model.speedtest.SpeedTestConfig
import id.my.pamslab.netlogger.core.model.speedtest.cloudflareLocation
import id.my.pamslab.netlogger.core.model.speedtest.SpeedTestProgress
import id.my.pamslab.netlogger.core.model.speedtest.SpeedTestRunner
import id.my.pamslab.netlogger.core.model.speedtest.ThroughputResult
import id.my.pamslab.netlogger.core.model.speedtest.ThroughputType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlin.math.abs
import kotlin.random.Random

/**
 * Ping (HTTP round trips), then download and upload ([SpeedTestConfig.phases]; all three by default) with [SpeedTestConfig.streams] parallel connections for
 * [SpeedTestConfig.durationMs] each. Every request carries a unique query parameter so no cache answers it.
 *
 * - Ping RTT: request sent to response body read, on a kept-alive connection; the first request opens the
 *   connection and is not counted. It includes the server's response time, unlike ICMP.
 * - Download: bytes read from the response bodies until the phase ends.
 * - Upload: bytes of the pieces the server confirmed (answered), timed to the last confirmation (since 2026-10-20;
 *   before, bytes handed to the socket, which the send buffers inflated).
 * - Mbps = bytes × 8 / phase time / 10^6, over the whole phase (TCP ramp-up included, no grace period).
 * - Redirects are never followed: a 3xx (e.g. an operator's captive portal when the data quota is used up) is an
 *   error naming the redirect host, not a measurement of the portal.
 */
class OkHttpSpeedTestRunner @Inject constructor(
    private val clock: Clock,
    private val network: NetworkGuard,
) : SpeedTestRunner {

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .cache(null)
        .build()

    override suspend fun run(
        config: SpeedTestConfig,
        onResult: (ThroughputResult) -> Unit,
        onProgress: (SpeedTestProgress) -> Unit,
    ): List<ThroughputResult> {
        val phases = ThroughputType.entries.filter { it in config.phases }
        network.problem()?.let { problem ->
            return phases.map { failed(it, url(config, it), problem).also(onResult) }
        }
        return withContext(Dispatchers.IO) {
            phases.map { phase ->
                when (phase) {
                    ThroughputType.PING -> ping(config, onProgress)
                    ThroughputType.DOWNLOAD -> download(config, onProgress)
                    ThroughputType.UPLOAD -> upload(config, onProgress)
                }.also(onResult)
            }
        }
    }

    private fun url(config: SpeedTestConfig, type: ThroughputType) = when (type) {
        ThroughputType.PING -> config.pingUrl
        ThroughputType.DOWNLOAD -> config.downloadUrl
        ThroughputType.UPLOAD -> config.uploadUrl
    }

    private fun failed(type: ThroughputType, url: String, error: String) = ThroughputResult(
        type, clock.wallTimeMs(), clock.elapsedRealtimeNs(), url, null, null, null, null, null, null, null, null, null,
        error = error,
    )

    /** [base] with a unique `nl` query parameter, or null when [base] is not an http(s) URL. */
    private fun uncached(base: String) = base.toHttpUrlOrNull()?.newBuilder()
        ?.addQueryParameter("nl", "${System.nanoTime()}-${Random.nextInt(1_000_000)}")
        ?.build()

    private suspend fun ping(config: SpeedTestConfig, onProgress: (SpeedTestProgress) -> Unit): ThroughputResult = coroutineScope {
        val startMs = clock.wallTimeMs()
        val startNs = clock.elapsedRealtimeNs()
        val t0 = System.nanoTime()
        val rtts = mutableListOf<Double>()
        var failures = 0
        var lastError: String? = null
        var location: String? = null
        repeat(config.pingCount + 1) { i ->
            ensureActive()
            val url = uncached(config.pingUrl) ?: return@coroutineScope failed(ThroughputType.PING, config.pingUrl, BAD_URL)
            val call = client.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build())
            call.timeout().timeout(PING_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val sent = System.nanoTime()
            try {
                call.execute().use { response ->
                    location = location ?: response.location()
                    response.body?.bytes()
                    if (!response.isSuccessful) throw HttpStatusException(response)
                }
                val rtt = (System.nanoTime() - sent) / 1e6
                if (i > 0) {
                    rtts += rtt
                    onProgress(SpeedTestProgress(ThroughputType.PING, rttMs = rtt))
                }
            } catch (e: IOException) {
                lastError = e.message ?: e.javaClass.simpleName
                if (i > 0) failures++ else return@coroutineScope failed(ThroughputType.PING, config.pingUrl, "connect: $lastError")
            }
            if (i < config.pingCount) delay(PING_GAP_MS)
        }
        val jitter = rtts.zipWithNext { a, b -> abs(b - a) }.takeIf { it.isNotEmpty() }?.average()
        ThroughputResult(
            ThroughputType.PING, startMs, startNs, config.pingUrl,
            durationMs = (System.nanoTime() - t0) / 1_000_000, bytes = null, mbps = null, streams = 1,
            rttMinMs = rtts.minOrNull(), rttAvgMs = rtts.takeIf { it.isNotEmpty() }?.average(), rttMaxMs = rtts.maxOrNull(),
            jitterMs = jitter, lossPct = failures * 100.0 / config.pingCount,
            error = if (rtts.isEmpty()) lastError ?: "no reply" else null,
            serverLocation = location,
        )
    }

    private suspend fun download(config: SpeedTestConfig, onProgress: (SpeedTestProgress) -> Unit) =
        transfer(ThroughputType.DOWNLOAD, config.downloadUrl, config, config.maxDownloadBytes, onProgress, confirmedOnly = false) { url, bytes, limit, location, _ ->
            val call = client.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build())
            call to {
                call.execute().use { response ->
                    response.location()?.let { location.compareAndSet(null, it) }
                    if (!response.isSuccessful) throw HttpStatusException(response)
                    val source = response.body?.source() ?: throw IOException("no body")
                    val buffer = ByteArray(CHUNK_BYTES)
                    while (true) {
                        val n = source.read(buffer)
                        if (n < 0) break
                        if (bytes.addAndGet(n.toLong()) >= limit) break
                    }
                }
                0L
            }
        }

    /**
     * Each request posts one piece of [pieceBytes]; it counts only once the server has answered, i.e. received all of
     * it. Bytes handed to the socket are not counted: the send buffers (2 MB per connection on the A56) and, during a
     * capture, the VPN forwarder take several MB at memory speed (a 10 MB upload "took" 0.5 s, 162 Mbps, 2026-10-20).
     */
    private suspend fun upload(config: SpeedTestConfig, onProgress: (SpeedTestProgress) -> Unit) =
        transfer(ThroughputType.UPLOAD, config.uploadUrl, config, config.maxUploadBytes, onProgress, confirmedOnly = true) { url, _, _, location, pieceBytes ->
            val body = object : RequestBody() {
                override fun contentType() = OCTET_STREAM
                override fun contentLength() = pieceBytes
                override fun writeTo(sink: BufferedSink) {
                    var left = pieceBytes
                    while (left > 0) {
                        val n = minOf(left, RANDOM_BLOCK.size.toLong()).toInt()
                        sink.write(RANDOM_BLOCK, 0, n)
                        left -= n
                    }
                }
            }
            val call = client.newCall(Request.Builder().url(url).post(body).build())
            call to {
                call.execute().use { response ->
                    response.location()?.let { location.compareAndSet(null, it) }
                    if (!response.isSuccessful) throw HttpStatusException(response)
                    response.body?.bytes()
                }
                pieceBytes
            }
        }

    /**
     * Runs [streams][SpeedTestConfig.streams] workers that repeat [request] until the phase time is up or [limit] bytes
     * are counted. All calls still open at the end are cancelled.
     *
     * [confirmedOnly] (upload): a request counts the bytes it returns once it has finished, and the phase's time ends
     * at the last such count, so pieces cut off by the end of the phase count neither bytes nor time. Pieces start at
     * [MIN_PIECE_BYTES] and double per worker while one takes less than [PIECE_GROW_MS] (up to [MAX_PIECE_BYTES]), so
     * a slow link still finishes pieces and a fast one is not slowed by a wait for each answer. A piece is at most the
     * bytes left to [limit] divided by the connections, so pieces still in flight pass the cap by about the bytes that
     * were left when they started (a 3 MB cap ended at 4.1 MB in the unit test).
     */
    private suspend fun transfer(
        type: ThroughputType,
        base: String,
        config: SpeedTestConfig,
        limit: Long,
        onProgress: (SpeedTestProgress) -> Unit,
        confirmedOnly: Boolean,
        request: (url: okhttp3.HttpUrl, bytes: AtomicLong, limit: Long, location: AtomicReference<String?>, pieceBytes: Long) -> Pair<Call, () -> Long>,
    ): ThroughputResult {
        if (uncached(base) == null) return failed(type, base, BAD_URL)
        val startMs = clock.wallTimeMs()
        val startNs = clock.elapsedRealtimeNs()
        val bytes = AtomicLong()
        val lastCountNs = AtomicLong(0)
        val firstError = AtomicReference<String?>(null)
        val rejected = AtomicReference<String?>(null)
        val location = AtomicReference<String?>(null)
        val open = Collections.synchronizedSet(mutableSetOf<Call>())
        val t0 = System.nanoTime()
        val deadline = t0 + config.durationMs * 1_000_000
        fun done() = System.nanoTime() >= deadline || bytes.get() >= limit
        coroutineScope {
            repeat(config.streams) {
                launch(Dispatchers.IO) {
                    var piece = MIN_PIECE_BYTES
                    while (isActive && !done()) {
                        val url = uncached(base) ?: break
                        val size = minOf(piece, maxOf(MIN_PIECE_BYTES, (limit - bytes.get()) / config.streams))
                        val (call, execute) = request(url, bytes, limit, location, size)
                        call.timeout().timeout((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS)
                        open += call
                        val sent = System.nanoTime()
                        try {
                            val counted = execute()
                            if (confirmedOnly && counted > 0) {
                                val now = System.nanoTime()
                                bytes.addAndGet(counted)
                                lastCountNs.accumulateAndGet(now, ::maxOf)
                                if (now - sent < PIECE_GROW_MS * 1_000_000) piece = minOf(piece * 2, MAX_PIECE_BYTES)
                            }
                        } catch (e: HttpStatusException) {
                            // The server refused the request: the counted bytes do not show the link speed.
                            rejected.compareAndSet(null, e.message)
                            break
                        } catch (e: IOException) {
                            if (!done()) {
                                firstError.compareAndSet(null, e.message ?: e.javaClass.simpleName)
                                delay(RETRY_DELAY_MS)
                            }
                        } finally {
                            open -= call
                        }
                    }
                }
            }
            launch {
                try {
                    while (!done()) {
                        delay(PROGRESS_MS)
                        onProgress(SpeedTestProgress(type, mbps = mbps(bytes.get(), System.nanoTime() - t0)))
                    }
                } finally {
                    // Also when the test is stopped: the workers block in socket reads and writes, which a coroutine
                    // cancel does not interrupt; cancelling the calls does, at once.
                    synchronized(open) { open.forEach(Call::cancel) }
                }
            }
        }
        val endNs = if (confirmedOnly) lastCountNs.get().takeIf { it > 0 } ?: minOf(System.nanoTime(), deadline) else minOf(System.nanoTime(), deadline)
        val elapsedNs = (endNs - t0).coerceAtLeast(1)
        val total = bytes.get()
        val refusal = rejected.get()
        return ThroughputResult(
            type, startMs, startNs, base,
            durationMs = elapsedNs / 1_000_000, bytes = total,
            mbps = if (total > 0 && refusal == null) mbps(total, elapsedNs) else null,
            streams = config.streams, rttMinMs = null, rttAvgMs = null, rttMaxMs = null, jitterMs = null, lossPct = null,
            error = refusal ?: if (total == 0L) firstError.get() ?: "no data" else null,
            serverLocation = location.get(),
        )
    }

    /** Where the server answered from: Cloudflare's CF-RAY location code; null for servers without one. */
    private fun Response.location(): String? = cloudflareLocation(header("CF-RAY"))

    /** A non-2xx answer: the server refused the request, or something on the way redirected it. */
    private class HttpStatusException(response: Response) : IOException(
        "HTTP ${response.code}" + (response.header("Location")?.toHttpUrlOrNull()?.host?.let { " (redirect to $it)" } ?: ""),
    )

    private fun mbps(bytes: Long, elapsedNs: Long) = bytes * 8 / (elapsedNs / 1e9) / 1e6

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000L
        const val READ_TIMEOUT_MS = 5_000L
        const val PING_TIMEOUT_MS = 3_000L
        const val PING_GAP_MS = 100L
        const val PROGRESS_MS = 500L
        const val RETRY_DELAY_MS = 200L
        const val CHUNK_BYTES = 64 * 1024
        const val MIN_PIECE_BYTES = 256L * 1024
        const val MAX_PIECE_BYTES = 8L * 1024 * 1024
        const val PIECE_GROW_MS = 1_000L
        const val BAD_URL = "invalid URL"
        val OCTET_STREAM = "application/octet-stream".toMediaType()

        /** Incompressible upload data. */
        val RANDOM_BLOCK = Random(42).nextBytes(64 * 1024)
    }
}
