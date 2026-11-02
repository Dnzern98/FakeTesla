package com.dnzern.faketesla

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Pal.Blue, background = Pal.Bg, surface = Pal.Surface)) {
                Root(onShare = { shareLog() }, onOpenMaps = { openInGoogleMaps(it) })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ObdManager.startLocation()
    }

    override fun onStop() {
        super.onStop()
        ObdManager.stopLocationIfIdle()
    }

    private fun shareLog() {
        val f = ObdManager.logFile.value ?: run {
            ObdManager.log("Henüz sürüş kaydı yok")
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Sürüş kaydını paylaş"))
        } catch (e: Exception) {
            ObdManager.log("Paylaşılamadı: ${e.message}")
        }
    }

    /** Hands the route to Google Maps navigation with the charging stops as waypoints. */
    private fun openInGoogleMaps(plan: Plan) {
        val dest = plan.routePts.lastOrNull() ?: return
        val sb = StringBuilder("https://www.google.com/maps/dir/?api=1&travelmode=driving&dir_action=navigate")
        sb.append("&destination=").append(Uri.encode(dest.str()))
        if (plan.stops.isNotEmpty()) {
            sb.append("&waypoints=").append(Uri.encode(plan.stops.joinToString("|") { it.charger.loc.str() }))
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(sb.toString()))
        try {
            startActivity(Intent(intent).setPackage("com.google.android.apps.maps"))
        } catch (e: Exception) {
            try {
                startActivity(intent)
            } catch (e2: Exception) {
                ObdManager.log("Google Haritalar açılamadı: ${e2.message}")
            }
        }
    }
}

fun requiredPermissions(): Array<String> {
    val list = ArrayList<String>()
    list.add(Manifest.permission.ACCESS_FINE_LOCATION)
    list.add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        list.add(Manifest.permission.BLUETOOTH_SCAN)
        list.add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        list.add(Manifest.permission.POST_NOTIFICATIONS)
    }
    return list.toTypedArray()
}

fun hasLocationPermission(c: Context): Boolean =
    ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

enum class Screen { Home, Search, Plan, Car, Menu }

enum class SearchMode { Trip, SetHome, SetWork }

/** Battery % to plan with: live from the car when connected, otherwise the manual value. */
fun planningSoc(): Double =
    (if (ObdManager.connected.value) ObdManager.socNow() else null) ?: Prefs.manualSoc.toDouble()

