package id.my.pamslab.netlogger.core.ui.plan

/**
 * Where a floor plan is drawn in its view: fitted into the container at [zoom] 1, zoomed up to [MAX_ZOOM] and panned.
 * Plan coordinates are pixels of the stored image (the unit of waypoints and the scale), whatever size the bitmap on
 * screen was decoded at. Pure, so taps and drawing agree and are unit-tested.
 */
data class PlanViewport(
    val containerWidth: Float,
    val containerHeight: Float,
    val imageWidth: Float,
    val imageHeight: Float,
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
) {
    /** Screen pixels per plan pixel. */
    val scale: Float get() = minOf(containerWidth / imageWidth, containerHeight / imageHeight) * zoom

    /** Top-left corner of the plan on screen. */
    val left: Float get() = (containerWidth - imageWidth * scale) / 2 + panX
    val top: Float get() = (containerHeight - imageHeight * scale) / 2 + panY

    fun toPlan(screenX: Float, screenY: Float): Pair<Float, Float> = (screenX - left) / scale to (screenY - top) / scale

    fun toScreen(planX: Float, planY: Float): Pair<Float, Float> = left + planX * scale to top + planY * scale

    fun onPlan(planX: Float, planY: Float) = planX in 0f..imageWidth && planY in 0f..imageHeight

    /**
     * A pinch or drag: zoom by [zoomChange] around [centroidX]/[centroidY] (the plan point under the fingers stays
     * there) and move by [panDeltaX]/[panDeltaY]. The plan never leaves the view: smaller than the container it stays
     * centred, larger it cannot be dragged past its edges.
     */
    fun transformed(centroidX: Float, centroidY: Float, panDeltaX: Float, panDeltaY: Float, zoomChange: Float): PlanViewport {
        val (px, py) = toPlan(centroidX, centroidY)
        val z = (zoom * zoomChange).coerceIn(1f, MAX_ZOOM)
        val zoomed = copy(zoom = z, panX = 0f, panY = 0f)
        // The left/top that keeps (px, py) under the centroid, moved by the drag.
        val wantLeft = centroidX + panDeltaX - px * zoomed.scale
        val wantTop = centroidY + panDeltaY - py * zoomed.scale
        return zoomed.copy(
            panX = clampPan(wantLeft - zoomed.left, containerWidth, imageWidth * zoomed.scale),
            panY = clampPan(wantTop - zoomed.top, containerHeight, imageHeight * zoomed.scale),
        )
    }

    /** Back to the whole plan. */
    fun reset() = copy(zoom = 1f, panX = 0f, panY = 0f)

    companion object {
        /** Zoomed in this far, a 4096 px plan shows ~200 plan pixels across a phone: room-level detail. */
        const val MAX_ZOOM = 20f

        private fun clampPan(pan: Float, container: Float, content: Float): Float {
            val limit = maxOf(0f, (content - container) / 2)
            return pan.coerceIn(-limit, limit)
        }
    }
}
