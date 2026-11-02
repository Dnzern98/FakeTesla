package com.dnzern.faketesla

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val Muted = Color(0xFF9AA4AE)
private val Good = Color(0xFF4CAF50)
private val Warn = Color(0xFFFFB74D)
private val Bad = Color(0xFFEF5350)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppScreen(
                        onShare = { shareLog() },
                        onOpenMaps = { openInGoogleMaps(it) }
                    )
                }
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
        val f = ObdManager.logFile.value ?: return
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

    /** Hands the route to Google Maps with the charging stops as waypoints. */
    private fun openInGoogleMaps(plan: Plan) {
        val dest = plan.routePts.lastOrNull() ?: return
        val sb = StringBuilder("https://www.google.com/maps/dir/?api=1&travelmode=driving")
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
                ObdManager.log("Harita açılamadı: ${e2.message}")
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

fun fmt(v: Double?, decimals: Int, unit: String): String =
    if (v == null) "—" else String.format("%.${decimals}f %s", v, unit)

fun duration(min: Double): String {
    val m = min.roundToInt()
    return if (m >= 60) "${m / 60} sa ${m % 60} dk" else "$m dk"
}

private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())

@Composable
fun AppScreen(onShare: () -> Unit, onOpenMaps: (Plan) -> Unit) {
    var tab by remember { mutableIntStateOf(if (Prefs.apiKey.isBlank()) 2 else 0) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { ObdManager.startLocation() }
    LaunchedEffect(Unit) { launcher.launch(requiredPermissions()) }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Rota") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Araç") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Ayarlar") })
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (tab) {
                0 -> RouteTab(onOpenMaps = onOpenMaps, goToSettings = { tab = 2 })
                1 -> CarTab(onShare = onShare, requestPermissions = { launcher.launch(requiredPermissions()) })
                else -> SettingsTab()
            }
        }
    }
}

// =====================================================================================
// Route tab
// =====================================================================================

@Composable
fun RouteTab(onOpenMaps: (Plan) -> Unit, goToSettings: () -> Unit) {
    val plan by TripRepo.plan.collectAsState()
    val busy by TripRepo.busy.collectAsState()
    val progress by TripRepo.progress.collectAsState()
    val error by TripRepo.error.collectAsState()
    val values by ObdManager.values.collectAsState()
    val connected by ObdManager.connected.collectAsState()

    var origin by remember { mutableStateOf<PlaceRef?>(null) }
    var dest by remember { mutableStateOf(TripRepo.lastDest) }
    var manualSoc by remember { mutableFloatStateOf(Prefs.manualSoc.toFloat()) }

    if (Prefs.apiKey.isBlank()) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Google API anahtarı gerekli", fontWeight = FontWeight.Bold)
                Text("Adres arama ve rota için Ayarlar'a Google Maps API anahtarını gir.", color = Muted)
                Button(onClick = goToSettings) { Text("Ayarlara git") }
            }
        }
        return
    }

    PlaceField(
        label = "Nereden",
        placeholder = "Boş bırakırsan: şu anki konumun",
        initial = origin?.name ?: "",
        onSelected = { origin = it }
    )
    PlaceField(
        label = "Nereye",
        placeholder = "Adres veya yer adı yaz",
        initial = dest?.name ?: "",
        onSelected = { dest = it }
    )

    val liveSoc = values["bms_soc"] ?: values["soc"]
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            if (connected && liveSoc != null) {
                Text("Başlangıç şarjı: %${liveSoc.roundToInt()} (araçtan canlı)", fontWeight = FontWeight.Bold)
            } else {
                Text("Başlangıç şarjı: %${manualSoc.roundToInt()}", fontWeight = FontWeight.Bold)
                Slider(
                    value = manualSoc,
                    onValueChange = { manualSoc = it },
                    onValueChangeFinished = { Prefs.manualSoc = manualSoc.roundToInt() },
                    valueRange = 5f..100f
                )
                Text("Araç bağlanınca gerçek değer kullanılır.", color = Muted, fontSize = 12.sp)
            }
        }
    }

    val d = dest
    Button(
        onClick = {
            if (d != null) {
                val soc = if (connected && liveSoc != null) liveSoc else manualSoc.toDouble()
                TripRepo.planTrip(origin, d, soc)
            }
        },
        enabled = d != null && !busy,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(if (busy) "Planlanıyor…" else "Rotayı planla")
    }

    if (busy) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(progress, color = Muted)
    }
    error?.let { Text(it, color = Bad) }

    plan?.let { p ->
        PlanView(p, onOpenMaps = onOpenMaps, onReplan = {
            val last = TripRepo.lastDest
            if (last != null) {
                val soc = if (connected && liveSoc != null) liveSoc else manualSoc.toDouble()
                TripRepo.planTrip(null, last, soc)
            }
        })
    }
}

