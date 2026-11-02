package com.dnzern.faketesla

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import kotlin.math.roundToInt
import com.google.android.gms.maps.model.LatLng as GLatLng

fun LatLng.g(): GLatLng = GLatLng(lat, lng)

/** The full-screen Google map behind every screen: traffic, route, charging stops, nearby chargers. */
@Composable
fun MapLayer(
    cameraState: CameraPositionState,
    plan: Plan?,
    showPlan: Boolean,
    chargers: List<GoogleApi.Charger>,
    showChargers: Boolean,
    myLocationEnabled: Boolean,
    topPadding: Dp,
    bottomPadding: Dp,
    onMapLoaded: () -> Unit,
    onChargerClick: (GoogleApi.Charger) -> Unit
) {
    val context = LocalContext.current
    val style = remember {
        try {
            MapStyleOptions.loadRawResourceStyle(context, R.raw.map_style)
        } catch (_: Exception) {
            null
        }
    }
    GoogleMap(
        modifier = Modifier.fillMaxSize(),
        cameraPositionState = cameraState,
        properties = MapProperties(
            isMyLocationEnabled = myLocationEnabled,
            isTrafficEnabled = true,
            mapStyleOptions = style
        ),
        uiSettings = MapUiSettings(
            zoomControlsEnabled = false,
            myLocationButtonEnabled = false,
            mapToolbarEnabled = false,
            compassEnabled = false
        ),
        contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding),
        onMapLoaded = onMapLoaded
    ) {
        if (showPlan && plan != null) {
            val pts = remember(plan) { plan.routePts.map { it.g() } }
            Polyline(points = pts, color = Color(0xFF163A78), width = 26f)
            Polyline(points = pts, color = Color(0xFF4C8DFF), width = 14f)
            plan.stops.forEachIndexed { i, s ->
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
                    title = plan.destName,
                    icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)
                )
            }
        } else if (showChargers) {
            for (c in chargers) {
                val power = if (c.maxKw > 0) "${c.maxKw.roundToInt()} kW" else "Güç bilgisi yok"
                Marker(
                    state = MarkerState(position = c.loc.g()),
                    title = c.name,
                    snippet = "$power · rota için dokun",
                    icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN),
                    onInfoWindowClick = { onChargerClick(c) }
                )
            }
        }
    }
}
