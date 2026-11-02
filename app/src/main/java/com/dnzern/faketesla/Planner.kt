package com.dnzern.faketesla

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.PriorityQueue
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min

class PlaceRef(val name: String, val placeId: String?, val latLng: LatLng?)

class Stop(
    val charger: GoogleApi.Charger,
    val alongKm: Double,
    val arriveSoc: Int,
    val departSoc: Int,
    val chargeMin: Double,
    val stationKw: Double,
    val arriveAtMs: Long
)

class Plan(
    val destName: String,
    val totalKm: Double,
    val driveMin: Double,
    val chargeMin: Double,
    val totalMin: Double,
    val startSoc: Int,
    val arrivalSoc: Int,
    val arrivalAtMs: Long,
    val stops: List<Stop>,
    val routePts: List<LatLng>,
    val usedElevation: Boolean,
    val chargersFound: Int,
    val map: Bitmap?
)

/** Holds the current trip plan; shared by the phone UI and Android Auto. */
object TripRepo {
    val plan = MutableStateFlow<Plan?>(null)
    val busy = MutableStateFlow(false)
    val progress = MutableStateFlow("")
    val error = MutableStateFlow<String?>(null)
    var lastDest: PlaceRef? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun planTrip(origin: PlaceRef?, dest: PlaceRef, startSoc: Double) {
        if (busy.value) return
        lastDest = dest
        busy.value = true
        error.value = null
        scope.launch {
            try {
                val p = withContext(Dispatchers.IO) {
                    Planner.plan(origin, dest, startSoc) { msg -> progress.value = msg }
                }
                plan.value = p
            } catch (e: Exception) {
                error.value = e.message ?: e.toString()
                ObdManager.log("Planlama hatası: ${e.message}")
            } finally {
                busy.value = false
                progress.value = ""
            }
        }
    }

    fun clear() {
        plan.value = null
        error.value = null
    }
}

object Planner {
    private const val PCT_WH = EC3.USABLE_KWH * 10.0 // Wh in 1 % of battery
    private const val STOP_OVERHEAD_MIN = 4.0        // parking, plugging in, paying
    private const val MAX_OFFSET_KM = 3.0            // ignore chargers further than this from the road
    private const val CHUNK_KM = 80.0

    private class Node(
        val idx: Int,
        val charger: GoogleApi.Charger?,
        val halfDetourMin: Double,
        val halfDetourWh: Double,
        val kw: Double
    )