@Composable
fun PlaceField(label: String, placeholder: String, initial: String, onSelected: (PlaceRef?) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    var editing by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<GoogleApi.Suggestion>>(emptyList()) }
    var searchError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(text, editing) {
        if (!editing || text.trim().length < 3) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(350L)
        try {
            suggestions = withContext(Dispatchers.IO) {
                GoogleApi.autocomplete(text.trim(), ObdManager.currentLatLng())
            }
            searchError = null
        } catch (e: Exception) {
            suggestions = emptyList()
            searchError = "Arama hatası: ${e.message}"
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                editing = true
                onSelected(null)
            },
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        searchError?.let { Text(it, color = Bad, fontSize = 12.sp) }
        for (s in suggestions) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        text = s.main
                        editing = false
                        suggestions = emptyList()
                        val full = if (s.secondary.isNotBlank()) "${s.main}, ${s.secondary}" else s.main
                        onSelected(PlaceRef(full, s.placeId, null))
                    }
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text(s.main, fontWeight = FontWeight.Bold)
                    if (s.secondary.isNotBlank()) Text(s.secondary, color = Muted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun PlanView(p: Plan, onOpenMaps: (Plan) -> Unit, onReplan: () -> Unit) {
    p.map?.let { bmp ->
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "Rota haritası",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth()
        )
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Varış ${clock.format(Date(p.arrivalAtMs))}", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(
                "Varışta batarya: %${p.arrivalSoc}",
                fontSize = 18.sp,
                color = if (p.arrivalSoc >= 15) Good else Warn
            )
            Text(p.destName, color = Muted)
            Spacer(Modifier.width(4.dp))
            DataRow("Mesafe", fmt(p.totalKm, 0, "km"))
            DataRow("Toplam süre", duration(p.totalMin))
            DataRow("Sürüş", duration(p.driveMin))
            DataRow("Şarj", if (p.stops.isEmpty()) "Gerekmiyor" else "${p.stops.size} durak · ${duration(p.chargeMin)}")
        }
    }

    Text("Yolculuk", fontWeight = FontWeight.Bold)
    TimelineItem(
        title = "Başlangıç",
        detail = "Batarya %${p.startSoc}",
        color = Good
    )
    p.stops.forEachIndexed { i, s ->
        val power = if (s.charger.maxKw > 0) "${s.charger.maxKw.roundToInt()} kW" else "güç bilinmiyor (~${s.stationKw.roundToInt()} kW varsayıldı)"
        TimelineItem(
            title = "${i + 1}. şarj · ${s.charger.name}",
            detail = "${clock.format(Date(s.arriveAtMs))} varış · km ${s.alongKm.roundToInt()}\n" +
                "%${s.arriveSoc} → %${s.departSoc} · ${s.chargeMin.roundToInt()} dk · $power\n" +
                s.charger.address,
            color = Warn
        )
    }
    TimelineItem(
        title = "Varış · ${clock.format(Date(p.arrivalAtMs))}",
        detail = "Batarya %${p.arrivalSoc}\n${p.destName}",
        color = Bad
    )

    Button(onClick = { onOpenMaps(p) }, modifier = Modifier.fillMaxWidth()) {
        Text("Google Haritalar'da başlat")
    }
    OutlinedButton(onClick = onReplan, modifier = Modifier.fillMaxWidth()) {
        Text("Şu anki konum ve şarjla yeniden planla")
    }

    val notes = ArrayList<String>()
    if (!p.usedElevation) notes.add("Yükseklik verisi alınamadı, yol düz kabul edildi.")
    if (p.stops.isNotEmpty() || p.chargersFound > 0) notes.add("Rota üzerinde ${p.chargersFound} uygun istasyon değerlendirildi.")
    notes.add("Tüketim: ${fmt(EnergyModel.whPerKm(90.0), 0, "Wh/km")} @90 km/sa, ${fmt(EnergyModel.whPerKm(120.0), 0, "Wh/km")} @120 km/sa (model).")
    for (n in notes) Text(n, color = Muted, fontSize = 12.sp)
}

