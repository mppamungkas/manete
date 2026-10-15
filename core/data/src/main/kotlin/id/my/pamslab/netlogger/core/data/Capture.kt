package id.my.pamslab.netlogger.core.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import id.my.pamslab.netlogger.core.model.capture.CaptureState
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** The current capture state; written by feature:capture, read by the logging engine (speed test captureActive). */
@Singleton
class CaptureStateHolder @Inject constructor() {
    val state = MutableStateFlow(CaptureState())
}

/**
 * Where NetLogger's captures are stored: app-private files/captures/<session uuid>.pcap, + _sync.csv,
 * + _connections.csv (sessions from the removed PCAPdroid bridge have no _connections.csv).
 */
interface CaptureFileStore {
    fun pcap(sessionUuid: String): File
    fun sync(sessionUuid: String): File
    fun connections(sessionUuid: String): File

    /** The session's capture files, if present. */
    fun existing(sessionUuid: String): List<File> =
        listOf(pcap(sessionUuid), sync(sessionUuid), connections(sessionUuid)).filter { it.isFile }

    fun delete(sessionUuid: String) = existing(sessionUuid).forEach { it.delete() }
}

@Singleton
class AppCaptureFileStore @Inject constructor(@ApplicationContext context: Context) : CaptureFileStore {
    private val dir = File(context.filesDir, "captures")

    override fun pcap(sessionUuid: String) = File(dir.apply { mkdirs() }, "$sessionUuid.pcap")
    override fun sync(sessionUuid: String) = File(dir.apply { mkdirs() }, "${sessionUuid}_sync.csv")
    override fun connections(sessionUuid: String) = File(dir.apply { mkdirs() }, "${sessionUuid}_connections.csv")
}
