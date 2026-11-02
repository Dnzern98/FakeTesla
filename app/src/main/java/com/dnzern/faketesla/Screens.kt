package com.dnzern.faketesla

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// =====================================================================================
// Search
// =====================================================================================

private enum class Field { Origin, Dest }

@Composable
private fun fieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Pal.Surface2,
    unfocusedContainerColor = Pal.Surface2,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    focusedTextColor = Pal.Text,
    unfocusedTextColor = Pal.Text,
    cursorColor = Pal.Blue,
    focusedPlaceholderColor = Pal.Muted,
    unfocusedPlaceholderColor = Pal.Muted
)

@Composable
fun SearchScreen(mode: SearchMode, onBack: () -> Unit, onPicked: (PlaceRef?, PlaceRef) -> Unit) {
    var origin by remember { mutableStateOf<PlaceRef?>(null) }
    var originText by remember { mutableStateOf("") }
    var destText by remember { mutableStateOf("") }
    var active by remember { mutableStateOf(Field.Dest) }
    var suggestions by remember { mutableStateOf<List<Models.Suggestion>>(emptyList()) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var manualSoc by remember { mutableFloatStateOf(Prefs.manualSoc.toFloat()) }
    val destFocus = remember { FocusRequester() }
    val connected by ObdManager.connected.collectAsState()
    val values by ObdManager.values.collectAsState()
    val liveSoc = values["bms_soc"] ?: values["soc"]

    val query = if (active == Field.Origin) originText else destText

    LaunchedEffect(Unit) {
        delay(150L)
        try {
            destFocus.requestFocus()
        } catch (_: Exception) {
        }
    }

    LaunchedEffect(query, active) {
        if (query.trim().length < 3) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(350L)
        try {
            suggestions = withContext(Dispatchers.IO) { Providers.search(query.trim(), ObdManager.currentLatLng()) }
            searchError = null
        } catch (e: Exception) {
            suggestions = emptyList()
            searchError = "Arama hatası: ${e.message}"
        }
    }

    fun pick(s: Models.Suggestion) {
        val full = if (s.secondary.isNotBlank()) "${s.main}, ${s.secondary}" else s.main
        val ref = PlaceRef(full, s.placeId.ifBlank { null }, s.latLng)
        if (active == Field.Origin) {
            origin = ref
            originText = s.main
            suggestions = emptyList()
            active = Field.Dest
            try {
                destFocus.requestFocus()
            } catch (_: Exception) {
            }
        } else {
            onPicked(origin, ref)
        }
    }

    val title = when (mode) {
        SearchMode.Trip -> null
        SearchMode.SetHome -> "Ev adresini seç"
        SearchMode.SetWork -> "İş adresini seç"
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Pal.Bg)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E2226))
                .statusBarsPadding()
                .padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = onBack),
                    contentAlignment = Alignment.Center
                ) { Glyph(Ic.Back, Pal.Text) }
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (title != null) {
                        Text(title, color = Pal.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                    } else {
                        TextField(
                            value = originText,
                            onValueChange = {
                                originText = it
                                origin = null
                            },
                            placeholder = { Text("Konumum") },
                            leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(Pal.Blue)) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = fieldColors(),
                            textStyle = TextStyle(fontSize = 15.sp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { if (it.isFocused) active = Field.Origin }
                        )
                    }
                    TextField(
                        value = destText,
                        onValueChange = { destText = it },
                        placeholder = { Text(if (mode == SearchMode.Trip) "Nereye?" else "Adres yaz") },
                        leadingIcon = { Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(Pal.Red)) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors(),
                        textStyle = TextStyle(fontSize = 15.sp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(destFocus)
                            .onFocusChanged { if (it.isFocused) active = Field.Dest }
                    )
                }
            }
        }

        if (mode == SearchMode.Trip) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                if (connected && liveSoc != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BatteryGlyph((liveSoc / 100.0).toFloat(), Pal.Green)
                        Text("Başlangıç şarjı %${liveSoc.roundToInt()} · araçtan canlı", color = Pal.Muted, fontSize = 14.sp)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BatteryGlyph(manualSoc / 100f, Pal.Green)
                        Text("Başlangıç şarjı %${manualSoc.roundToInt()} · araç bağlı değil", color = Pal.Muted, fontSize = 14.sp)
                    }
                    Slider(
                        value = manualSoc,
                        onValueChange = { manualSoc = it },
                        onValueChangeFinished = { Prefs.manualSoc = manualSoc.roundToInt() },
                        valueRange = 5f..100f,
                        colors = SliderDefaults.colors(thumbColor = Pal.Blue, activeTrackColor = Pal.Blue, inactiveTrackColor = Pal.Surface2)
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Pal.Line)
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
        ) {
            searchError?.let {
                Text(it, color = Pal.Red, fontSize = 13.sp, modifier = Modifier.padding(20.dp))
            }
            for (s in suggestions) {
                PlaceRow(Ic.Pin, s.main, s.secondary) { pick(s) }
            }
            if (suggestions.isEmpty() && query.isBlank() && active == Field.Dest && mode == SearchMode.Trip) {
                val home = Prefs.home
                val work = Prefs.work
                if (home != null) PlaceRow(Ic.Home, "Ev", home.name) { onPicked(origin, home) }
                if (work != null) PlaceRow(Ic.Work, "İş", work.name) { onPicked(origin, work) }
                val recents = Prefs.recents()
                if (recents.isNotEmpty()) {
                    Text(
                        "SON ARAMALAR",
                        color = Pal.Label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp)
                    )
                    for (r in recents) {
                        val main = r.name.substringBefore(", ")
                        val rest = r.name.substringAfter(", ", "")
                        PlaceRow(Ic.Clock, main, rest) { onPicked(origin, r) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceRow(icon: Ic, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Pal.Surface2),
            contentAlignment = Alignment.Center
        ) { Glyph(icon, Pal.Soft, 20.dp) }
        Column(Modifier.weight(1f)) {
            Text(title, color = Pal.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = Pal.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// =====================================================================================
// Plan
// =====================================================================================

@Composable
fun PlanScreen(onBack: () -> Unit, onStart: (Plan) -> Unit, onReplan: () -> Unit) {
    val plan by TripRepo.plan.collectAsState()
    val busy by TripRepo.busy.collectAsState()
    val progress by TripRepo.progress.collectAsState()
    val error by TripRepo.error.collectAsState()

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(16.dp)
        ) {
            RoundButton("Geri", size = 48.dp, onClick = onBack) { Glyph(Ic.Back, Pal.Text) }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = 470.dp)
                .shadow(16.dp, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .background(Pal.Surface)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(width = 40.dp, height = 5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFF444B54))
            )
            val p = plan
            val err = error
            when {
                busy -> {
                    Text("Rota planlanıyor…", color = Pal.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Pal.Blue, trackColor = Pal.Surface2)
                    Text(progress, color = Pal.Muted, fontSize = 14.sp)
                }
                err != null -> {
                    Text("Plan yapılamadı", color = Pal.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(err, color = Pal.Red, fontSize = 14.sp)
                    PrimaryButton("Tekrar dene", onReplan)
                }
                p != null -> PlanContent(p, onStart, onReplan)
                else -> Text("Plan yok", color = Pal.Muted)
            }
        }
    }
}

@Composable
private fun PlanContent(p: Plan, onStart: (Plan) -> Unit, onReplan: () -> Unit) {
    var showList by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text("${p.destName.substringBefore(", ")} · varış", color = Pal.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(clock.format(Date(p.arrivalAtMs)), color = Pal.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("Varışta batarya", color = Pal.Muted, fontSize = 13.sp)
            Text("%${p.arrivalSoc}", color = if (p.arrivalSoc >= 15) Pal.Green else Pal.Warn, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatPill("Toplam", duration(p.totalMin), Modifier.weight(1f))
        StatPill("Mesafe", fmt(p.totalKm, 0, "km"), Modifier.weight(1f))
        StatPill("Şarj", if (p.stops.isEmpty()) "Yok" else "${p.stops.size} · ${duration(p.chargeMin)}", Modifier.weight(1f))
    }

    Column {
        TimelineRow(
            marker = { Box(Modifier.size(14.dp).clip(CircleShape).background(Pal.Blue)) },
            title = "Başlangıç",
            right = "%${p.startSoc}",
            detail = null,
            last = false
        )
        p.stops.forEachIndexed { i, s ->
            val power = if (s.charger.maxKw > 0) "${s.charger.maxKw.roundToInt()} kW" else "~${s.stationKw.roundToInt()} kW"
            TimelineRow(
                marker = {
                    Box(
                        Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Pal.Green),
                        contentAlignment = Alignment.Center
                    ) { Text("${i + 1}", color = Color(0xFF0E2A1A), fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                },
                title = s.charger.name,
                right = clock.format(Date(s.arriveAtMs)),
                detail = "%${s.arriveSoc} → %${s.departSoc} · ${s.chargeMin.roundToInt()} dk · $power",
                last = false
            )
        }
        TimelineRow(
            marker = { Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(Pal.Red)) },
            title = p.destName.substringBefore(", "),
            right = "${clock.format(Date(p.arrivalAtMs))} · %${p.arrivalSoc}",
            detail = null,
            last = true
        )
    }

    if (p.routeChargers.isNotEmpty()) {
        Text(
            if (showList) "Yol üstündeki istasyonları gizle" else "Yol üstündeki hızlı şarj istasyonları (${p.routeChargers.size})",
            color = Pal.Blue,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clickable(role = Role.Button) { showList = !showList }
                .padding(vertical = 6.dp)
        )
        if (showList) {
            for (rc in p.routeChargers) {
                val chosen = p.stops.any { it.charger.id == rc.charger.id }
                val power = when {
                    rc.charger.maxKw > 0 -> "${rc.charger.maxKw.roundToInt()} kW"
                    rc.charger.dcKnown -> "hızlı · güç bilinmiyor"
                    else -> "güç bilinmiyor"
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (chosen) Pal.GreenDark else Pal.Surface2)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(rc.charger.name, color = Pal.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "km ${rc.alongKm.roundToInt()} · $power · yoldan ${fmt(rc.offsetKm, 1, "km")}" + if (chosen) " · planda" else "",
                            color = Pal.Muted,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { PrimaryButton("Yolculuğu başlat") { onStart(p) } }
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Pal.Surface2)
                .clickable(role = Role.Button, onClick = onReplan),
            contentAlignment = Alignment.Center
        ) { Glyph(Ic.Refresh, Pal.Text) }
    }
    Text(
        (if (p.traffic) "Varış saati canlı trafiğe göre. " else "Varış saati trafik hariç. ") +
            "Yenile düğmesi şu anki konum ve şarjla yeniden planlar." +
            if (!p.usedElevation) " Yükseklik verisi alınamadı, yol düz kabul edildi." else "",
        color = Pal.Label,
        fontSize = 12.sp
    )
}

@Composable
private fun StatPill(label: String, value: String, modifier: Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Pal.Surface2)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(label, color = Pal.Muted, fontSize = 12.sp)
        Text(value, color = Pal.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun TimelineRow(marker: @Composable () -> Unit, title: String, right: String, detail: String?, last: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.width(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.padding(top = 2.dp)) { marker() }
            if (!last) {
                Box(
                    Modifier
                        .width(2.dp)
                        .height(if (detail != null) 34.dp else 24.dp)
                        .background(Color(0xFF3A4048))
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    title,
                    color = Pal.Text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(right, color = Pal.Muted, fontSize = 14.sp)
            }
            if (detail != null) Text(detail, color = Pal.Muted, fontSize = 13.sp)
        }
    }
}

@Composable
fun PrimaryButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Pal.Blue)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Pal.OnBlue, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun OutlineButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(50.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, Color(0xFF3A4048), RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (enabled) Pal.Text else Pal.Label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

// =====================================================================================
// Car
// =====================================================================================

@Composable
fun CarScreen(onBack: () -> Unit, onShare: () -> Unit) {
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
    var advanced by remember { mutableStateOf(false) }
    var command by remember { mutableStateOf("") }

    val soc = values["bms_soc"] ?: values["soc"]
    val consumption = if (tripKm > 0.5) tripWh / tripKm else null
    val usedWh = if (consumption != null && consumption > 60 && consumption < 400) consumption else EC3.DEFAULT_WH_PER_KM
    val range = soc?.let { EC3.USABLE_KWH * it / 100.0 * 1000.0 / usedWh }

    Column(
        Modifier
            .fillMaxSize()
            .background(Pal.Bg)
            .verticalScroll(rememberScrollState())
    ) {
        ScreenHeader("Citroën ë-C3", onBack) {
            Row(
                modifier = Modifier
                    .height(30.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(if (connected) Pal.GreenDark else Pal.Surface2)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (connected) Pal.Green else Pal.Label))
                Text(if (connected) "Bağlı" else "Bağlı değil", color = if (connected) Pal.GreenText else Pal.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (!connected) {
                SurfaceCard {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(status, color = Pal.Muted, fontSize = 14.sp)
                        PrimaryButton(if (scanning) "Aranıyor…" else "OBD adaptörünü bul") {
                            if (!scanning) ObdManager.startScan()
                        }
                        for (d in devices) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Pal.Surface2)
                                    .clickable(role = Role.Button) { ObdManager.connect(d) }
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        d.name,
                                        color = if (ObdManager.looksLikeObd(d.name)) Pal.GreenText else Pal.Text,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text("${d.rssi} dBm", color = Pal.Muted, fontSize = 12.sp)
                                }
                                Glyph(Ic.Chevron, Pal.Muted, 18.dp)
                            }
                        }
                    }
                }
            }

            SurfaceCard {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                        Column {
                            Text("Batarya", color = Pal.Muted, fontSize = 13.sp)
                            Text(if (soc != null) "%${soc.roundToInt()}" else "—", color = Pal.Text, fontSize = 56.sp, fontWeight = FontWeight.Bold)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Tahmini menzil", color = Pal.Muted, fontSize = 13.sp)
                            Text(fmt(range, 0, "km"), color = Pal.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(14.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(Pal.Surface2)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(((soc ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f))
                                .height(14.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(if ((soc ?: 100.0) < 20) Pal.Warn else Pal.Green)
                        )
                    }
                    Text(
                        if (consumption != null) "Bu sürüşteki tüketimle hesaplandı · ${fmt(usedWh, 0, "Wh/km")}"
                        else "Varsayılan tüketimle hesaplandı · ${fmt(usedWh, 0, "Wh/km")}",
                        color = Pal.Muted,
                        fontSize = 13.sp
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Metric("Anlık güç", fmt(values["kw"], 1, "kW"), Modifier.weight(1f))
                Metric("Hız", fmt(speed, 0, "km/sa"), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Metric("Voltaj", fmt(values["volt"], 0, "V"), Modifier.weight(1f))
                Metric("Akım", fmt(values["amp"], 1, "A"), Modifier.weight(1f))
            }

            SurfaceCard {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Bu sürüş", color = Pal.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    DataRow("Mesafe", fmt(tripKm, 1, "km"))
                    DataRow("Harcanan enerji", fmt(tripWh / 1000.0, 1, "kWh"))
                    DataRow("Ortalama tüketim", fmt(consumption, 0, "Wh/km"))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlineButton("Bağlantıyı kes", Modifier.weight(1f), enabled = connected) { ObdManager.disconnect() }
                OutlineButton("Kaydı paylaş", Modifier.weight(1f), enabled = logFile != null, onClick = onShare)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Sürerken güç eksi görünüyorsa akım işaretini ters çevir",
                    color = Pal.Muted,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = invert, onCheckedChange = { ObdManager.setInvertCurrent(it) })
            }

            Text(
                if (advanced) "Gelişmişi gizle" else "Gelişmiş: komut ve günlük",
                color = Pal.Blue,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable(role = Role.Button) { advanced = !advanced }
                    .padding(vertical = 8.dp)
            )
            if (advanced) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(
                        value = command,
                        onValueChange = { command = it },
                        placeholder = { Text("ör. 224801 veya ATSH7E7") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors(),
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .height(54.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (connected) Pal.Blue else Pal.Surface2)
                            .clickable(enabled = connected, role = Role.Button) { ObdManager.sendRaw(command) }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("Gönder", color = if (connected) Pal.OnBlue else Pal.Label, fontWeight = FontWeight.Bold) }
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF101418))
                        .padding(10.dp)
                ) {
                    val lines = logLines.takeLast(80).reversed()
                    if (lines.isEmpty()) Text("Henüz kayıt yok", color = Pal.Muted, fontSize = 12.sp)
                    for (line in lines) {
                        Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFFB0BEC5))
                    }
                }
            }
            Spacer(Modifier.navigationBarsPadding().height(24.dp))
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Pal.Surface)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(label, color = Pal.Muted, fontSize = 12.sp)
        Text(value, color = Pal.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

// =====================================================================================
// Menu
// =====================================================================================

@Composable
fun MenuScreen(onBack: () -> Unit, onCar: () -> Unit, onSetHome: () -> Unit, onSetWork: () -> Unit, onShare: () -> Unit) {
    val connected by ObdManager.connected.collectAsState()
    val status by ObdManager.status.collectAsState()
    var reserve by remember { mutableFloatStateOf(Prefs.reservePct.toFloat()) }
    var arrive by remember { mutableFloatStateOf(Prefs.chargerArrivePct.toFloat()) }
    var maxCharge by remember { mutableFloatStateOf(Prefs.maxChargePct.toFloat()) }
    var minKw by remember { mutableFloatStateOf(Prefs.minChargerKw.toFloat()) }
    var factor by remember { mutableFloatStateOf(Prefs.consumptionFactor) }
    var tomtom by remember { mutableStateOf(Prefs.tomtomKey) }
    var tomtomSaved by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Pal.Bg)
            .verticalScroll(rememberScrollState())
    ) {
        ScreenHeader("Menü", onBack)
        Column(
            Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SectionLabel("Araç")
            SurfaceCard {
                MenuRow(Ic.Bolt, Pal.Green, "Araç verileri", "Batarya, güç, tüketim", onCar)
                Divider()
                MenuRow(Ic.Locate, Pal.Blue, "OBD adaptörü", if (connected) "Bağlı" else status, onCar)
            }

            SectionLabel("Adresler")
            SurfaceCard {
                MenuRow(Ic.Home, Pal.Soft, "Ev", Prefs.home?.name ?: "Ayarlamak için dokun", onSetHome)
                Divider()
                MenuRow(Ic.Work, Pal.Soft, "İş", Prefs.work?.name ?: "Ayarlamak için dokun", onSetWork)
            }

            SectionLabel("Şarj planlama")
            SurfaceCard {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    PrefSlider("Hedefe varışta en az", "%${reserve.roundToInt()}", reserve, 5f..30f, { reserve = it }) {
                        Prefs.reservePct = reserve.roundToInt()
                    }
                    PrefSlider("İstasyona varışta en az", "%${arrive.roundToInt()}", arrive, 3f..30f, { arrive = it }) {
                        Prefs.chargerArrivePct = arrive.roundToInt()
                    }
                    PrefSlider("Yolda en fazla şarj", "%${maxCharge.roundToInt()}", maxCharge, 60f..100f, { maxCharge = it }) {
                        Prefs.maxChargePct = maxCharge.roundToInt()
                    }
                    PrefSlider("En düşük istasyon gücü", "${((minKw / 5f).roundToInt() * 5)} kW", minKw, 20f..150f, { minKw = it }) {
                        Prefs.minChargerKw = (minKw / 5f).roundToInt() * 5
                    }
                    PrefSlider(
                        "Tüketim katsayısı",
                        String.format(Locale.US, "x%.2f", factor),
                        factor,
                        0.7f..1.5f,
                        { factor = it }
                    ) {
                        Prefs.consumptionFactor = (factor * 100f).roundToInt() / 100f
                    }
                    Text(
                        "Model tüketimi: ${fmt(EnergyModel.whPerKm(90.0), 0, "Wh/km")} @90 km/sa, " +
                            "${fmt(EnergyModel.whPerKm(120.0), 0, "Wh/km")} @120 km/sa. Gerçekte daha çok harcıyorsan katsayıyı artır.",
                        color = Pal.Label,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }

            SectionLabel("Diğer")
            SurfaceCard {
                MenuRow(Ic.Clock, Pal.Soft, "Sürüş kaydını paylaş", "Son sürüşün CSV dosyası", onShare)
                Divider()
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Canlı trafik (TomTom)", color = Pal.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (Prefs.tomtomKey.isNotBlank()) "Açık: haritada trafik renkleri görünür, varış saati trafiğe göre hesaplanır."
                        else "developer.tomtom.com'da ücretsiz hesap açıp aldığın anahtarı buraya yapıştır. Kredi kartı gerekmez.",
                        color = Pal.Muted,
                        fontSize = 13.sp
                    )
                    TextField(
                        value = tomtom,
                        onValueChange = {
                            tomtom = it
                            tomtomSaved = false
                        },
                        placeholder = { Text("TomTom anahtarı") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlineButton(if (tomtomSaved) "Kaydedildi · haritayı görmek için uygulamayı yeniden aç" else "TomTom anahtarını kaydet", Modifier.fillMaxWidth()) {
                        Prefs.tomtomKey = tomtom
                        tomtomSaved = true
                    }
                }
            }
            Spacer(Modifier.navigationBarsPadding().height(24.dp))
        }
    }
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Pal.Line)
    )
}

@Composable
private fun MenuRow(icon: Ic, tint: Color, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Glyph(icon, tint)
        Column(Modifier.weight(1f)) {
            Text(title, color = Pal.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Pal.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Glyph(Ic.Chevron, Color(0xFF6B7480), 18.dp)
    }
}

@Composable
private fun PrefSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onDone: () -> Unit
) {
    Column(Modifier.padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Pal.Text, fontSize = 15.sp)
            Text(valueText, color = Pal.Blue, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onDone,
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = Pal.Blue, activeTrackColor = Pal.Blue, inactiveTrackColor = Pal.Surface2)
        )
    }
}
