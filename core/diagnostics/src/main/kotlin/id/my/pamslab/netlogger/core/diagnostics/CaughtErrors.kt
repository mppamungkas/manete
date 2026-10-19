package id.my.pamslab.netlogger.core.diagnostics

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * One kind of error NetLogger caught and survived: where it happened (NetLogger's own words, which follow the log line
 * rules: no location, cell identity, SSID or IP address), the exception's class and the first NetLogger frame. Never
 * the exception message: it can carry hosts, IP addresses or file names.
 */
@Serializable
data class CaughtError(
    val where: String,
    val exception: String?,
    val frame: String?,
    val process: String,
    val count: Long,
    val firstMs: Long,
    val lastMs: Long,
)

/**
 * Pure part of [CaughtErrors]: grouping, the 7-day window and the summary text, unit-tested on the JVM.
 * The same (where, exception, frame) is counted, not stored again, so an error in every tick stays one line.
 */
class CaughtErrorLog(private val process: String) {

    private val entries = ConcurrentHashMap<String, CaughtError>()

    /** True when this is a new kind of error (worth saving at once). */
    fun record(where: String, error: Throwable?, nowMs: Long): Boolean {
        val exception = error?.let(::exceptionName)
        val frame = error?.let(::ownFrame)
        var added = false
        entries.compute(key(where, exception, frame)) { _, old ->
            if (old == null) {
                added = true
                CaughtError(where, exception, frame, process, 1, nowMs, nowMs)
            } else {
                old.copy(count = old.count + 1, lastMs = nowMs)
            }
        }
        if (added && entries.size > MAX_ENTRIES) {
            entries.values.minByOrNull { it.lastMs }?.let { oldest -> entries.remove(key(oldest.where, oldest.exception, oldest.frame)) }
        }
        return added
    }

    /** Adds what an earlier process saved (same counts, not doubled). */
    fun restore(saved: List<CaughtError>, nowMs: Long) {
        saved.filter { nowMs - it.lastMs < KEEP_MS }.forEach { entries.putIfAbsent(key(it.where, it.exception, it.frame), it) }
    }

    fun snapshot(nowMs: Long): List<CaughtError> = entries.values.filter { nowMs - it.lastMs < KEEP_MS }.sortedByDescending { it.lastMs }

    private fun key(where: String, exception: String?, frame: String?) = "$where|$exception|$frame"

    companion object {
        const val KEEP_MS = 7L * 24 * 3600 * 1000
        const val MAX_ENTRIES = 50
        private const val MAX_LINES = 30
        private const val OWN_PACKAGE = "id.my.pamslab.netlogger"

        /** "SecurityException", or "IOException ← ErrnoException" for the innermost cause. */
        internal fun exceptionName(error: Throwable): String {
            val name = error.javaClass.simpleName.ifEmpty { error.javaClass.name }
            var root = error
            while (root.cause != null && root.cause !== root) root = root.cause!!
            val rootName = root.javaClass.simpleName.ifEmpty { root.javaClass.name }
            return if (root === error || rootName == name) name else "$name ← $rootName"
        }

        /** "AndroidTelephonySource.register:124": the first frame in NetLogger's code, which says where it was caught. */
        internal fun ownFrame(error: Throwable): String? =
            generateSequence(error) { it.cause.takeIf { c -> c !== it } }
                .flatMap { it.stackTrace.asSequence() }
                .firstOrNull { it.className.startsWith(OWN_PACKAGE) }
                ?.let { "${it.className.substringAfterLast('.').substringBefore('$')}.${it.methodName}:${it.lineNumber}" }

        /**
         * The report section: newest first, one line per kind with count, time span and process, e.g.
         * "12× extract signalStrength.timestamp failed · NoSuchMethodError · Extract.get:44 · 10-19 10:21–10:40 · main".
         */
        fun summary(errors: List<CaughtError>, zone: TimeZone = TimeZone.getDefault()): List<String> {
            val day = SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).apply { timeZone = zone }
            val time = SimpleDateFormat("HH:mm", Locale.ROOT).apply { timeZone = zone }
            val lines = errors.sortedByDescending { it.lastMs }.take(MAX_LINES).map { e ->
                val first = day.format(Date(e.firstMs))
                val span = when {
                    e.count == 1L -> first
                    day.format(Date(e.lastMs)).take(5) == first.take(5) -> "$first–${time.format(Date(e.lastMs))}"
                    else -> "$first – ${day.format(Date(e.lastMs))}"
                }
                listOfNotNull("${e.count}× ${e.where}", e.exception, e.frame, span, e.process).joinToString(" · ")
            }
            val more = errors.size - lines.size
            return if (more > 0) lines + "… and $more more" else lines
        }
    }
}

