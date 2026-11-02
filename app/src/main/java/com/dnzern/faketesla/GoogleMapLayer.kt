package com.dnzern.faketesla

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.google.android.gms.maps.model.LatLng as GLatLng

fun LatLng.g(): GLatLng = GLatLng(lat, lng)

/** Google Maps with live traffic. Needs "Maps SDK for Android" enabled for the built-in key. */
class GoogleMapController : MapCtl {
    override val idle = MutableStateFlow<Pair<LatLng, Double>?>(null)
    override val ready = MutableStateFlow(false)
    override var onChargerTap: (GoogleApi.Charger) -> Unit = {}

    val plan = MutableStateFlow<Plan?>(null)
    val chargers = MutableStateFlow<List<GoogleApi.Charger>>(emptyList())
    val padTopPx = MutableStateFlow(0)
    val padBottomPx = MutableStateFlow(0)

    private var cam: CameraPositionState? = null
    private var scope: CoroutineScope? = null

    fun attach(c: CameraPositionState, s: CoroutineScope) {
        cam = c
        scope = s
    }

    override fun showPlan(plan: Plan?) {
        this.plan.value = plan
        if (plan == null) {
            padTopPx.value = 0
            padBottomPx.value = 0
        }
    }

    override fun showChargers(list: List<GoogleApi.Charger>) {
        chargers.value = list
    }

    override fun showMe(p: LatLng?) {
        // Google draws the blue location dot itself.
    }

    override fun moveTo(p: LatLng, zoom: Double, animate: Boolean) {
        val c = cam ?: return
        val u = CameraUpdateFactory.newLatLngZoom(p.g(), zoom.toFloat())
        scope?.launch {
            try {
                if (animate) c.animate(u, 800) else c.move(u)
            } catch (_: Exception) {
            }
        }
    }

    override fun fit(points: List<LatLng>, topPx: Int, bottomPx: Int, sidePx: Int) {
        val c = cam ?: return
        if (points.size < 2) return
        padTopPx.value = topPx
        padBottomPx.value = bottomPx
        val b = LatLngBounds.builder()
        for (p in Geo.thin(points, 300)) b.include(p.g())
        scope?.launch {
            try {
                c.animate(CameraUpdateFactory.newLatLngBounds(b.build(), sidePx), 900)
            } catch (_: Exception) {
            }
        }
    }
}

@Composable
fun GoogleMapLayer(ctl: GoogleMapController) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val cam = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(GLatLng(41.015, 28.979), 10f)
    }
    LaunchedEffect(cam) { ctl.attach(cam, scope) }

    val plan by ctl.plan.collectAsState()
    val chargers by ctl.chargers.collectAsState()
    val padTop by ctl.padTopPx.collectAsState()
    val padBottom by ctl.padBottomPx.collectAsState()
    val location by ObdManager.location.collectAsState()
    val hasPermission = location != null || hasLocationPermission(context)

    LaunchedEffect(cam.isMoving) {
        if (!cam.isMoving) {
            val t = cam.position.target
            ctl.idle.value = LatLng(t.latitude, t.longitude) to cam.position.zoom.toDouble()
        }
    }

    val style = remember {
        try {
            MapStyleOptions.loadRawResourceStyle(context, R.raw.map_style)
        } catch (_: Exception) {
            null
        }
    }

    GoogleMap(
        modifier = Modifier.fillMaxSize(),
        cameraPositionState = cam,
        properties = MapProperties(
            isMyLocationEnabled = hasPermission && hasLocationPermission(context),
            isTrafficEnabled = true,
            mapStyleOptions = style
        ),
        uiSettings = MapUiSettings(
            zoomControlsEnabled = false,
            myLocationButtonEnabled = false,
            mapToolbarEnabled = false,
            compassEnabled = false
        ),
        contentPadding = with(density) {
            PaddingValues(top = padTop.toDp(), bottom = padBottom.toDp().coerceAtLeast(150.dp))
        },
        onMapLoaded = { ctl.ready.value = true }
    ) {
        val p = plan
        if (p != null) {
            val pts = remember(p) { Geo.thin(p.routePts, 3000).map { it.g() } }
            Polyline(points = pts, color = Color(0xFF163A78), width = 26f)
            Polyline(points = pts, color = Color(0xFF4C8DFF), width = 14f)
            p.stops.forEachIndexed { i, s ->
                Marker(
                    state = MarkerState(position = s.charger.loc.g()),
                    title = "${i + 1}. ${s.charger.name}",
                    snippet = "%${s.arriveSoc} → %${s.departSoc} · ${s.chargeMin.roundToInt()} dk",
                    icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)
                )
            }
            val end = pts.lastOrNull()
            if (end != null) {
                Marker(
                    state = MarkerState(position = end),
                    title = p.destName,
                    icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)
                )
            }
        } else {
            for (c in chargers) {
                Marker(
                    state = MarkerState(position = c.loc.g()),
                    title = c.name,
                    icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN),
                    onClick = {
                        ctl.onChargerTap(c)
                        true
                    }
                )
            }
        }
    }
}
