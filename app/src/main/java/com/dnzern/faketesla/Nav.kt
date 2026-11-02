package com.dnzern.faketesla

import android.content.Context
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
    val socAtNext: Double?
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
    return NavInfo(idx, offRouteM, step, distStep, remainingKm, remainingMin, nextStop, distStop, soc)
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
    var lastIdx by remember(plan) { mutableIntStateOf(0) }
    var spokenFar by remember(plan) { mutableIntStateOf(-1) }
    var spokenNear by remember(plan) { mutableIntStateOf(-1) }
    var offCount by remember(plan) { mutableIntStateOf(0) }
    var muted by remember { mutableStateOf(Voice.muted) }
    var info by remember(plan) { mutableStateOf<NavInfo?>(null) }
    var arrived by remember(plan) { mutableStateOf(false) }

    DisposableEffect(Unit) {
        Voice.init(context)
        ObdManager.startLocation()
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

        val bearing = if (l.hasBearing() && l.speed > 1.5f) l.bearing else {
            val ahead = p.routePts[minOf(p.routePts.size - 1, idx + 5)]
            bearingTo(here, ahead)
        }
        controller.follow(here, bearing, if (l.speed > 22f) 15.0 else 16.5)

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
        // Instruction banner
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(12.dp)
                .fillMaxWidth()
                .shadow(12.dp, RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp))
                .background(Pal.Blue)
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            when {
                busy -> Text("Rota yeniden hesaplanıyor…", color = Pal.OnBlue, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                arrived -> Text("Hedefe vardınız", color = Pal.OnBlue, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                ni == null -> Text("Konum bekleniyor…", color = Pal.OnBlue, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                ni.step != null -> {
                    Text(distText(ni.distToStepM), color = Pal.OnBlue, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text(ni.step.text, color = Pal.OnBlue, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                else -> Text("Rotayı takip et", color = Pal.OnBlue, fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text("Varış", color = Pal.Muted, fontSize = 12.sp)
                        Text(
                            clock.format(Date(System.currentTimeMillis() + (ni.remainingMin * 60_000).toLong())),
                            color = Pal.Text,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Kalan", color = Pal.Muted, fontSize = 12.sp)
                        Text("${fmt(ni.remainingKm, 0, "km")} · ${duration(ni.remainingMin)}", color = Pal.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
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
                    val p = plan
                    val soc = ni.socAtNext?.roundToInt() ?: p?.arrivalSoc
                    if (soc != null) Text("Hedefe varışta batarya %$soc", color = Pal.Text, fontSize = 14.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlineButton(if (muted) "Sesi aç" else "Sesi kapat", Modifier.weight(1f)) {
                    muted = !muted
                    Voice.muted = muted
                }
                Box(Modifier.weight(1f)) { PrimaryButton("Bitir", onEnd) }
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