/**
 * Errors NetLogger caught and kept running after (2026-10-19), for "Describe a problem": kept in
 * files/problems/caught/<process>.json for 7 days, per process (main and :vpn), so they survive a restart. Saved at
 * once for a new kind of error, otherwise at most every 30 s. Nothing leaves the phone unless the user sends a report.
 *
 * Call [caught] in a catch block instead of `Log.w`: it writes the same log line and records the error.
 */
object CaughtErrors {

    private const val SAVE_EVERY_MS = 30_000L

    @Volatile private var log: CaughtErrorLog? = null
    @Volatile private var file: File? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val lastSaveMs = AtomicLong(0)
    private val saveQueued = AtomicBoolean(false)
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "NetLogger-caught").apply { isDaemon = true } }

    /** Once per process, early (next to [CrashRecorder.install]). */
    fun install(context: Context, processName: String) {
        if (log != null) return
        val label = if (processName.contains(':')) processName.substringAfterLast(':') else "main"
        val target = File(dir(context), "$label.json")
        val fresh = CaughtErrorLog(label)
        file = target
        log = fresh
        io.execute {
            try {
                if (target.isFile) fresh.restore(json.decodeFromString(ListSerializer(CaughtError.serializer()), target.readText()), System.currentTimeMillis())
            } catch (e: Exception) {
                Log.w(TAG, "caught errors not read", e)
            }
        }
    }

    /** Logs [where] (with [error]'s stack trace) like `Log.w(TAG, where, error)` and records it. */
    fun caught(where: String, error: Throwable? = null) {
        Log.w(TAG, where, error)
        record(where, error)
    }

    /** Records without logging, for places that write their own log line. */
    fun record(where: String, error: Throwable? = null) {
        val target = log ?: return // not installed (unit tests, or before Application.onCreate)
        val now = System.currentTimeMillis()
        val added = target.record(where, error, now)
        if (added || now - lastSaveMs.get() >= SAVE_EVERY_MS) save()
    }

    /** Every process' caught errors of the last 7 days, newest first. Blocking (file I/O). */
    fun all(context: Context): List<CaughtError> {
        val now = System.currentTimeMillis()
        val own = log?.snapshot(now).orEmpty()
        val ownFile = file
        val others = dir(context).listFiles().orEmpty().filter { it != ownFile && it.name.endsWith(".json") }.flatMap { f ->
            try {
                json.decodeFromString(ListSerializer(CaughtError.serializer()), f.readText())
            } catch (e: Exception) {
                Log.w(TAG, "caught errors not read", e)
                emptyList()
            }
        }
        return (own + others).filter { now - it.lastMs < CaughtErrorLog.KEEP_MS }.sortedByDescending { it.lastMs }
    }

    private fun save() {
        if (!saveQueued.compareAndSet(false, true)) return
        io.execute {
            saveQueued.set(false)
            lastSaveMs.set(System.currentTimeMillis())
            val target = file ?: return@execute
            val entries = log?.snapshot(System.currentTimeMillis()) ?: return@execute
            try {
                target.parentFile?.mkdirs()
                val tmp = File(target.parentFile, target.name + ".tmp")
                tmp.writeText(json.encodeToString(ListSerializer(CaughtError.serializer()), entries))
                if (!tmp.renameTo(target)) Log.w(TAG, "caught errors not saved")
            } catch (e: Exception) {
                Log.w(TAG, "caught errors not saved", e)
            }
        }
    }

    private fun dir(context: Context) = File(context.filesDir, "problems/caught")
}
