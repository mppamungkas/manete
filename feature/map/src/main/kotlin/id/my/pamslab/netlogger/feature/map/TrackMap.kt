package id.my.pamslab.netlogger.feature.map

import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
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

/**
 * MapLibre map with the session track: a thin gray line through all fixes, one dot per tick colored by [metric],
 * event markers and the selected tick. Tiles: OpenStreetMap standard raster, used per the OSM tile usage policy
 * (visible attribution, MapLibre's User-Agent names the app, only MapLibre's own cache, no prefetching).
 *
 * [fitKey] changes when the camera should frame the whole track (new session); with [follow] the camera keeps the
 * newest point in view while a session records.
 */
@Composable
internal fun TrackMap(
    track: Track?,
    metric: Metric,
    selectedSeq: Long?,
    fitKey: Any?,
    follow: Boolean,
    onPointClick: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val paddingPx = with(LocalDensity.current) { FIT_PADDING_DP.dp.roundToPx() }
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    val clicks by rememberUpdatedState(onPointClick)

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
            m.uiSettings.isAttributionEnabled = false // shown as text by the screen, as OSM requires
            m.uiSettings.isRotateGesturesEnabled = false
            m.setStyle(Style.Builder().fromJson(OSM_STYLE)) { s ->
                s.addSource(GeoJsonSource(SRC_LINE))
                s.addSource(GeoJsonSource(SRC_POINTS))
                s.addSource(GeoJsonSource(SRC_EVENTS))
                s.addSource(GeoJsonSource(SRC_SELECTED))
                s.addLayer(LineLayer(LAYER_LINE, SRC_LINE).withProperties(lineColor("#616161"), lineWidth(1.5f)))
                s.addLayer(
                    CircleLayer(LAYER_POINTS, SRC_POINTS).withProperties(
                        circleColor(get("color")), circleRadius(4.5f), circleStrokeColor("#212121"), circleStrokeWidth(0.5f),
                    ),
                )
                s.addLayer(
                    CircleLayer(LAYER_EVENTS, SRC_EVENTS).withProperties(
                        circleColor("#000000"), circleRadius(7f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f),
                    ),
                )
                s.addLayer(
                    CircleLayer(LAYER_SELECTED, SRC_SELECTED).withProperties(
                        circleColor(get("color")), circleRadius(9f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f),
                    ),
                )
                style = s
            }
            m.addOnMapClickListener { latLng ->
                val p = m.projection.toScreenLocation(latLng)
                val hit = m.queryRenderedFeatures(RectF(p.x - TAP_PX, p.y - TAP_PX, p.x + TAP_PX, p.y + TAP_PX), LAYER_POINTS)
                    .mapNotNull { it.getNumberProperty("seq")?.toLong() }
                    .maxOrNull()
                clicks(hit)
                true
            }
            map = m
        }
    }

    LaunchedEffect(style, track, metric) {
        val s = style ?: return@LaunchedEffect
        val points = track?.points.orEmpty()
        s.getSourceAs<GeoJsonSource>(SRC_LINE)?.setGeoJson(
            if (points.size < 2) FeatureCollection.fromFeatures(emptyList())
            else FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(points.map { Point.fromLngLat(it.lon, it.lat) }))),
        )
        s.getSourceAs<GeoJsonSource>(SRC_POINTS)?.setGeoJson(
            FeatureCollection.fromFeatures(
                points.map { p ->
                    Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply {
                        addStringProperty("color", metric.hex(p))
                        addNumberProperty("seq", p.seq)
                    }
                },
            ),
        )
        s.getSourceAs<GeoJsonSource>(SRC_EVENTS)?.setGeoJson(
            FeatureCollection.fromFeatures(
                track?.events.orEmpty().mapNotNull { e ->
                    val lat = e.lat ?: return@mapNotNull null
                    val lon = e.lon ?: return@mapNotNull null
                    Feature.fromGeometry(Point.fromLngLat(lon, lat))
                },
            ),
        )
    }

    LaunchedEffect(style, track, metric, selectedSeq) {
        val s = style ?: return@LaunchedEffect
        val p = track?.points?.firstOrNull { it.seq == selectedSeq }
        s.getSourceAs<GeoJsonSource>(SRC_SELECTED)?.setGeoJson(
            FeatureCollection.fromFeatures(
                listOfNotNull(p?.let { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)).apply { addStringProperty("color", metric.hex(it)) } }),
            ),
        )
    }

    // Frame the whole track once per fitKey, as soon as it has points.
    var fittedKey by remember { mutableStateOf<Any?>(Unit) }
    LaunchedEffect(map, fitKey, track?.points?.isNotEmpty()) {
        val m = map ?: return@LaunchedEffect
        val points = track?.points.orEmpty()
        if (points.isEmpty() || fittedKey == fitKey) return@LaunchedEffect
        fittedKey = fitKey
        val latLngs = points.map { LatLng(it.lat, it.lon) }
        val spanTiny = latLngs.maxOf { it.latitude } - latLngs.minOf { it.latitude } < MIN_SPAN_DEG &&
            latLngs.maxOf { it.longitude } - latLngs.minOf { it.longitude } < MIN_SPAN_DEG
        if (spanTiny) {
            m.cameraPosition = CameraPosition.Builder().target(latLngs.last()).zoom(SINGLE_POINT_ZOOM).build()
        } else {
            m.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(latLngs).build(), paddingPx))
        }
    }

    LaunchedEffect(map, follow, track?.points?.lastOrNull()) {
        val m = map ?: return@LaunchedEffect
        val last = track?.points?.lastOrNull() ?: return@LaunchedEffect
        if (follow && fittedKey == fitKey) m.animateCamera(CameraUpdateFactory.newLatLng(LatLng(last.lat, last.lon)))
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private const val FIT_PADDING_DP = 48
private const val TAP_PX = 24f
private const val MIN_SPAN_DEG = 0.0005
private const val SINGLE_POINT_ZOOM = 16.0

private const val SRC_LINE = "track-line"
private const val SRC_POINTS = "track-points"
private const val SRC_EVENTS = "events"
private const val SRC_SELECTED = "selected"
private const val LAYER_LINE = "track-line"
private const val LAYER_POINTS = "track-points"
private const val LAYER_EVENTS = "events"
private const val LAYER_SELECTED = "selected"

// OpenStreetMap standard tiles (https://operations.osmfoundation.org/policies/tiles/); max zoom 19.
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
