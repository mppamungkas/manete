package id.my.pamslab.netlogger.core.data

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import id.my.pamslab.netlogger.core.data.db.FloorPlanDao
import id.my.pamslab.netlogger.core.data.db.FloorPlanEntity
import id.my.pamslab.netlogger.core.data.db.NetLoggerDatabase
import id.my.pamslab.netlogger.core.diagnostics.CaughtErrors
import id.my.pamslab.netlogger.core.model.Clock
import id.my.pamslab.netlogger.core.model.indoor.FloorPlan
import id.my.pamslab.netlogger.core.model.indoor.PlanGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A floor plan for the Floor plans page: the plan, its stored image and how many sessions use it. */
data class FloorPlanSummary(
    val plan: FloorPlan,
    val image: File,
    val createdAt: Long,
    val sessions: Int,
    val waypoints: Int,
)

/** A file the user picked, before importing: its name and, for a PDF, its page count. */
data class PlanSource(val displayName: String?, val pdfPages: Int?)

sealed interface PlanImportResult {
    data class Imported(val id: Long) : PlanImportResult

    data class Failed(val reason: PlanImportError) : PlanImportResult
}

enum class PlanImportError {
    /** Not an image or PDF Android can read. */
    UNSUPPORTED,

    /** The file could not be opened or read. */
    UNREADABLE,

    /** A password-protected PDF. */
    PDF_PROTECTED,

    /** Not enough memory to decode it. */
    TOO_LARGE,

    /** The stored copy could not be written (storage full?). */
    NOT_SAVED,
}

/**
 * Floor plans for indoor tests (decision 006): imported images live in files/plans/<uuid>.png, downscaled to at most
 * [PlanGeometry.MAX_SIDE_PX]. A plan used by a session cannot be deleted.
 */
interface FloorPlanRepository {
    /** Newest first. */
    val plans: Flow<List<FloorPlanSummary>>

    suspend fun plan(id: Long): FloorPlanSummary?

    /** Name and page count of a picked file, or why it cannot be opened ([PlanImageException]). */
    suspend fun inspect(uri: Uri): Result<PlanSource>

    /** Imports an image, or page [pdfPage] (0-based) of a PDF. */
    suspend fun import(uri: Uri, pdfPage: Int, name: String, floor: String?): PlanImportResult

    suspend fun rename(id: Long, name: String, floor: String?)

    /** [metresPerPx] null removes the scale. */
    suspend fun setScale(id: Long, metresPerPx: Double?)

    /** False when a session uses the plan (it stays). */
    suspend fun delete(id: Long): Boolean
}

/** Where plan images are stored. */
interface PlanFileStore {
    val dir: File

    fun image(imageFile: String) = File(dir, imageFile)
}

@Singleton
class AppPlanFileStore @Inject constructor(@ApplicationContext context: Context) : PlanFileStore {
    override val dir = File(context.filesDir, "plans")
}

/** Decodes a picked file into the bitmap to store; Android-only, so tests replace it. */
interface PlanImageReader {
    fun inspect(uri: Uri): Result<PlanSource>

    /** The plan bitmap, already within [PlanGeometry.MAX_SIDE_PX], or the reason it failed. */
    fun read(uri: Uri, pdfPage: Int): Result<Bitmap>
}

class PlanImageException(val reason: PlanImportError, cause: Throwable? = null) : Exception(reason.name, cause)

@Singleton
class AndroidPlanImageReader @Inject constructor(@ApplicationContext context: Context) : PlanImageReader {
    private val resolver: ContentResolver = context.contentResolver

    override fun inspect(uri: Uri): Result<PlanSource> = try {
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
        val pages = if (isPdf(uri)) pdf(uri) { it.pageCount } else null
        Result.success(PlanSource(name, pages))
    } catch (e: SecurityException) {
        // PdfRenderer: "Password required to access document" (moto g45, 2026-10-21).
        Result.failure(PlanImageException(if (isPdf(uri)) PlanImportError.PDF_PROTECTED else PlanImportError.UNREADABLE, e))
    } catch (e: Exception) {
        CaughtErrors.caught("floor plan: picked file not readable", e)
        Result.failure(PlanImageException(PlanImportError.UNREADABLE, e))
    }

    override fun read(uri: Uri, pdfPage: Int): Result<Bitmap> = try {
        Result.success(if (isPdf(uri)) renderPdf(uri, pdfPage) else decodeImage(uri))
    } catch (e: PlanImageException) {
        Result.failure(e)
    } catch (e: OutOfMemoryError) {
        Result.failure(PlanImageException(PlanImportError.TOO_LARGE, e))
    } catch (e: ImageDecoder.DecodeException) {
        Result.failure(PlanImageException(PlanImportError.UNSUPPORTED, e))
    } catch (e: SecurityException) {
        // PdfRenderer: a password-protected PDF.
        Result.failure(PlanImageException(if (isPdf(uri)) PlanImportError.PDF_PROTECTED else PlanImportError.UNREADABLE, e))
    } catch (e: Exception) {
        Result.failure(PlanImageException(PlanImportError.UNREADABLE, e))
    }

