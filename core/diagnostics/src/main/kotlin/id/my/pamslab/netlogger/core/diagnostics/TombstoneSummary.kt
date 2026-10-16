package id.my.pamslab.netlogger.core.diagnostics

/**
 * The few parts of an Android native-crash tombstone (protobuf, `system/core/debuggerd/proto/tombstone.proto`, given by
 * ApplicationExitInfo.getTraceInputStream on Android 12+) that a problem report shows (decision 005, D5): signal,
 * abort message, cause, and the crashing thread's backtrace. Memory dumps, registers, other threads, the log buffer
 * and the memory map are skipped on purpose: they can hold app data and are not filtered.
 */
data class TombstoneSummary(
    val signal: String?,
    val faultAddress: Long?,
    val abortMessage: String?,
    val cause: String?,
    val threadName: String?,
    /** "#00 libmaplibre.so mln::android::MapRenderer::render(_JNIEnv&)+144", at most [MAX_FRAMES]. */
    val frames: List<String>,
) {
    /** "SIGSEGV in libmaplibre.so mln::android::MapRenderer::render(_JNIEnv&)": for the report's "What" line. */
    fun headline(): String {
        val top = frames.firstOrNull { !it.contains(" libc.so ") }?.substringAfter(' ')?.substringBeforeLast('+')
        return listOfNotNull(signal, top?.let { "in $it" }).joinToString(" ").ifEmpty { "native crash" }
    }

    companion object {
        const val MAX_FRAMES = 32
        private const val MAX_FUNCTION = 120

        /** Null if [bytes] is not a readable tombstone. */
        fun parse(bytes: ByteArray): TombstoneSummary? = try {
            parseOrThrow(bytes)
        } catch (e: ProtoException) {
            null
        }

        private fun parseOrThrow(bytes: ByteArray): TombstoneSummary? {
            var tid: Long? = null
            var signal: Proto? = null
            var abort: String? = null
            var cause: String? = null
            val threads = mutableMapOf<Long, Proto>()
            Proto(bytes).forEach { f ->
                when (f.number) {
                    TOMBSTONE_TID -> tid = f.varint
                    TOMBSTONE_SIGNAL -> signal = f.message()
                    TOMBSTONE_ABORT -> abort = f.string().takeIf { it.isNotBlank() }
                    TOMBSTONE_CAUSES -> if (cause == null) cause = f.message().strings(CAUSE_TEXT).firstOrNull()
                    TOMBSTONE_THREADS -> {
                        // map<uint32, Thread>: an entry with key = 1, value = 2
                        var key: Long? = null
                        var value: Proto? = null
                        f.message().forEach { e ->
                            if (e.number == 1) key = e.varint
                            if (e.number == 2) value = e.message()
                        }
                        val k = key
                        val v = value
                        if (k != null && v != null) threads[k] = v
                    }
                }
            }
            val sig = signal
            if (sig == null && threads.isEmpty()) return null
            val thread = tid?.let { threads[it] } ?: threads.values.firstOrNull()
            return TombstoneSummary(
                signal = sig?.let { s ->
                    val name = s.strings(SIGNAL_NAME).firstOrNull()
                    val code = s.strings(SIGNAL_CODE_NAME).firstOrNull()
                    listOfNotNull(name, code?.let { "($it)" }).joinToString(" ").ifEmpty { null }
                },
                // proto3 leaves zero values out: "has a fault address" without one means 0x0.
                faultAddress = sig?.takeIf { s -> s.varints(SIGNAL_HAS_FAULT_ADDRESS).firstOrNull() == 1L }
                    ?.let { s -> s.varints(SIGNAL_FAULT_ADDRESS).firstOrNull() ?: 0L },
                abortMessage = abort,
                cause = cause,
                threadName = thread?.strings(THREAD_NAME)?.firstOrNull(),
                frames = thread?.messages(THREAD_BACKTRACE).orEmpty().take(MAX_FRAMES).mapIndexed { i, frame -> frameLine(i, frame) },
            )
        }

        private fun frameLine(index: Int, frame: Proto): String {
            val file = frame.strings(FRAME_FILE).firstOrNull()?.let(::libraryName) ?: "?"
            // C++ template names can run to thousands of characters.
            val function = frame.strings(FRAME_FUNCTION).firstOrNull()?.takeIf { it.isNotEmpty() }
                ?.let { if (it.length > MAX_FUNCTION) it.take(MAX_FUNCTION) + "…" else it }
            val where = if (function != null) {
                "$function+${frame.varints(FRAME_FUNCTION_OFFSET).firstOrNull() ?: 0}"
            } else {
                "pc 0x%x".format(frame.varints(FRAME_REL_PC).firstOrNull() ?: 0)
            }
            return "#%02d %s %s".format(index, file, where)
        }

        /** "/data/app/~~x==/pkg-y==/base.apk!libmaplibre.so" -> "libmaplibre.so": no install path in a report. */
        internal fun libraryName(path: String): String = path.substringAfterLast('!').substringAfterLast('/')

        // tombstone.proto field numbers
        private const val TOMBSTONE_TID = 6
        private const val TOMBSTONE_SIGNAL = 10
        private const val TOMBSTONE_ABORT = 14
        private const val TOMBSTONE_CAUSES = 15
        private const val TOMBSTONE_THREADS = 16
        private const val SIGNAL_NAME = 2
        private const val SIGNAL_CODE_NAME = 4
        private const val SIGNAL_HAS_FAULT_ADDRESS = 8
        private const val SIGNAL_FAULT_ADDRESS = 9
        private const val CAUSE_TEXT = 1
        private const val THREAD_NAME = 2
        private const val THREAD_BACKTRACE = 4
        private const val FRAME_REL_PC = 1
        private const val FRAME_FUNCTION = 4
        private const val FRAME_FUNCTION_OFFSET = 5
        private const val FRAME_FILE = 6
    }
}

