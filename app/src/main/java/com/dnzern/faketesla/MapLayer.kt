package com.dnzern.faketesla

import android.graphics.PointF
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.android.geometry.LatLng as MLatLng

fun LatLng.m(): MLatLng = MLatLng(lat, lng)

/** OpenFreeMap dark vector style: free, no key, OpenStreetMap data. */
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/dark"

/** What the screens can do with a map, whichever engine draws it. */
interface MapCtl {
    /** Map centre and zoom whenever the camera stops moving. */
    val idle: MutableStateFlow<Pair<LatLng, Double>?>
    val ready: MutableStateFlow<Boolean>
    var onChargerTap: (GoogleApi.Charger) -> Unit
    fun showPlan(plan: Plan?)
    fun showChargers(list: List<GoogleApi.Charger>)
    fun showMe(p: LatLng?)
    fun moveTo(p: LatLng, zoom: Double, animate: Boolean = true)
    fun fit(points: List<LatLng>, topPx: Int, bottomPx: Int, sidePx: Int)
}

/** MapLibre + OpenFreeMap (free, no key). */
class MapController : MapCtl {
    private var map: MapLibreMap? = null
    private var routeSrc: GeoJsonSource? = null
    private var stopsSrc: GeoJsonSource? = null
    private var destSrc: GeoJsonSource? = null
    private var chargersSrc: GeoJsonSource? = null
    private var meSrc: GeoJsonSource? = null
    private var chargers: List<GoogleApi.Charger> = emptyList()

    override val idle = MutableStateFlow<Pair<LatLng, Double>?>(null)
    override val ready = MutableStateFlow(false)
    override var onChargerTap: (GoogleApi.Charger) -> Unit = {}

    fun attach(m: MapLibreMap, style: Style) {
        map = m
        val route = GeoJsonSource("route")
        val stops = GeoJsonSource("stops")
        val dest = GeoJsonSource("dest")
        val ch = GeoJsonSource("chargers")
        val me = GeoJsonSource("me")
        style.addSource(route)
        style.addSource(ch)
        style.addSource(stops)
        style.addSource(dest)
        style.addSource(me)
        style.addLayer(
            LineLayer("route-casing", "route").withProperties(
                PropertyFactory.lineColor("#163A78"),
                PropertyFactory.lineWidth(11f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
        style.addLayer(
            LineLayer("route-line", "route").withProperties(
                PropertyFactory.lineColor("#4C8DFF"),
                PropertyFactory.lineWidth(6f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
        style.addLayer(
            CircleLayer("chargers-layer", "chargers").withProperties(
                PropertyFactory.circleColor("#2FBF71"),
                PropertyFactory.circleRadius(7f),
                PropertyFactory.circleStrokeColor("#1A1D21"),
                PropertyFactory.circleStrokeWidth(2.5f)
            )
        )
        style.addLayer(
            CircleLayer("stops-layer", "stops").withProperties(
                PropertyFactory.circleColor("#2FBF71"),
                PropertyFactory.circleRadius(10f),
                PropertyFactory.circleStrokeColor("#F2F4F7"),
                PropertyFactory.circleStrokeWidth(3f)
            )
        )
        style.addLayer(
            CircleLayer("dest-layer", "dest").withProperties(
                PropertyFactory.circleColor("#E5484D"),
                PropertyFactory.circleRadius(10f),
                PropertyFactory.circleStrokeColor("#F2F4F7"),
                PropertyFactory.circleStrokeWidth(3f)
            )
        )
        style.addLayer(
            CircleLayer("me-halo", "me").withProperties(
                PropertyFactory.circleColor("#4C8DFF"),
                PropertyFactory.circleOpacity(0.18f),
                PropertyFactory.circleRadius(22f)
            )
        )
        style.addLayer(
            CircleLayer("me-dot", "me").withProperties(
                PropertyFactory.circleColor("#4C8DFF"),
                PropertyFactory.circleRadius(7f),
                PropertyFactory.circleStrokeColor("#F2F4F7"),
                PropertyFactory.circleStrokeWidth(3f)
            )
        )
        routeSrc = route
        stopsSrc = stops
        destSrc = dest
        chargersSrc = ch
        meSrc = me

        m.uiSettings.isCompassEnabled = false
        m.uiSettings.isRotateGesturesEnabled = false
        m.uiSettings.isTiltGesturesEnabled = false
        m.addOnCameraIdleListener {
            val p = m.cameraPosition
            val t = p.target
            if (t != null) idle.value = LatLng(t.latitude, t.longitude) to p.zoom
        }
        m.addOnMapClickListener { point ->
            val screen: PointF = m.projection.toScreenLocation(point)
            val box = android.graphics.RectF(screen.x - 30f, screen.y - 30f, screen.x + 30f, screen.y + 30f)
            val hit = m.queryRenderedFeatures(box, "chargers-layer").firstOrNull()
            val id = hit?.getStringProperty("id")
            val c = chargers.firstOrNull { it.id == id }
            if (c != null) {
                onChargerTap(c)
                true
            } else {
                false
            }
        }
        ready.value = true
    }

    private fun points(list: List<LatLng>): FeatureCollection =
        FeatureCollection.fromFeatures(list.map { Feature.fromGeometry(Point.fromLngLat(it.lng, it.lat)) })

    private val empty: FeatureCollection get() = FeatureCollection.fromFeatures(emptyList<Feature>())

    override fun showPlan(plan: Plan?) {
        if (plan == null || plan.routePts.size < 2) {
            routeSrc?.setGeoJson(empty)
            stopsSrc?.setGeoJson(empty)
            destSrc?.setGeoJson(empty)
            return
        }
        val line = LineString.fromLngLats(Geo.thin(plan.routePts, 3000).map { Point.fromLngLat(it.lng, it.lat) })
        routeSrc?.setGeoJson(FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(line))))
        stopsSrc?.setGeoJson(points(plan.stops.map { it.charger.loc }))
        destSrc?.setGeoJson(points(listOf(plan.routePts.last())))
    }

    override fun showChargers(list: List<GoogleApi.Charger>) {
        chargers = list
        val features = list.map { c ->
            val f = Feature.fromGeometry(Point.fromLngLat(c.loc.lng, c.loc.lat))
            f.addStringProperty("id", c.id)
            f
        }
        chargersSrc?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    override fun showMe(p: LatLng?) {
        meSrc?.setGeoJson(if (p == null) empty else points(listOf(p)))
    }

    override fun moveTo(p: LatLng, zoom: Double, animate: Boolean) {
        val m = map ?: return
        val u = CameraUpdateFactory.newLatLngZoom(p.m(), zoom)
        if (animate) m.animateCamera(u, 800) else m.moveCamera(u)
    }

    /** Fits all points, keeping clear of the top bar and the bottom sheet (in pixels). */
    override fun fit(points: List<LatLng>, topPx: Int, bottomPx: Int, sidePx: Int) {
        val m = map ?: return
        if (points.size < 2) return
        val b = LatLngBounds.Builder()
        for (p in Geo.thin(points, 300)) b.include(p.m())
        try {
            m.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), sidePx, topPx, sidePx, bottomPx), 900)
        } catch (_: Exception) {
        }
    }
}

/** The full-screen map behind every screen. */
@Composable
fun MapLayer(controller: MapController) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapView(context).also { it.onCreate(null) }
    }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->
                controller.attach(m, style)
            }
        }
    }

    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
}
