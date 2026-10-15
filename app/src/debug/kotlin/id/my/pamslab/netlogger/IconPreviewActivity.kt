package id.my.pamslab.netlogger

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import java.io.File
import kotlin.math.hypot

/**
 * Debug builds only: renders the launcher icon as Android draws it, checks that the foreground and monochrome layers
 * stay inside the adaptive icon's 66 dp safe zone, and writes previews to the app's external files dir
 * (icon-previews/; copied to docs/branding/previews/). Result in logcat (tag NetLogger).
 *
 * ```
 * adb shell am start -n id.my.pamslab.netlogger/.IconPreviewActivity
 * adb pull /sdcard/Android/data/id.my.pamslab.netlogger/files/icon-previews
 * ```
 */
class IconPreviewActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val out = File(getExternalFilesDir(null), "icon-previews").apply { mkdirs() }
            for ((name, id) in listOf("foreground" to R.drawable.ic_launcher_foreground, "monochrome" to R.drawable.ic_launcher_monochrome)) {
                Log.i(TAG, "icon $name: ${safeZone(id)}")
            }
            fun save(name: String, bitmap: Bitmap) =
                File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            // Full 108 dp square: background and foreground, no mask.
            save("full_square_432", composed(432, mask = null))
            // What launchers show: the central 72 dp, cut by their mask.
            save("circle_mask_432", composed(432, mask = Mask.CIRCLE))
            save("rounded_square_mask_432", composed(432, mask = Mask.ROUNDED_SQUARE))
            save("circle_mask_48px", composed(48, mask = Mask.CIRCLE))
            save("circle_mask_24px", composed(24, mask = Mask.CIRCLE))
            // Themed icon (Android 13+): the monochrome layer, tinted by the launcher (here dark on a light tint).
            save("monochrome_432", composed(432, mask = Mask.CIRCLE, monochrome = true))
            save("monochrome_48px", composed(48, mask = Mask.CIRCLE, monochrome = true))
            Log.i(TAG, "icon previews written to $out")
        } catch (e: Exception) {
            Log.e(TAG, "icon previews failed", e)
        }
        finish()
    }

    private fun drawable(id: Int): Drawable = requireNotNull(getDrawable(id))

    /** "inside" or "OUTSIDE" the safe zone, with the farthest drawn pixel's distance from the centre in dp. */
    private fun safeZone(id: Int): String {
        val size = 432 // 108 dp at 4 px/dp
        val bitmap = createBitmap(size, size)
        drawable(id).apply { setBounds(0, 0, size, size); draw(Canvas(bitmap)) }
        var farthest = 0.0
        var drawn = 0
        for (y in 0 until size) for (x in 0 until size) {
            if (Color.alpha(bitmap[x, y]) > 8) {
                drawn++
                farthest = maxOf(farthest, hypot(x + 0.5 - size / 2.0, y + 0.5 - size / 2.0))
            }
        }
        val dp = farthest * 108 / size
        return "${if (dp <= SAFE_RADIUS_DP) "inside" else "OUTSIDE"} the safe zone: farthest pixel %.2f dp from the centre (limit %.0f dp), %d px drawn"
            .format(dp, SAFE_RADIUS_DP, drawn)
    }

    private enum class Mask { CIRCLE, ROUNDED_SQUARE }

    /**
     * The icon at [visible] px. With a mask, [visible] is the launcher's 72 dp viewport (the layers drawn 1.5× larger
     * and centred, as launchers do); without one, it is the full 108 dp canvas.
     */
    private fun composed(visible: Int, mask: Mask?, monochrome: Boolean = false): Bitmap {
        val bitmap = createBitmap(visible, visible)
        val canvas = Canvas(bitmap)
        val full = if (mask == null) visible.toFloat() else visible * 108f / 72f
        val offset = (visible - full) / 2
        if (mask != null) {
            val r = visible / 2f
            canvas.clipPath(
                Path().apply {
                    when (mask) {
                        Mask.CIRCLE -> addCircle(r, r, r, Path.Direction.CW)
                        Mask.ROUNDED_SQUARE -> addRoundRect(0f, 0f, visible.toFloat(), visible.toFloat(), r * 0.45f, r * 0.45f, Path.Direction.CW)
                    }
                },
            )
        }
        canvas.translate(offset, offset)
        val bounds = full.toInt()
        if (monochrome) {
            canvas.drawColor(Color.rgb(0xD8, 0xE2, 0xFF))
            drawable(R.drawable.ic_launcher_monochrome).mutate().apply {
                colorFilter = PorterDuffColorFilter(Color.rgb(0x10, 0x2A, 0x5C), PorterDuff.Mode.SRC_IN)
                setBounds(0, 0, bounds, bounds)
                draw(canvas)
            }
        } else {
            canvas.drawColor(getColor(R.color.ic_launcher_background))
            drawable(R.drawable.ic_launcher_foreground).apply { setBounds(0, 0, bounds, bounds); draw(canvas) }
        }
        return bitmap
    }

    private companion object {
        const val TAG = "NetLogger"
        const val SAFE_RADIUS_DP = 33.0
    }
}
