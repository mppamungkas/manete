package id.my.pamslab.netlogger.core.ui.plan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import id.my.pamslab.netlogger.core.ui.R
import id.my.pamslab.netlogger.core.diagnostics.CaughtErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** Longest side a plan is decoded at for the screen: sharp enough zoomed in, a quarter of the stored size in memory. */
private const val DISPLAY_MAX_SIDE = 2048

/**
 * Decodes [file] for display, halving until the long side is at most [maxSide]. Null if unreadable. Blocking: call
 * off the main thread.
 */
fun decodePlanBitmap(file: File, maxSide: Int = DISPLAY_MAX_SIDE): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
} catch (e: Exception) {
    CaughtErrors.caught("floor plan image not decoded", e)
    null
}

/** The plan image of [file], decoded off the main thread; null while loading or if unreadable (then [failed]). */
@Composable
fun rememberPlanBitmap(file: File, maxSide: Int = DISPLAY_MAX_SIDE): PlanBitmapState {
    val state by produceState<PlanBitmapState>(PlanBitmapState.Loading, file, maxSide) {
        value = withContext(Dispatchers.IO) { decodePlanBitmap(file, maxSide) }
            ?.let { PlanBitmapState.Ready(it.asImageBitmap()) } ?: PlanBitmapState.Failed
    }
    return state
}

sealed interface PlanBitmapState {
    data object Loading : PlanBitmapState

    data object Failed : PlanBitmapState

    data class Ready(val bitmap: ImageBitmap) : PlanBitmapState
}

/**
 * A floor plan the user can zoom (pinch, up to [PlanViewport.MAX_ZOOM]), pan and tap. [planWidth] × [planHeight] is
 * the stored image size: [onTap] and [overlay] work in its pixels, whatever size [file] is decoded at for the screen.
 * Taps outside the plan are ignored.
 */
@Composable
fun PlanView(
    file: File,
    planWidth: Int,
    planHeight: Int,
    modifier: Modifier = Modifier,
    onTap: ((x: Float, y: Float) -> Unit)? = null,
    overlay: DrawScope.(PlanViewport) -> Unit = {},
) {
    val image = rememberPlanBitmap(file)
    var viewport by remember(planWidth, planHeight) { mutableStateOf<PlanViewport?>(null) }
    val tap by rememberUpdatedState(onTap)
    Box(
        modifier
            .onSizeChanged { size ->
                if (size.width > 0 && size.height > 0) {
                    viewport = viewport?.copy(containerWidth = size.width.toFloat(), containerHeight = size.height.toFloat())
                        ?: PlanViewport(size.width.toFloat(), size.height.toFloat(), planWidth.toFloat(), planHeight.toFloat())
                }
            }
            .pointerInput(planWidth, planHeight) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    viewport = viewport?.transformed(centroid.x, centroid.y, pan.x, pan.y, zoom)
                }
            }
            .pointerInput(planWidth, planHeight) {
                detectTapGestures(
                    onDoubleTap = { viewport = viewport?.reset() },
                    onTap = { offset ->
                        val v = viewport ?: return@detectTapGestures
                        val (x, y) = v.toPlan(offset.x, offset.y)
                        if (v.onPlan(x, y)) tap?.invoke(x, y)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        when (val state = image) {
            PlanBitmapState.Loading -> CircularProgressIndicator()
            PlanBitmapState.Failed -> Text(stringResource(R.string.plan_image_unreadable), color = MaterialTheme.colorScheme.error)
            is PlanBitmapState.Ready -> Canvas(Modifier.fillMaxSize()) {
                val v = viewport ?: return@Canvas
                drawImage(
                    state.bitmap,
                    dstOffset = IntOffset(v.left.roundToInt(), v.top.roundToInt()),
                    dstSize = IntSize((planWidth * v.scale).roundToInt(), (planHeight * v.scale).roundToInt()),
                    filterQuality = FilterQuality.Medium,
                )
                overlay(v)
            }
        }
    }
}
