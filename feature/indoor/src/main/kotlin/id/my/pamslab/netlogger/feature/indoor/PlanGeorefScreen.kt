package id.my.pamslab.netlogger.feature.indoor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.my.pamslab.netlogger.core.data.FloorPlanSummary
import id.my.pamslab.netlogger.core.model.indoor.Georeference
import id.my.pamslab.netlogger.core.ui.plan.PlanView
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.util.Locale

/**
 * Place on map (decision 006 step 7): the plan above, the OpenStreetMap map below. Tap a point on the plan and the
 * same point on the map (two building corners, far apart); a third point checks the placement. The plan's outline is
 * drawn on the map as soon as two points are placed.
 */
@Composable
internal fun PlanGeorefScreen(onBack: () -> Unit, viewModel: PlanGeorefViewModel = hiltViewModel()) {
    val summary by viewModel.plan.collectAsStateWithLifecycle()
    val points by viewModel.points.collectAsStateWithLifecycle()
    val start by viewModel.start.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.scale_back)) }
        val s = summary
        if (s == null) {
            CircularProgressIndicator()
        } else {
            GeorefContent(s, points, start, viewModel, onBack)
        }
    }
}

@Composable
private fun ColumnScope.GeorefContent(
    summary: FloorPlanSummary,
    points: List<PointPair>,
    start: Pair<Double, Double>?,
    viewModel: PlanGeorefViewModel,
    onBack: () -> Unit,
) {
    val plan = summary.plan
    val georef = viewModel.georef(points)
    val transform = georef?.transform
    Text(stringResource(R.string.georef_title, plan.name), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.georef_info), style = MaterialTheme.typography.bodySmall)
    PlanView(
        summary.image, plan.widthPx, plan.heightPx,
        Modifier.fillMaxWidth().weight(1f).border(1.dp, MaterialTheme.colorScheme.outlineVariant),
        onTap = { x, y, _ -> viewModel.tapPlan(x, y) },
    ) { v ->
        points.forEachIndexed { i, p ->
            val x = p.x ?: return@forEachIndexed
            val y = p.y ?: return@forEachIndexed
            val (sx, sy) = v.toScreen(x.toFloat(), y.toFloat())
            drawCircle(Color.White, radius = 17f, center = Offset(sx, sy))
            drawCircle(POINT_COLORS[i], radius = 13f, center = Offset(sx, sy), style = Stroke(width = 6f))
        }
    }
    Box(Modifier.fillMaxWidth().weight(1f).border(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        PickerMap(points, georef, plan.widthPx, plan.heightPx, start, viewModel::tapMap, Modifier.fillMaxSize())
        Text(
            "© OpenStreetMap contributors",
            Modifier.align(Alignment.BottomStart).padding(4.dp).background(Color.White.copy(alpha = 0.8f)).padding(horizontal = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = Color.Black,
        )
    }
    Text(statusText(points, georef, plan.metresPerPx), style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { viewModel.save(onBack) }, enabled = transform != null && points.all { it.complete }) {
            Text(stringResource(R.string.georef_save))
        }
        OutlinedButton(onClick = viewModel::undo, enabled = points.isNotEmpty()) { Text(stringResource(R.string.georef_undo)) }
        if (plan.georef != null) {
            OutlinedButton(onClick = { viewModel.remove(onBack) }) { Text(stringResource(R.string.georef_remove)) }
        }
    }
}

@Composable
private fun statusText(points: List<PointPair>, georef: Georeference?, metresPerPx: Double?): String {
    val names = listOf("A", "B", "C")
    val next = points.lastOrNull()?.takeIf { !it.complete }
    val step = when {
        next == null && points.size < MAX_POINTS -> stringResource(R.string.georef_next, names[points.size])
        next != null && !next.onPlan -> stringResource(R.string.georef_tap_plan, names[points.lastIndex])
        next != null -> stringResource(R.string.georef_tap_map, names[points.lastIndex])
        else -> ""
    }
    val t = georef?.transform
    val result = when {
        georef != null && t == null -> stringResource(R.string.georef_too_close)
        t != null -> {
            val scale = String.format(Locale.ROOT, "%.1f", 1 / t.metresPerPx)
            // Android drops a string resource's leading space: added here.
            val calibrated = metresPerPx?.let { " " + stringResource(R.string.georef_scale_compare, String.format(Locale.ROOT, "%.1f", 1 / it)) }.orEmpty()
            val check = if (t.residualsM.size > 2) stringResource(R.string.georef_error, String.format(Locale.ROOT, "%.1f", t.rmsM)) else ""
            stringResource(R.string.georef_result, String.format(Locale.ROOT, "%.0f", t.rotationDeg), scale) + calibrated + check
        }
        else -> ""
    }
    return listOf(step, result).filter { it.isNotEmpty() }.joinToString(" ")
}

