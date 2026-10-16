package id.my.pamslab.netlogger.core.diagnostics

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Problem reports kept on the phone (decision 005), files/problems/<id>/:
 * problem.json ([Problem]), trace.bin (Android's ANR dump or native tombstone), java.txt (stack trace and log lines
 * written by [CrashRecorder]), log.txt (the ended process' log lines still in the buffer at the next start).
 * Main process only; nothing leaves the phone unless the user sends a report.
 */
@Singleton
class ProblemReports @Inject constructor(@ApplicationContext private val context: Context) {

    private val root = File(context.filesDir, "problems")
    private val prefs = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _reports = MutableStateFlow<List<Problem>>(emptyList())

    /** Newest first. */
    val reports: StateFlow<List<Problem>> = _reports.asStateFlow()

    /**
     * Reads the exit records Android added since the last call and keeps a report for each unexpected end. Blocking
     * (file and logcat I/O): call it off the main thread, once per start of the main process.
     */
    @Synchronized
    fun refresh() {
        try {
            val am = context.getSystemService(ActivityManager::class.java)
            val lastSeen = prefs.getLong(KEY_LAST_SEEN, 0)
            val exits = am?.getHistoricalProcessExitReasons(context.packageName, 0, 0).orEmpty()
                .filter { it.timestamp > lastSeen }
                .sortedBy { it.timestamp }
            exits.forEach { keep(it) }
            exits.maxOfOrNull { it.timestamp }?.let { saveLastSeen(it) }
            if (exits.isNotEmpty()) Log.i(TAG, "exit records: " + exits.joinToString { "${ExitReason.name(it.reason)}@${it.processName}" })
        } catch (e: Exception) {
            Log.w(TAG, "exit records not read", e)
        }
        pendingJavaFiles().forEach { if (it.lastModified() < System.currentTimeMillis() - PENDING_MAX_AGE_MS) it.delete() }
        prune()
        _reports.value = load()
    }

    fun markSeen(id: String) = update(id) { it.copy(seen = true) }

    fun delete(id: String) {
        File(root, id).deleteRecursively()
        _reports.value = load()
    }

    /** A kept file of report [id] ("trace.bin", "java.txt", "log.txt"); null if it does not exist. */
    fun file(id: String, name: String): File? = File(File(root, id), name).takeIf { it.isFile }

    private fun keep(exit: ApplicationExitInfo) {
        val state = ProcessStateSummary.decode(exit.processStateSummary)
        val kind = ExitReason.classify(exit.reason, state) ?: return
        val id = "${exit.timestamp}_${exit.pid}"
        val dir = File(root, id).apply { mkdirs() }
        val trace = try {
            exit.traceInputStream?.use { input -> readUpTo(input, MAX_TRACE_BYTES) }
        } catch (e: Exception) {
            Log.w(TAG, "trace not readable", e)
            null
        }
        trace?.let { File(dir, TRACE).writeBytes(it) }
        val java = File(CrashRecorder.pendingDir(context), "java_${exit.pid}.txt")
        val hasJava = kind == ProblemKind.CRASH && java.isFile && java.renameTo(File(dir, JAVA))
        val log = OwnLog.linesOf(exit.pid)
        if (log.isNotEmpty()) File(dir, LOG).writeText(log.joinToString("\n", postfix = "\n"))
        val problem = Problem(
            id = id, kind = kind, timeMs = exit.timestamp, pid = exit.pid, processName = exit.processName,
            reason = exit.reason, reasonName = ExitReason.name(exit.reason), status = exit.status,
            importance = exit.importance, description = exit.description, pssKb = exit.pss, rssKb = exit.rss,
            state = state, hasTrace = trace != null, hasJavaStack = hasJava, hasLog = log.isNotEmpty(),
        )
        File(dir, PROBLEM).writeText(json.encodeToString(Problem.serializer(), problem))
    }

    private fun load(): List<Problem> = root.listFiles().orEmpty()
        .mapNotNull { dir ->
            val f = File(dir, PROBLEM)
            if (!f.isFile) return@mapNotNull null
            try {
                json.decodeFromString(Problem.serializer(), f.readText())
            } catch (e: Exception) {
                Log.w(TAG, "problem report ${dir.name} unreadable", e)
                null
            }
        }
        .sortedByDescending { it.timeMs }

    /** At most [MAX_REPORTS] reports (decision 005, D7); the oldest go first. */
    private fun prune() {
        load().drop(MAX_REPORTS).forEach { File(root, it.id).deleteRecursively() }
    }

    @Synchronized
    private fun update(id: String, change: (Problem) -> Problem) {
        val f = File(File(root, id), PROBLEM)
        val p = load().firstOrNull { it.id == id } ?: return
        f.writeText(json.encodeToString(Problem.serializer(), change(p)))
        _reports.value = load()
    }

    @SuppressLint("UseKtx") // core-ktx is not a dependency of core:diagnostics; not worth one for a single edit.
    private fun saveLastSeen(timestamp: Long) = prefs.edit().putLong(KEY_LAST_SEEN, timestamp).apply()

    /** InputStream.readNBytes needs API 33; minSdk is 31. */
    private fun readUpTo(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (out.size() < max) {
            val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun pendingJavaFiles() = CrashRecorder.pendingDir(context).listFiles().orEmpty().toList()

    companion object {
        const val PROBLEM = "problem.json"
        const val TRACE = "trace.bin"
        const val JAVA = "java.txt"
        const val LOG = "log.txt"
        const val MAX_REPORTS = 20
        private const val MAX_TRACE_BYTES = 2 * 1024 * 1024
        private const val KEY_LAST_SEEN = "lastExitTimestamp"

        /** A java_<pid>.txt without a matching exit record (e.g. the record was not kept) is dropped after a week. */
        private const val PENDING_MAX_AGE_MS = 7L * 24 * 3600 * 1000
    }
}
