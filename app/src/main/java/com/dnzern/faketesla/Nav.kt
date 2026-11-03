package com.dnzern.faketesla

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** Turkish voice prompts using the phone's built-in text-to-speech. */
object Voice {
    private var tts: TextToSpeech? = null
    private var ready = false
    var muted = false

    fun init(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("tr", "TR")
                ready = true
            }
        }
    }

    fun say(text: String) {
        if (!ready || muted || text.isBlank()) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav")
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}

/** Where we are on the planned route. */
class NavInfo(
    val idx: Int,
    val offRouteM: Double,
    val step: NavStep?,
    val distToStepM: Double,
    val remainingKm: Double,
    val remainingMin: Double,
    val nextStop: Stop?,
    val distToStopKm: Double,
    /** Battery % expected at the next stop (or the destination), from live data when connected. */
    val socAtNext: Double?,
    val thenStep: NavStep? = null,
    /** Speed limit here in km/h, 0 when unknown. */
    val limit: Int = 0,
    /** Distance to the next speed camera in metres, null when none within 2 km. */
    val cameraM: Double? = null,
    val cameraIdx: Int = -1
)

private const val PCT_WH = EC3.USABLE_KWH * 10.0

/** Finds the nearest route point, searching near the last one first. */
fun locate(plan: Plan, here: LatLng, lastIdx: Int): Pair<Int, Double> {
    val pts = plan.routePts
    if (pts.isEmpty()) return 0 to Double.MAX_VALUE
    var best = lastIdx.coerceIn(0, pts.size - 1)
    var bestD = Geo.dist(pts[best], here)
    val from = max(0, lastIdx - 30)
    val to = minOf(pts.size - 1, lastIdx + 600)
    for (i in from..to) {
        val d = Geo.dist(pts[i], here)
        if (d < bestD) {
            bestD = d
            best = i
        }
    }
    if (bestD > 200) {
        for (i in pts.indices) {
            val d = Geo.dist(pts[i], here)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
    }
    return best to bestD
}

fun navInfo(plan: Plan, idx: Int, offRouteM: Double, liveSoc: Double?): NavInfo {
    val n = plan.routePts.size
    val last = n - 1
    val hasCum = plan.cumKm.size == n && plan.cumMin.size == n && plan.cumWh.size == n
    val kmHere = if (hasCum) plan.cumKm[idx] else 0.0
    val step = plan.steps.firstOrNull { it.idx > idx }
    val distStep = if (step != null && hasCum) (plan.cumKm[step.idx] - kmHere) * 1000.0 else 0.0
    val remainingKm = if (hasCum) plan.cumKm[last] - kmHere else plan.totalKm
    val chargeLeft = plan.stops.filter { it.alongKm > kmHere + 0.2 }.sumOf { it.chargeMin + 4.0 }
    val remainingMin = if (hasCum) plan.cumMin[last] - plan.cumMin[idx] + chargeLeft else plan.totalMin
    val nextStop = plan.stops.firstOrNull { it.alongKm > kmHere - 0.3 }
    val distStop = if (nextStop != null) nextStop.alongKm - kmHere else 0.0
    val soc = if (liveSoc != null && hasCum) {
        val targetIdx = if (nextStop != null) {
            plan.cumKm.indexOfFirst { it >= nextStop.alongKm }.let { if (it < 0) last else it }
        } else {
            last
        }
        liveSoc - (plan.cumWh[targetIdx] - plan.cumWh[idx]) / PCT_WH
    } else {
        null
    }
    val then = if (step != null) plan.steps.firstOrNull { it.idx > step.idx } else null
    val limit = plan.limits.lastOrNull { it.first <= idx }?.second ?: 0
    val cam = plan.cameras.firstOrNull { it > idx }
    val camM = if (cam != null && hasCum) (plan.cumKm[cam] - kmHere) * 1000.0 else null
    return NavInfo(
        idx, offRouteM, step, distStep, remainingKm, remainingMin, nextStop, distStop, soc,
        thenStep = then,
        limit = limit,
        cameraM = camM?.takeIf { it <= 2000.0 },
        cameraIdx = cam ?: -1
    )
}

fun distText(m: Double): String = when {
    m >= 10_000 -> "${(m / 1000).roundToInt()} km"
    m >= 1000 -> String.format(Locale.US, "%.1f km", m / 1000).replace('.', ',')
    m >= 100 -> "${((m / 50).roundToInt() * 50)} m"
    else -> "${(m / 10).roundToInt() * 10} m"
}

@Composable
fun NavScreen(controller: MapCtl, onEnd: () -> Unit) {
    val context = LocalContext.current
    val plan by TripRepo.plan.collectAsState()
    val busy by TripRepo.busy.collectAsState()
    val location by ObdManager.location.collectAsState()
    val values by ObdManager.values.collectAsState()
    val connected by ObdManager.connected.collectAsState()
    val userMoved by controller.userMoved.collectAsState()
    var lastIdx by remember(plan) { mutableIntStateOf(0) }
    var spokenFar by remember(plan) { mutableIntStateOf(-1) }
    var spokenNear by remember(plan) { mutableIntStateOf(-1) }
    var spokenCam by remember(plan) { mutableIntStateOf(-1) }
    var offCount by remember(plan) { mutableIntStateOf(0) }
    var muted by remember { mutableStateOf(Voice.muted) }
    var info by remember(plan) { mutableStateOf<NavInfo?>(null) }
    var arrived by remember(plan) { mutableStateOf(false) }
    var speedKmh by remember { mutableStateOf<Int?>(null) }

    DisposableEffect(Unit) {
        Voice.init(context)
        ObdManager.startLocation()
        controller.userMoved.value = false
        onDispose { }
    }

    LaunchedEffect(plan, location) {
        val p = plan ?: return@LaunchedEffect
        val l = location ?: return@LaunchedEffect
        val here = LatLng(l.latitude, l.longitude)
        val (idx, off) = locate(p, here, lastIdx)
        lastIdx = idx
        val live = if (connected) (values["bms_soc"] ?: values["soc"]) else null
        val ni = navInfo(p, idx, off, live)
        info = ni
        speedKmh = if (l.hasSpeed()) (l.speed * 3.6f).roundToInt() else null

        if (!controller.userMoved.value) {
            val bearing = if (l.hasBearing() && l.speed > 1.5f) l.bearing else {
                bearingTo(here, p.routePts[minOf(p.routePts.size - 1, idx + 5)])
            }
            controller.follow(here, bearing, if (l.speed > 22f) 15.0 else 16.5)
        }

        if (ni.remainingKm < 0.05 && !arrived) {
            arrived = true
            Voice.say("Hedefe vardınız")
        }

        val step = ni.step
        if (step != null && !arrived) {
            if (ni.distToStepM in 150.0..1200.0 && spokenFar != step.idx) {
                spokenFar = step.idx
                Voice.say("${distText(ni.distToStepM)} sonra, ${step.voice}")
            } else if (ni.distToStepM < 120.0 && spokenNear != step.idx) {
                spokenNear = step.idx
                Voice.say(step.voice)
            }
        }

        val camM = ni.cameraM
        if (camM != null && camM < 550 && spokenCam != ni.cameraIdx) {
            spokenCam = ni.cameraIdx
            Voice.say("Dikkat, ${distText(camM)} ileride radar" + if (ni.limit > 0) ", hız sınırı ${ni.limit}" else "")
        }

        // Off the route for a few fixes in a row: plan again from here.
        if (off > 80 && l.accuracy < 40f) offCount++ else offCount = 0
        if (offCount >= 3 && !busy) {
            offCount = 0
            val d = TripRepo.lastDest
            if (d != null) {
                Voice.say("Rota yeniden hesaplanıyor")
                TripRepo.planTrip(null, d, planningSoc())
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        val ni = info

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(12.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Instruction banner
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(12.dp, RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp))
                    .background(Pal.Blue)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val step = ni?.step
                when {
                    busy -> Text("Rota yeniden hesaplanıyor…", color = Pal.OnBlue, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    arrived -> {
                        TurnArrow(4, Pal.OnBlue, 44.dp)
                        Spacer(Modifier.width(14.dp))
                        Text("Hedefe vardınız", color = Pal.OnBlue, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                    ni == null -> Text("Konum bekleniyor…", color = Pal.OnBlue, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    step != null -> {
                        TurnArrow(step.type, Pal.OnBlue, 48.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(distText(ni.distToStepM), color = Pal.OnBlue, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                            Text(step.text, color = Pal.OnBlue, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    else -> Text("Rotayı takip et", color = Pal.OnBlue, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
            val then = ni?.thenStep
            if (then != null && !arrived && !busy) {
                Row(
                    modifier = Modifier
                        .shadow(6.dp, RoundedCornerShape(14.dp))
                        .clip(RoundedCornerShape(14.dp))
                        .background(Pal.OnBlue)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Sonra", color = Pal.Muted, fontSize = 13.sp)
                    Spacer(Modifier.width(8.dp))
                    TurnArrow(then.type, Pal.Text, 22.dp)
                }
            }
            val camM = ni?.cameraM
            if (camM != null && !arrived) {
                Row(
                    modifier = Modifier
                        .shadow(6.dp, RoundedCornerShape(14.dp))
                        .clip(RoundedCornerShape(14.dp))
                        .background(Pal.Warn)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Radar · ${distText(camM)}", color = Color(0xFF2A1A00), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Speed and speed limit, plus recenter
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = 16.dp, bottom = 230.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val limit = ni?.limit ?: 0
            if (limit > 0) SpeedLimitSign(limit)
            val spd = speedKmh
            val over = spd != null && limit > 0 && spd > limit + 5
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .shadow(8.dp, CircleShape)
                    .clip(CircleShape)
                    .background(if (over) Pal.Red else Pal.Chip),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(spd?.toString() ?: "–", color = Pal.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("km/sa", color = if (over) Pal.Text else Pal.Muted, fontSize = 10.sp)
                }
            }
        }
        if (userMoved) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 16.dp, bottom = 230.dp)
            ) {
                Row(
                    modifier = Modifier
                        .height(48.dp)
                        .shadow(8.dp, RoundedCornerShape(24.dp))
                        .clip(RoundedCornerShape(24.dp))
                        .background(Pal.Chip)
                        .clickable(role = Role.Button) { controller.userMoved.value = false }
                        .padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Glyph(Ic.Locate, Pal.Blue, 20.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Ortala", color = Pal.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Trip panel
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(12.dp)
                .fillMaxWidth()
                .shadow(12.dp, RoundedCornerShape(22.dp))
                .clip(RoundedCornerShape(22.dp))
                .background(Pal.Chip)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (ni != null) {
                val eta = System.currentTimeMillis() + (ni.remainingMin * 60_000).toLong()
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text(if (plan?.traffic == true) "Varış · trafikle" else "Varış", color = Pal.Muted, fontSize = 12.sp)
                        Text(clock.format(Date(eta)), color = Pal.Green, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.weight(1.3f)) {
                        Text("Kalan", color = Pal.Muted, fontSize = 12.sp)
                        Text("${duration(ni.remainingMin)} · ${fmt(ni.remainingKm, 0, "km")}", color = Pal.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                val stop = ni.nextStop
                if (stop != null) {
                    val socText = ni.socAtNext?.let { " · varışta %${it.roundToInt()}" } ?: " · varışta %${stop.arriveSoc}"
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Glyph(Ic.Bolt, Pal.Green, 18.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${stop.charger.name} · ${fmt(ni.distToStopKm, 0, "km")}$socText",
                            color = Pal.Text,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    val soc = ni.socAtNext?.roundToInt() ?: plan?.arrivalSoc
                    if (soc != null) Text("Hedefe varışta batarya %$soc", color = Pal.Text, fontSize = 14.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlineButton(if (muted) "Ses aç" else "Sessiz", Modifier.weight(1f)) {
                        muted = !muted
                        Voice.muted = muted
                    }
                    OutlineButton("Paylaş", Modifier.weight(1f)) {
                        val text = "Tahmini varışım ${clock.format(Date(eta))}" +
                            (plan?.destName?.let { " · ${it.substringBefore(", ")}" } ?: "")
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        try {
                            context.startActivity(Intent.createChooser(send, "Varış saatini paylaş").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: Exception) {
                        }
                    }
                    Box(Modifier.weight(1f)) { PrimaryButton("Bitir", onEnd) }
                }
            } else {
                Box(Modifier.fillMaxWidth()) { PrimaryButton("Bitir", onEnd) }
            }
        }
    }
}

/** Round European-style speed limit sign. */
@Composable
fun SpeedLimitSign(kmh: Int) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(Color.White)
            .border(6.dp, Color(0xFFD32F2F), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(kmh.toString(), color = Color.Black, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

/** Turn arrow for a Valhalla maneuver type. */
@Composable
fun TurnArrow(type: Int, color: Color, size: androidx.compose.ui.unit.Dp) {
    val angle = when (type) {
        9, 23, 37, 2 -> 45f
        10, 18, 20, 5 -> 90f
        11 -> 135f
        16, 24, 38, 3 -> -45f
        15, 19, 21, 6 -> -90f
        14 -> -135f
        else -> 0f
    }
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val u = w / 24f
        val stroke = Stroke(width = 3f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (type) {
            4 -> {
                // destination: flag
                drawLine(color, Offset(7 * u, 21 * u), Offset(7 * u, 3 * u), 2.5f * u, StrokeCap.Round)
                drawPath(Path().apply { moveTo(7 * u, 4 * u); lineTo(19 * u, 7.5f * u); lineTo(7 * u, 11 * u); close() }, color)
            }
            12, 13 -> {
                // U-turn
                val left = type == 13
                val x1 = if (left) 16 * u else 8 * u
                val x2 = if (left) 8 * u else 16 * u
                drawPath(
                    Path().apply {
                        moveTo(x1, 21 * u); lineTo(x1, 9 * u)
                        cubicTo(x1, 3 * u, x2, 3 * u, x2, 9 * u); lineTo(x2, 14 * u)
                    },
                    color,
                    style = stroke
                )
                drawPath(Path().apply { moveTo(x2 - 4 * u, 11 * u); lineTo(x2, 16 * u); lineTo(x2 + 4 * u, 11 * u) }, color, style = stroke)
            }
            26, 27 -> {
                // roundabout
                drawCircle(color, 5 * u, Offset(12 * u, 12 * u), style = stroke)
                drawLine(color, Offset(12 * u, 22 * u), Offset(12 * u, 17 * u), 3 * u, StrokeCap.Round)
                drawLine(color, Offset(15.5f * u, 8.5f * u), Offset(20 * u, 4 * u), 3 * u, StrokeCap.Round)
                drawPath(Path().apply { moveTo(15 * u, 4 * u); lineTo(20 * u, 4 * u); lineTo(20 * u, 9 * u) }, color, style = stroke)
            }
            else -> rotate(angle, pivot = Offset(12 * u, 14 * u)) {
                drawLine(color, Offset(12 * u, 22 * u), Offset(12 * u, 5 * u), 3 * u, StrokeCap.Round)
                drawPath(Path().apply { moveTo(6 * u, 11 * u); lineTo(12 * u, 4 * u); lineTo(18 * u, 11 * u) }, color, style = stroke)
            }
        }
    }
}

/** Compass direction from a to b in degrees. */
fun bearingTo(a: LatLng, b: LatLng): Float {
    val la1 = Math.toRadians(a.lat)
    val la2 = Math.toRadians(b.lat)
    val dl = Math.toRadians(b.lng - a.lng)
    val y = Math.sin(dl) * Math.cos(la2)
    val x = Math.cos(la1) * Math.sin(la2) - Math.sin(la1) * Math.cos(la2) * Math.cos(dl)
    return ((Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0).toFloat()
}