/** The OSM map (as on the Map tab, same tile policy) with the placed points and, once placed, the plan's outline. */
@Composable
private fun PickerMap(
    points: List<PointPair>,
    georef: Georeference?,
    planWidth: Int,
    planHeight: Int,
    start: Pair<Double, Double>?,
    onTap: (Double, Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var centred by remember { mutableStateOf(false) }
    val taps by rememberUpdatedState(onTap)

    DisposableEffect(lifecycle, mapView) {
        var started = false
        var resumed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { mapView.onStart(); started = true }
                Lifecycle.Event.ON_RESUME -> { mapView.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> { mapView.onPause(); resumed = false }
                Lifecycle.Event.ON_STOP -> { mapView.onStop(); started = false }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isAttributionEnabled = false // shown as text, as OSM requires
            m.uiSettings.isRotateGesturesEnabled = false
            m.addOnMapClickListener { p -> taps(p.latitude, p.longitude); true }
            m.setStyle(Style.Builder().fromJson(OSM_STYLE)) { s ->
                s.addSource(GeoJsonSource(SRC_OUTLINE))
                s.addSource(GeoJsonSource(SRC_POINTS))
                s.addLayer(LineLayer(LAYER_OUTLINE, SRC_OUTLINE).withProperties(lineColor("#37474F"), lineWidth(2.5f)))
                s.addLayer(
                    CircleLayer(LAYER_POINTS, SRC_POINTS).withProperties(
                        circleColor(get("color")), circleRadius(8f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f),
                    ),
                )
                style = s
            }
            map = m
        }
    }

    LaunchedEffect(map, start) {
        val m = map ?: return@LaunchedEffect
        if (centred) return@LaunchedEffect
        val target = start ?: return@LaunchedEffect
        m.cameraPosition = CameraPosition.Builder().target(LatLng(target.first, target.second)).zoom(START_ZOOM).build()
        centred = true
    }

    LaunchedEffect(style, points, georef) {
        val s = style ?: return@LaunchedEffect
        (s.getSource(SRC_POINTS) as? GeoJsonSource)?.setGeoJson(
            FeatureCollection.fromFeatures(
                points.mapIndexedNotNull { i, p ->
                    val lat = p.lat ?: return@mapIndexedNotNull null
                    val lon = p.lon ?: return@mapIndexedNotNull null
                    Feature.fromGeometry(Point.fromLngLat(lon, lat)).apply { addStringProperty("color", POINT_HEX[i]) }
                },
            ),
        )
        val t = georef?.transform
        val outline = if (t == null) {
            emptyList()
        } else {
            val w = planWidth.toDouble()
            val h = planHeight.toDouble()
            listOf(0.0 to 0.0, w to 0.0, w to h, 0.0 to h, 0.0 to 0.0).map { (x, y) -> t.toLatLon(x, y).let { (lat, lon) -> Point.fromLngLat(lon, lat) } }
        }
        (s.getSource(SRC_OUTLINE) as? GeoJsonSource)?.setGeoJson(
            if (outline.isEmpty()) FeatureCollection.fromFeatures(emptyList()) else FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(outline))),
        )
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

/** A, B, C: the same colour on the plan and on the map. */
private val POINT_COLORS = listOf(Color(0xFFD81B60), Color(0xFF1E88E5), Color(0xFF43A047))
private val POINT_HEX = listOf("#D81B60", "#1E88E5", "#43A047")

private const val START_ZOOM = 17.0
private const val SRC_OUTLINE = "outline"
private const val SRC_POINTS = "points"
private const val LAYER_OUTLINE = "outline-line"
private const val LAYER_POINTS = "points-circle"

/** OpenStreetMap standard raster tiles, as on the Map tab (OSM tile usage policy: attribution shown, no prefetch). */
private const val OSM_STYLE = """
{
  "version": 8,
  "sources": {
    "osm": {
      "type": "raster",
      "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
      "tileSize": 256,
      "maxzoom": 19,
      "attribution": "© OpenStreetMap contributors"
    }
  },
  "layers": [{ "id": "osm", "type": "raster", "source": "osm" }]
}
"""