@Composable
fun Root(onShare: () -> Unit, onOpenMaps: (Plan) -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current

    var screen by remember { mutableStateOf(Screen.Home) }
    var searchMode by remember { mutableStateOf(SearchMode.Trip) }
    var searchReturn by remember { mutableStateOf(Screen.Home) }
    var selected by remember { mutableStateOf<GoogleApi.Charger?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        ObdManager.startLocation()
    }
    LaunchedEffect(Unit) { launcher.launch(requiredPermissions()) }

    val plan by TripRepo.plan.collectAsState()
    val location by ObdManager.location.collectAsState()

    // Google map when the built-in key is there and Google is switched on; otherwise the free map.
    val googleOn = remember(screen) { Providers.google && BuildConfig.MAPS_API_KEY.isNotBlank() }
    val controller: MapCtl = remember(googleOn) { if (googleOn) GoogleMapController() else MapController() }
    val ready by controller.ready.collectAsState()
    val idle by controller.idle.collectAsState()
    var centeredOnce by remember(controller) { mutableStateOf(false) }
    var showChargers by remember { mutableStateOf(true) }
    var chargers by remember { mutableStateOf<List<GoogleApi.Charger>>(emptyList()) }
    var lastFetch by remember(controller) { mutableStateOf<LatLng?>(null) }

    fun refOf(c: GoogleApi.Charger) = PlaceRef(c.name, c.id.takeIf { it.isNotBlank() && !it.startsWith("osm-") }, c.loc)

    fun startPlan(origin: PlaceRef?, dest: PlaceRef) {
        selected = null
        TripRepo.planTrip(origin, dest, planningSoc())
        screen = Screen.Plan
    }

    controller.onChargerTap = { c -> selected = c }

    // Start over Istanbul, then jump to the user once a position arrives.
    LaunchedEffect(ready, controller) {
        if (ready) controller.moveTo(LatLng(41.015, 28.979), 10.0, animate = false)
    }
    LaunchedEffect(ready, location, controller) {
        val l = location ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        controller.showMe(LatLng(l.latitude, l.longitude))
        if (!centeredOnce) {
            centeredOnce = true
            controller.moveTo(LatLng(l.latitude, l.longitude), 13.0)
        }
    }

    // Route, stops and destination on the map only while the plan screen is open.
    LaunchedEffect(ready, plan, screen, controller) {
        if (!ready) return@LaunchedEffect
        val p = plan
        if (screen == Screen.Plan && p != null) {
            controller.showPlan(p)
            controller.showChargers(emptyList())
            with(density) {
                controller.fit(p.routePts, 140.dp.roundToPx(), 470.dp.roundToPx(), 40.dp.roundToPx())
            }
        } else {
            controller.showPlan(null)
            controller.showChargers(if (showChargers) chargers else emptyList())
        }
    }

    // Load fast chargers around the map centre when the map stops moving.
    LaunchedEffect(idle, showChargers, screen) {
        val (c, zoom) = idle ?: return@LaunchedEffect
        if (!showChargers || screen != Screen.Home || zoom < 9.0) return@LaunchedEffect
        val radius = when {
            zoom >= 13.0 -> 6000.0
            zoom >= 11.0 -> 15000.0
            else -> 30000.0
        }
        val last = lastFetch
        if (last != null && Geo.dist(last, c) < radius / 3) return@LaunchedEffect
        delay(700L)
        try {
            chargers = withContext(Dispatchers.IO) { Providers.nearbyChargers(c, radius, Prefs.minChargerKw) }
            lastFetch = c
            if (screen == Screen.Home) controller.showChargers(chargers)
        } catch (e: Exception) {
            ObdManager.log("Yakındaki istasyonlar alınamadı: ${e.message}")
        }
    }

    BackHandler(enabled = screen != Screen.Home || selected != null) {
        if (selected != null && screen == Screen.Home) {
            selected = null
        } else {
            screen = if (screen == Screen.Search) searchReturn else Screen.Home
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Pal.Bg)
    ) {
        if (controller is GoogleMapController) GoogleMapLayer(controller) else MapLayer(controller as MapController)

        when (screen) {
            Screen.Home -> HomeOverlay(
                plan = plan,
                chargersOn = showChargers,
                selected = selected,
                onCloseSelected = { selected = null },
                onGoSelected = { c -> startPlan(null, refOf(c)) },
                onSearch = {
                    searchMode = SearchMode.Trip
                    searchReturn = Screen.Home
                    screen = Screen.Search
                },
                onMenu = { screen = Screen.Menu },
                onCar = { screen = Screen.Car },
                onShowPlan = { screen = Screen.Plan },
                onToggleChargers = {
                    showChargers = !showChargers
                    controller.showChargers(if (showChargers) chargers else emptyList())
                },
                onRecenter = {
                    val l = ObdManager.currentLatLng()
                    if (l != null) controller.moveTo(l, 14.0)
                },
                onHome = {
                    val h = Prefs.home
                    if (h != null) {
                        startPlan(null, h)
                    } else {
                        searchMode = SearchMode.SetHome
                        searchReturn = Screen.Home
                        screen = Screen.Search
                    }
                },
                onWork = {
                    val w = Prefs.work
                    if (w != null) {
                        startPlan(null, w)
                    } else {
                        searchMode = SearchMode.SetWork
                        searchReturn = Screen.Home
                        screen = Screen.Search
                    }
                },
                onNearestCharger = {
                    val here = ObdManager.currentLatLng()
                    val nearest = if (here != null) chargers.minByOrNull { Geo.dist(here, it.loc) } else chargers.firstOrNull()
                    if (nearest != null) {
                        startPlan(null, refOf(nearest))
                    } else {
                        ObdManager.log("Yakında hızlı şarj istasyonu bulunamadı")
                    }
                }
            )

            Screen.Search -> SearchScreen(
                mode = searchMode,
                onBack = { screen = searchReturn },
                onPicked = { origin, dest ->
                    when (searchMode) {
                        SearchMode.Trip -> startPlan(origin, dest)
                        SearchMode.SetHome -> {
                            Prefs.home = dest
                            if (searchReturn == Screen.Home) startPlan(null, dest) else screen = searchReturn
                        }
                        SearchMode.SetWork -> {
                            Prefs.work = dest
                            if (searchReturn == Screen.Home) startPlan(null, dest) else screen = searchReturn
                        }
                    }
                }
            )

            Screen.Plan -> PlanScreen(
                onBack = {
                    TripRepo.clear()
                    screen = Screen.Home
                },
                onStart = onOpenMaps,
                onReplan = {
                    val d = TripRepo.lastDest
                    if (d != null) TripRepo.planTrip(null, d, planningSoc())
                }
            )

            Screen.Car -> CarScreen(onBack = { screen = Screen.Home }, onShare = onShare)

            Screen.Menu -> MenuScreen(
                onBack = { screen = Screen.Home },
                onCar = { screen = Screen.Car },
                onSetHome = {
                    searchMode = SearchMode.SetHome
                    searchReturn = Screen.Menu
                    screen = Screen.Search
                },
                onSetWork = {
                    searchMode = SearchMode.SetWork
                    searchReturn = Screen.Menu
                    screen = Screen.Search
                },
                onShare = onShare
            )
        }
    }
}