    fun plan(origin: PlaceRef?, dest: PlaceRef, startSoc: Double, progress: (String) -> Unit): Plan {
        val reserve = Prefs.reservePct
        val chargerArrive = Prefs.chargerArrivePct
        val maxCharge = Prefs.maxChargePct
        val minKw = Prefs.minChargerKw

        // 1. Route ------------------------------------------------------------------
        progress("Rota hesaplanıyor…")
        val originJson: JSONObject = when {
            origin?.placeId != null -> GoogleApi.placeIdJson(origin.placeId)
            origin?.latLng != null -> GoogleApi.latLngJson(origin.latLng)
            else -> {
                val here = ObdManager.currentLatLng()
                    ?: throw ApiException("Konumun alınamadı. GPS'i aç ya da başlangıç adresi yaz.")
                GoogleApi.latLngJson(here)
            }
        }
        val destJson: JSONObject = when {
            dest.placeId != null -> GoogleApi.placeIdJson(dest.placeId)
            dest.latLng != null -> GoogleApi.latLngJson(dest.latLng)
            else -> throw ApiException("Hedef seçilmedi")
        }
        val route = GoogleApi.computeRoute(originJson, destJson)

        // 2. Points with a speed for each segment -------------------------------------
        val pts = ArrayList<LatLng>()
        val segSpeed = ArrayList<Double>() // speed of the segment ending at this point
        for (step in route.steps) {
            val sp = Geo.decode(step.polyline)
            if (sp.isEmpty()) continue
            val kmh = if (step.staticDurationS > 1.0) {
                (step.distanceM / 1000.0) / (step.staticDurationS / 3600.0)
            } else {
                50.0
            }
            val speed = kmh.coerceIn(5.0, 140.0)
            for (p in sp) {
                if (pts.isNotEmpty() && Geo.dist(pts.last(), p) < 1.0) continue
                pts.add(p)
                segSpeed.add(speed)
            }
        }
        if (pts.size < 2) {
            pts.clear()
            segSpeed.clear()
            val avg = if (route.staticDurationS > 1.0) {
                (route.distanceM / 1000.0) / (route.staticDurationS / 3600.0)
            } else {
                70.0
            }
            for (p in Geo.decode(route.polyline)) {
                pts.add(p)
                segSpeed.add(avg.coerceIn(5.0, 140.0))
            }
        }
        if (pts.size < 2) throw ApiException("Rota çizgisi alınamadı")
        val n = pts.size
        val cumKm = DoubleArray(n)
        for (i in 1 until n) cumKm[i] = cumKm[i - 1] + Geo.dist(pts[i - 1], pts[i]) / 1000.0
        val totalKm = cumKm[n - 1]

        // 3. Elevation profile ------------------------------------------------------
        progress("Yükseklik profili alınıyor…")
        var heights: DoubleArray? = null
        try {
            val sampleCount = min(300, n)
            val sampleIdx = IntArray(sampleCount)
            var j = 0
            for (s in 0 until sampleCount) {
                val target = if (sampleCount > 1) totalKm * s / (sampleCount - 1) else 0.0
                while (j < n - 1 && cumKm[j] < target) j++
                sampleIdx[s] = j
            }
            val h = GoogleApi.elevations(sampleIdx.map { pts[it] })
            val out = DoubleArray(n)
            var s = 0
            for (i in 0 until n) {
                while (s < sampleCount - 2 && cumKm[sampleIdx[s + 1]] < cumKm[i]) s++
                val a = sampleIdx[s]
                val b = sampleIdx[min(s + 1, sampleCount - 1)]
                val span = cumKm[b] - cumKm[a]
                val f = if (span > 1e-6) ((cumKm[i] - cumKm[a]) / span).coerceIn(0.0, 1.0) else 0.0
                out[i] = h[s] + f * (h[min(s + 1, sampleCount - 1)] - h[s])
            }
            heights = out
        } catch (e: Exception) {
            ObdManager.log("Yükseklik verisi alınamadı, düz yol varsayıldı: ${e.message}")
        }

        // 4. Energy and time along the route ----------------------------------------
        val cumWh = DoubleArray(n)
        val cumMin = DoubleArray(n)
        for (i in 1 until n) {
            val d = cumKm[i] - cumKm[i - 1]
            val v = segSpeed[i]
            var wh = d * EnergyModel.whPerKm(v)
            val hh = heights
            if (hh != null) wh += EnergyModel.elevationWh(hh[i] - hh[i - 1])
            cumWh[i] = cumWh[i - 1] + wh
            cumMin[i] = cumMin[i - 1] + d / v * 60.0
        }
        // Scale times so the total matches Google's traffic-aware duration.
        if (route.durationS > 0 && cumMin[n - 1] > 0) {
            val k = (route.durationS / 60.0) / cumMin[n - 1]
            for (i in 0 until n) cumMin[i] *= k
        }

        // 5. Chargers along the route (only if we may need one) -----------------------
        val needsCharge = startSoc - cumWh[n - 1] / PCT_WH < reserve
        val found = LinkedHashMap<String, GoogleApi.Charger>()
        var lastErr: Exception? = null
        if (needsCharge) {
            var startKm = 0.0
            var chunk = 1
            val chunks = kotlin.math.ceil(totalKm / CHUNK_KM).toInt().coerceAtLeast(1)
            while (startKm < totalKm) {
                val endKm = min(totalKm, startKm + CHUNK_KM)
                progress("Şarj istasyonları aranıyor ($chunk/$chunks)…")
                val seg = ArrayList<LatLng>()
                for (i in 0 until n) if (cumKm[i] >= startKm - 2 && cumKm[i] <= endKm + 2) seg.add(pts[i])
                if (seg.size >= 2) {
                    try {
                        for (c in GoogleApi.chargersAlong(Geo.encode(Geo.thin(seg, 150)), minKw)) {
                            if (c.id.isNotBlank() && !found.containsKey(c.id)) found[c.id] = c
                        }
                    } catch (e: Exception) {
                        lastErr = e
                    }
                }
                startKm = endKm
                chunk++
            }
            if (found.isEmpty() && lastErr != null) {
                throw ApiException("Şarj istasyonları alınamadı: ${lastErr.message}")
            }
        }

        // 6. Put chargers on the route --------------------------------------------------
        val cosLat = cos(Math.toRadians(pts[0].lat))
        val nodes = ArrayList<Node>()
        nodes.add(Node(0, null, 0.0, 0.0, 0.0))
        val chargerNodes = ArrayList<Node>()
        for (c in found.values) {
            var bestI = 0
            var bestD = Double.MAX_VALUE
            for (i in 0 until n) {
                val dy = pts[i].lat - c.loc.lat
                val dx = (pts[i].lng - c.loc.lng) * cosLat
                val d2 = dx * dx + dy * dy
                if (d2 < bestD) {
                    bestD = d2
                    bestI = i
                }
            }
            val offsetKm = Geo.dist(pts[bestI], c.loc) / 1000.0
            if (offsetKm > MAX_OFFSET_KM) continue
            val detourKm = offsetKm * 1.3 // one way, roads are not straight
            val halfMin = detourKm / 40.0 * 60.0
            val halfWh = detourKm * EnergyModel.whPerKm(40.0)
            val kw = if (c.maxKw > 0) c.maxKw else minKw.toDouble()
            chargerNodes.add(Node(bestI, c, halfMin, halfWh, kw))
        }
        chargerNodes.sortBy { it.idx }
        nodes.addAll(chargerNodes)
        nodes.add(Node(n - 1, null, 0.0, 0.0, 0.0))

        // 7. Fastest plan: Dijkstra over (stop, battery %) ------------------------------
        progress("En hızlı plan hesaplanıyor…")
        val nn = nodes.size
        val best = Array(nn) { DoubleArray(101) { Double.MAX_VALUE } }
        val parentNode = Array(nn) { IntArray(101) { -1 } }
        val parentSoc = Array(nn) { IntArray(101) { -1 } }
        val chargedTo = Array(nn) { IntArray(101) { -1 } }
        val s0 = floor(startSoc).toInt().coerceIn(0, 100)
        best[0][s0] = 0.0
        val pq = PriorityQueue<DoubleArray>(Comparator<DoubleArray> { x, y -> x[0].compareTo(y[0]) })
        pq.add(doubleArrayOf(0.0, 0.0, s0.toDouble()))
        var endSoc = -1

        fun energy(i: Int, j: Int): Double =
            cumWh[nodes[j].idx] - cumWh[nodes[i].idx] + nodes[i].halfDetourWh + nodes[j].halfDetourWh

        fun drive(i: Int, j: Int): Double =
            cumMin[nodes[j].idx] - cumMin[nodes[i].idx] + nodes[i].halfDetourMin + nodes[j].halfDetourMin

        while (pq.isNotEmpty()) {
            val cur = pq.poll() ?: break
            val t = cur[0]
            val i = cur[1].toInt()
            val a = cur[2].toInt()
            if (t > best[i][a]) continue
            if (i == nn - 1) {
                endSoc = a
                break
            }
            val targets = ArrayList<Int>()
            targets.add(a)
            if (i != 0 && a < maxCharge) {
                var x = (a / 5 + 1) * 5
                while (x < maxCharge) {
                    targets.add(x)
                    x += 5
                }
                targets.add(maxCharge)
            }
            for (tgt in targets) {
                val cm = if (tgt > a) {
                    EnergyModel.chargeMinutes(a.toDouble(), tgt.toDouble(), nodes[i].kw) + STOP_OVERHEAD_MIN
                } else {
                    0.0
                }
                for (j in i + 1 until nn) {
                    if (cumKm[nodes[j].idx] - cumKm[nodes[i].idx] > 450.0) break
                    val arr = tgt - energy(i, j) / PCT_WH
                    val need = if (j == nn - 1) reserve else chargerArrive
                    if (arr < need) continue
                    val ai = floor(arr).toInt().coerceIn(0, 100)
                    val nt = t + cm + drive(i, j)
                    if (nt < best[j][ai]) {
                        best[j][ai] = nt
                        parentNode[j][ai] = i
                        parentSoc[j][ai] = a
                        chargedTo[j][ai] = tgt
                        pq.add(doubleArrayOf(nt, j.toDouble(), ai.toDouble()))
                    }
                }
            }
        }

        if (endSoc < 0) {
            val msg = if (chargerNodes.isEmpty()) {
                "Rota üzerinde uygun hızlı şarj istasyonu bulunamadı (en az $minKw kW). " +
                    "Ayarlar'dan minimum istasyon gücünü düşürmeyi dene."
            } else {
                "Bu şarj seviyesiyle hedefe ulaşan bir plan bulunamadı. " +
                    "Başlangıç şarjını, rezerv ayarlarını veya minimum istasyon gücünü kontrol et."
            }
            throw ApiException(msg)
        }

        // 8. Walk back to build the stop list ------------------------------------------
        val now = System.currentTimeMillis()
        val chain = ArrayList<Triple<Int, Int, Int>>() // node, arrive %, depart %
        var j = nn - 1
        var s = endSoc
        while (parentNode[j][s] >= 0) {
            val pi = parentNode[j][s]
            val pa = parentSoc[j][s]
            val pt = chargedTo[j][s]
            chain.add(Triple(pi, pa, pt))
            j = pi
            s = pa
        }
        chain.reverse()
        val stops = ArrayList<Stop>()
        var chargeTotal = 0.0
        for ((ni, arrive, depart) in chain) {
            val node = nodes[ni]
            val c = node.charger ?: continue
            if (depart <= arrive) continue
            val cm = EnergyModel.chargeMinutes(arrive.toDouble(), depart.toDouble(), node.kw)
            chargeTotal += cm
            stops.add(
                Stop(
                    c,
                    cumKm[node.idx],
                    arrive,
                    depart,
                    cm,
                    node.kw,
                    now + (best[ni][arrive] * 60_000.0).toLong()
                )
            )
        }
        val totalMin = best[nn - 1][endSoc]

        // 9. Map image -------------------------------------------------------------------
        progress("Harita hazırlanıyor…")
        val map = try {
            GoogleApi.staticMap(pts, stops.map { it.charger.loc })
        } catch (e: Exception) {
            ObdManager.log("Harita resmi alınamadı: ${e.message}")
            null
        }

        return Plan(
            destName = dest.name,
            totalKm = totalKm,
            driveMin = totalMin - chargeTotal - stops.size * STOP_OVERHEAD_MIN,
            chargeMin = chargeTotal,
            totalMin = totalMin,
            startSoc = s0,
            arrivalSoc = endSoc,
            arrivalAtMs = now + (totalMin * 60_000.0).toLong(),
            stops = stops,
            routePts = pts,
            usedElevation = heights != null,
            chargersFound = chargerNodes.size,
            map = map
        )
    }
}