@Composable
fun TimelineItem(title: String, detail: String, color: Color) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Spacer(
                Modifier
                    .padding(top = 4.dp)
                    .size(12.dp)
                    .background(color, CircleShape)
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, fontWeight = FontWeight.Bold, color = color)
                Text(detail, fontSize = 13.sp)
            }
        }
    }
}

// =====================================================================================
// Car tab
// =====================================================================================

@Composable
fun CarTab(onShare: () -> Unit, requestPermissions: () -> Unit) {
    val status by ObdManager.status.collectAsState()
    val connected by ObdManager.connected.collectAsState()
    val scanning by ObdManager.scanning.collectAsState()
    val devices by ObdManager.devices.collectAsState()
    val values by ObdManager.values.collectAsState()
    val speed by ObdManager.speedKmh.collectAsState()
    val tripKm by ObdManager.tripKm.collectAsState()
    val tripWh by ObdManager.tripWh.collectAsState()
    val logLines by ObdManager.logLines.collectAsState()
    val logFile by ObdManager.logFile.collectAsState()
    val invert by ObdManager.invertCurrent.collectAsState()
    var command by remember { mutableStateOf("") }

    Text(status, color = if (connected) Good else Muted)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!connected) {
            Button(onClick = {
                requestPermissions()
                ObdManager.startScan()
            }, enabled = !scanning) {
                Text(if (scanning) "Aranıyor…" else "Cihaz ara")
            }
        } else {
            OutlinedButton(onClick = { ObdManager.disconnect() }) { Text("Bağlantıyı kes") }
        }
        OutlinedButton(onClick = onShare, enabled = logFile != null) { Text("Kaydı paylaş") }
    }

    if (!connected && devices.isNotEmpty()) {
        Text("Bulunan cihazlar (adaptörüne dokun):", color = Muted)
        for (d in devices) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { ObdManager.connect(d) }
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        d.name,
                        fontWeight = FontWeight.Bold,
                        color = if (ObdManager.looksLikeObd(d.name)) Good else Color.Unspecified
                    )
                    Text("${d.device.address} · ${d.rssi} dBm", color = Muted, fontSize = 12.sp)
                }
            }
        }
    }

    val soc = values["bms_soc"] ?: values["soc"]
    val consumption = if (tripKm > 0.5) tripWh / tripKm else null
    val usedWh = if (consumption != null && consumption > 60 && consumption < 400) consumption else EC3.DEFAULT_WH_PER_KM
    val range = soc?.let { EC3.USABLE_KWH * it / 100.0 * 1000.0 / usedWh }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Batarya", color = Muted)
            Text(fmt(soc, 1, "%"), fontSize = 48.sp, fontWeight = FontWeight.Bold)
            Text("Tahmini menzil: " + fmt(range, 0, "km"), fontSize = 18.sp)
            Text(
                "Hesapta kullanılan tüketim: " + fmt(usedWh, 0, "Wh/km") +
                    if (consumption == null) " (varsayılan)" else " (bu sürüş)",
                color = Muted,
                fontSize = 13.sp
            )
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DataRow("Şarj (gösterge)", fmt(values["soc"], 1, "%"))
            DataRow("Şarj (BMS)", fmt(values["bms_soc"], 2, "%"))
            DataRow("Voltaj", fmt(values["volt"], 1, "V"))
            DataRow("Akım", fmt(values["amp"], 1, "A"))
            DataRow("Güç", fmt(values["kw"], 1, "kW"))
            DataRow("Hız (GPS)", fmt(speed, 0, "km/sa"))
            DataRow("Bu sürüş mesafe", fmt(tripKm, 2, "km"))
            DataRow("Bu sürüş enerji", fmt(tripWh, 0, "Wh"))
            DataRow("Bu sürüş tüketim", fmt(consumption, 0, "Wh/km"))
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = invert, onCheckedChange = { ObdManager.setInvertCurrent(it) })
        Spacer(Modifier.width(8.dp))
        Text("Akım işaretini ters çevir (sürerken güç eksi görünüyorsa aç)", fontSize = 13.sp)
    }

    Text("Komut gönder", fontWeight = FontWeight.Bold)
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = command,
            onValueChange = { command = it },
            label = { Text("ör. 224801 veya ATSH7E7") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = { ObdManager.sendRaw(command) }, enabled = connected) { Text("Gönder") }
    }

    Text("Günlük", fontWeight = FontWeight.Bold)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF101418))
            .padding(8.dp)
    ) {
        val lines = logLines.takeLast(80).reversed()
        if (lines.isEmpty()) Text("Henüz kayıt yok", color = Muted, fontSize = 12.sp)
        for (line in lines) {
            Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFFB0BEC5))
        }
    }
}