@Composable
fun HomeOverlay(
    plan: Plan?,
    chargersOn: Boolean,
    selected: GoogleApi.Charger?,
    onCloseSelected: () -> Unit,
    onGoSelected: (GoogleApi.Charger) -> Unit,
    onSearch: () -> Unit,
    onMenu: () -> Unit,
    onCar: () -> Unit,
    onShowPlan: () -> Unit,
    onToggleChargers: () -> Unit,
    onRecenter: () -> Unit,
    onHome: () -> Unit,
    onWork: () -> Unit,
    onNearestCharger: () -> Unit
) {
    val connected by ObdManager.connected.collectAsState()
    val values by ObdManager.values.collectAsState()
    val soc = values["bms_soc"] ?: values["soc"]

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                        .shadow(10.dp, RoundedCornerShape(27.dp))
                        .clip(RoundedCornerShape(27.dp))
                        .background(Pal.Chip)
                        .clickable(role = Role.Button, onClick = onSearch)
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Glyph(Ic.Search, Pal.Soft)
                    Text("Nereye gidiyorsun?", color = Pal.Soft, fontSize = 17.sp)
                }
                RoundButton("Menü", size = 54.dp, onClick = onMenu) { Glyph(Ic.Menu, Pal.Text) }
            }

            Row(
                modifier = Modifier
                    .height(44.dp)
                    .shadow(8.dp, RoundedCornerShape(22.dp))
                    .clip(RoundedCornerShape(22.dp))
                    .background(Pal.Chip)
                    .clickable(role = Role.Button, onClick = onCar)
                    .padding(start = 12.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (connected && soc != null) {
                    val range = ObdManager.estimatedRangeKm()
                    BatteryGlyph((soc / 100.0).toFloat(), if (soc < 20) Pal.Warn else Pal.Green)
                    Text("%${soc.roundToInt()}", color = Pal.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(fmt(range, 0, "km"), color = Pal.Muted, fontSize = 14.sp)
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Pal.Green))
                } else {
                    BatteryGlyph(0f, Pal.Green)
                    Text(if (connected) "Veri bekleniyor" else "Araç bağlı değil", color = Pal.Muted, fontSize = 14.sp)
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Pal.Label))
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 16.dp, bottom = if (plan != null) 230.dp else 160.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RoundButton(if (chargersOn) "Şarj istasyonlarını gizle" else "Şarj istasyonlarını göster", onClick = onToggleChargers) {
                Glyph(Ic.Bolt, if (chargersOn) Pal.Green else Pal.Label)
            }
            RoundButton("Konumuma git", onClick = onRecenter) { Glyph(Ic.Locate, Pal.Blue) }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(12.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (selected != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(12.dp, RoundedCornerShape(20.dp))
                        .clip(RoundedCornerShape(20.dp))
                        .background(Pal.Chip)
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Glyph(Ic.Bolt, Pal.Green, 20.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            selected.name,
                            color = Pal.Text,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "Kapat",
                            color = Pal.Muted,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .clickable(role = Role.Button, onClick = onCloseSelected)
                                .padding(8.dp)
                        )
                    }
                    val power = when {
                        selected.maxKw > 0 -> "${selected.maxKw.roundToInt()} kW"
                        selected.dcKnown -> "Hızlı şarj (güç bilinmiyor)"
                        else -> "Güç bilgisi yok"
                    }
                    Text(
                        listOf(power, selected.address).filter { it.isNotBlank() }.joinToString(" · "),
                        color = Pal.Muted,
                        fontSize = 13.sp
                    )
                    PrimaryButton("Buraya git") { onGoSelected(selected) }
                }
            }
            if (plan != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(10.dp, RoundedCornerShape(20.dp))
                        .clip(RoundedCornerShape(20.dp))
                        .background(Pal.Chip)
                        .clickable(role = Role.Button, onClick = onShowPlan)
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Planlı rota", color = Pal.Muted, fontSize = 12.sp)
                        Text(
                            "${plan.destName} · ${clock.format(Date(plan.arrivalAtMs))} · %${plan.arrivalSoc}",
                            color = Pal.Text,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Glyph(Ic.Chevron, Pal.Muted, 18.dp)
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(12.dp, RoundedCornerShape(24.dp))
                    .clip(RoundedCornerShape(24.dp))
                    .background(Pal.Chip)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Kısayollar", color = Pal.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (connected) "Araç bağlı · canlı" else "Araç bağlı değil", color = Pal.Muted, fontSize = 13.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Shortcut("Ev", Ic.Home, Pal.Text, Modifier.weight(1f), onHome)
                    Shortcut("İş", Ic.Work, Pal.Text, Modifier.weight(1f), onWork)
                    Shortcut("Şarj", Ic.Bolt, Pal.Green, Modifier.weight(1f), onNearestCharger)
                }
            }
        }
    }
}

@Composable
fun Shortcut(label: String, icon: Ic, tint: androidx.compose.ui.graphics.Color, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Pal.Surface2)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Glyph(icon, tint, 20.dp)
        Text(label, color = Pal.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun ScreenHeader(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onBack),
            contentAlignment = Alignment.Center
        ) { Glyph(Ic.Back, Pal.Text) }
        Spacer(Modifier.width(4.dp))
        Text(title, color = Pal.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        trailing()
    }
}