internal class ProtoException(message: String) : Exception(message)

/** A minimal protobuf reader: enough to walk fields; no schema, no dependency. */
internal class Proto(private val bytes: ByteArray, private val start: Int = 0, private val end: Int = bytes.size) {

    class Field(val number: Int, val wireType: Int, val varint: Long, private val bytes: ByteArray, private val from: Int, private val to: Int) {
        fun message() = Proto(bytes, from, to)

        fun string() = String(bytes, from, to - from, Charsets.UTF_8)
    }

    fun forEach(action: (Field) -> Unit) {
        var pos = start
        while (pos < end) {
            val (key, afterKey) = readVarint(pos)
            pos = afterKey
            val number = (key ushr 3).toInt()
            val wireType = (key and 7).toInt()
            if (number <= 0) throw ProtoException("field number $number")
            when (wireType) {
                0 -> {
                    val (v, next) = readVarint(pos)
                    action(Field(number, wireType, v, bytes, pos, pos))
                    pos = next
                }
                1 -> {
                    need(pos, 8)
                    action(Field(number, wireType, readFixed(pos, 8), bytes, pos, pos + 8))
                    pos += 8
                }
                2 -> {
                    val (len, next) = readVarint(pos)
                    if (len < 0 || len > end - next) throw ProtoException("length $len")
                    action(Field(number, wireType, len, bytes, next, next + len.toInt()))
                    pos = next + len.toInt()
                }
                5 -> {
                    need(pos, 4)
                    action(Field(number, wireType, readFixed(pos, 4), bytes, pos, pos + 4))
                    pos += 4
                }
                else -> throw ProtoException("wire type $wireType")
            }
        }
    }

    fun strings(number: Int): List<String> = collect(number) { if (it.wireType == 2) it.string() else null }

    fun messages(number: Int): List<Proto> = collect(number) { if (it.wireType == 2) it.message() else null }

    fun varints(number: Int): List<Long> = collect(number) { if (it.wireType == 0 || it.wireType == 1) it.varint else null }

    private fun <T> collect(number: Int, map: (Field) -> T?): List<T> {
        val out = mutableListOf<T>()
        forEach { f -> if (f.number == number) map(f)?.let(out::add) }
        return out
    }

    private fun need(pos: Int, n: Int) {
        if (pos + n > end) throw ProtoException("truncated")
    }

    private fun readFixed(pos: Int, n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = v or ((bytes[pos + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    private fun readVarint(from: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var pos = from
        while (true) {
            if (pos >= end || shift > 63) throw ProtoException("varint")
            val b = bytes[pos++].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result to pos
            shift += 7
        }
    }
}