// =====================================================================================
// Settings tab
// =====================================================================================

@Composable
fun SettingsTab() {
    var key by remember { mutableStateOf(Prefs.apiKey) }
    var reserve by remember { mutableFloatStateOf(Prefs.reservePct.toFloat()) }
    var arrive by remember { mutableFloatStateOf(Prefs.chargerArrivePct.toFloat()) }
    var maxCharge by remember { mutableFloatStateOf(Prefs.maxChargePct.toFloat()) }
    var minKw by remember { mutableFloatStateOf(Prefs.minChargerKw.toFloat()) }
    var factor by remember { mutableFloatStateOf(Prefs.consumptionFactor) }
    var saved by remember { mutableStateOf(false) }

    Text("Google Maps API anahtarı", fontWeight = FontWeight.Bold)
    OutlinedTextField(
        value = key,
        onValueChange = {
            key = it
            saved = false
        },
        label = { Text("AIza… ile başlar") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        "Google Cloud'da şu API'ler açık olmalı: Places API (New), Routes API, Elevation API, Maps Static API.",
        color = Muted,
        fontSize = 12.sp
    )

    Text("Planlama", fontWeight = FontWeight.Bold)
    SettingSlider("Hedefe varışta en az batarya", reserve, 5f..30f, "%${reserve.roundToInt()}") { reserve = it; saved = false }
    SettingSlider("Şarj istasyonuna varışta en az", arrive, 3f..30f, "%${arrive.roundToInt()}") { arrive = it; saved = false }
    SettingSlider("Yolda en fazla şarj et", maxCharge, 60f..100f, "%${maxCharge.roundToInt()}") { maxCharge = it; saved = false }
    SettingSlider("En düşük istasyon gücü", minKw, 20f..150f, "${minKw.roundToInt()} kW") { minKw = it; saved = false }
    SettingSlider(
        "Tüketim katsayısı (sen daha çok harcıyorsan artır)",
        factor,
        0.7f..1.5f,
        String.format(Locale.US, "x%.2f", factor)
    ) { factor = it; saved = false }
    Text(
        "Model: ${fmt(EnergyModel.whPerKm(90.0) / Prefs.consumptionFactor * factor, 0, "Wh/km")} @90 km/sa, " +
            "${fmt(EnergyModel.whPerKm(120.0) / Prefs.consumptionFactor * factor, 0, "Wh/km")} @120 km/sa",
        color = Muted,
        fontSize = 12.sp
    )

    Button(onClick = {
        Prefs.apiKey = key
        Prefs.reservePct = reserve.roundToInt()
        Prefs.chargerArrivePct = arrive.roundToInt()
        Prefs.maxChargePct = maxCharge.roundToInt()
        Prefs.minChargerKw = (minKw / 5f).roundToInt() * 5
        Prefs.consumptionFactor = (factor * 100f).roundToInt() / 100f
        saved = true
    }, modifier = Modifier.fillMaxWidth()) {
        Text(if (saved) "Kaydedildi ✓" else "Kaydet")
    }
}

@Composable
fun SettingSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onChange: (Float) -> Unit
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(valueText, fontWeight = FontWeight.Bold)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
fun DataRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted)
        Text(value, fontWeight = FontWeight.Medium)
    }
}