    private fun isPdf(uri: Uri) = resolver.getType(uri) == "application/pdf"

    /** PNG, JPEG, WebP, HEIC …; EXIF rotation applied by ImageDecoder. */
    private fun decodeImage(uri: Uri): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            val (w, h) = PlanGeometry.storedSize(info.size.width, info.size.height)
            decoder.setTargetSize(w, h)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // compressible
        }

    @SuppressLint("UseKtx") // core-ktx is not a dependency of core:data; not worth one for createBitmap.
    private fun renderPdf(uri: Uri, pageIndex: Int): Bitmap = pdf(uri) { renderer ->
        if (pageIndex !in 0 until renderer.pageCount) throw PlanImageException(PlanImportError.UNREADABLE)
        renderer.openPage(pageIndex).use { page ->
            val (w, h) = PlanGeometry.pdfRenderSize(page.width, page.height)
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                it.eraseColor(Color.WHITE) // PDF pages are transparent where nothing is drawn
                page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
    }

    private fun <T> pdf(uri: Uri, block: (PdfRenderer) -> T): T {
        val fd = resolver.openFileDescriptor(uri, "r") ?: throw PlanImageException(PlanImportError.UNREADABLE)
        return fd.use { PdfRenderer(it).use(block) }
    }
}

@Singleton
class RoomFloorPlanRepository @Inject constructor(
    db: NetLoggerDatabase,
    private val files: PlanFileStore,
    private val reader: PlanImageReader,
    private val clock: Clock,
) : FloorPlanRepository {

    private val dao: FloorPlanDao = db.floorPlanDao()

    override val plans: Flow<List<FloorPlanSummary>> = dao.observeWithUsage().map { rows ->
        rows.map { summary(it.plan, it.sessions, it.waypoints) }
    }

    override suspend fun plan(id: Long): FloorPlanSummary? {
        val entity = dao.get(id) ?: return null
        return summary(entity, sessions = dao.sessionCount(id), waypoints = dao.activeWaypointCount(id))
    }

    override suspend fun inspect(uri: Uri): Result<PlanSource> = withContext(Dispatchers.IO) { reader.inspect(uri) }

    override suspend fun import(uri: Uri, pdfPage: Int, name: String, floor: String?): PlanImportResult = withContext(Dispatchers.IO) {
        val bitmap = reader.read(uri, pdfPage).getOrElse { e ->
            val reason = (e as? PlanImageException)?.reason ?: PlanImportError.UNREADABLE
            CaughtErrors.record("floor plan import failed: $reason", e)
            return@withContext PlanImportResult.Failed(reason)
        }
        val width = bitmap.width
        val height = bitmap.height
        val uuid = UUID.randomUUID().toString()
        val fileName = "$uuid.png"
        try {
            files.dir.mkdirs()
            val tmp = File(files.dir, "$fileName.tmp")
            tmp.outputStream().use { out -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) }
            check(tmp.renameTo(files.image(fileName)))
        } catch (e: Exception) {
            CaughtErrors.caught("floor plan image not saved", e)
            return@withContext PlanImportResult.Failed(PlanImportError.NOT_SAVED)
        } finally {
            bitmap.recycle()
        }
        val id = dao.insert(
            FloorPlanEntity(
                uuid = uuid, name = name.trim().ifEmpty { "Floor plan" }, floor = floor?.trim()?.ifEmpty { null },
                imageFile = fileName, widthPx = width, heightPx = height, createdAt = clock.wallTimeMs(),
            ),
        )
        Log.i(TAG, "floor plan imported: $width x $height px" + if (pdfPage > 0) " (PDF page ${pdfPage + 1})" else "")
        PlanImportResult.Imported(id)
    }

    override suspend fun rename(id: Long, name: String, floor: String?) =
        dao.rename(id, name.trim().ifEmpty { "Floor plan" }, floor?.trim()?.ifEmpty { null })

    override suspend fun setScale(id: Long, metresPerPx: Double?) = dao.setScale(id, metresPerPx)

    override suspend fun delete(id: Long): Boolean {
        val plan = dao.get(id) ?: return true
        if (dao.waypointCount(id) > 0) return false
        dao.delete(id)
        withContext(Dispatchers.IO) { files.image(plan.imageFile).delete() }
        return true
    }

    private fun summary(e: FloorPlanEntity, sessions: Int, waypoints: Int) = FloorPlanSummary(
        plan = FloorPlan(e.id, e.uuid, e.name, e.floor, e.widthPx, e.heightPx, e.metresPerPx),
        image = files.image(e.imageFile),
        createdAt = e.createdAt,
        sessions = sessions,
        waypoints = waypoints,
    )

    private companion object {
        const val TAG = "NetLogger"
    }
}
