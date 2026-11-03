package com.dnzern.faketesla

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Free, key-less services built on OpenStreetMap data:
 * - Photon (komoot) for address search
 * - Valhalla (openstreetmap.de) for routing and elevation
 * - Overpass for charging stations
 * They have no live traffic. All calls are blocking: run them on Dispatchers.IO.
 */
object FreeApi {

    private const val UA = "FakeTesla/0.5 (personal EV route planner)"
    private const val VALHALLA = "https://valhalla1.openstreetmap.de"
    private val OVERPASS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter"
    )

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun request(url: String, method: String, body: String?, contentType: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 40_000
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "application/json")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", contentType)
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
            val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (code !in 200..299) {
                val msg = try {
                    val j = JSONObject(text)
                    j.optString("error").ifBlank { j.optString("message") }.ifBlank { text.take(200) }
                } catch (_: Exception) {
                    text.take(200)
                }
                throw ApiException("HTTP $code: $msg")
            }
            return text
        } finally {
            c.disconnect()
        }
    }

    // ---------------------------------------------------------------- Photon search

    fun search(query: String, bias: LatLng?): List<Models.Suggestion> {
        val sb = StringBuilder("https://photon.komoot.io/api/?limit=8&q=").append(enc(query))
        if (bias != null) sb.append("&lat=").append(bias.lat).append("&lon=").append(bias.lng)
        val j = JSONObject(request(sb.toString(), "GET", null, ""))
        val features = j.optJSONArray("features") ?: return emptyList()
        val out = ArrayList<Models.Suggestion>()
        for (i in 0 until features.length()) {
            val f = features.getJSONObject(i)
            val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            val p = f.optJSONObject("properties") ?: JSONObject()
            val street = listOf(p.optString("street"), p.optString("housenumber")).filter { it.isNotBlank() }.joinToString(" ")
            val main = p.optString("name").ifBlank { street }.ifBlank { p.optString("city") }
            if (main.isBlank()) continue
            val parts = ArrayList<String>()
            if (street.isNotBlank() && street != main) parts.add(street)
            for (k in listOf("district", "city", "state", "country")) {
                val v = p.optString(k)
                if (v.isNotBlank() && v != main && !parts.contains(v)) parts.add(v)
            }
            val ll = LatLng(coords.getDouble(1), coords.getDouble(0))
            out.add(Models.Suggestion("", main, parts.take(3).joinToString(", "), ll))
        }
        return out
    }

    // ---------------------------------------------------------------- Valhalla routing

    fun route(from: LatLng, to: LatLng): Models.Route {
        val body = JSONObject()
            .put(
                "locations",
                JSONArray()
                    .put(JSONObject().put("lat", from.lat).put("lon", from.lng))
                    .put(JSONObject().put("lat", to.lat).put("lon", to.lng))
            )
            .put("costing", "auto")
            .put(
                "costing_options",
                JSONObject().put(
                    "auto",
                    JSONObject()
                        .put("use_tolls", if (Prefs.avoidTolls) 0.0 else 0.5)
                        .put("use_highways", if (Prefs.avoidHighways) 0.1 else 1.0)
                        .put("use_ferry", if (Prefs.avoidFerries) 0.0 else 0.5)
                )
            )
            .put("units", "kilometers")
            .put("language", "tr-TR")
        val j = JSONObject(request("$VALHALLA/route", "POST", body.toString(), "application/json"))
        val trip = j.optJSONObject("trip") ?: throw ApiException("Rota bulunamadı")
        val legs = trip.optJSONArray("legs") ?: throw ApiException("Rota bulunamadı")
        val steps = ArrayList<Models.Step>()
        val maneuvers = ArrayList<Models.Maneuver>()
        val all = ArrayList<LatLng>()
        val limits = ArrayList<Pair<LatLng, Int>>()
        for (i in 0 until legs.length()) {
            val leg = legs.getJSONObject(i)
            val shape = leg.optString("shape")
            val pts = Geo.decode(shape, 6)
            all.addAll(pts)
            try {
                limits.addAll(speedLimits(shape))
            } catch (e: Exception) {
                ObdManager.log("Hız sınırları alınamadı: ${e.message}")
            }
            val mans = leg.optJSONArray("maneuvers") ?: continue
            for (k in 0 until mans.length()) {
                val m = mans.getJSONObject(k)
                val a = m.optInt("begin_shape_index", 0).coerceIn(0, maxOf(0, pts.size - 1))
                val text = m.optString("instruction")
                if (text.isNotBlank() && pts.isNotEmpty()) {
                    val voice = m.optString("verbal_pre_transition_instruction").ifBlank { text }
                    maneuvers.add(Models.Maneuver(pts[a], text, voice, m.optInt("type", 0)))
                }
                val b = m.optInt("end_shape_index", a).coerceIn(a, maxOf(0, pts.size - 1))
                if (b <= a) continue
                steps.add(
                    Models.Step(
                        (m.optDouble("length", 0.0) * 1000.0).toInt(),
                        m.optDouble("time", 0.0),
                        Geo.encode(pts.subList(a, b + 1))
                    )
                )
            }
        }
        val summary = trip.optJSONObject("summary") ?: JSONObject()
        val time = summary.optDouble("time", 0.0)
        return Models.Route(
            (summary.optDouble("length", 0.0) * 1000.0).toInt(),
            time,
            time,
            Geo.encode(all),
            steps,
            maneuvers,
            hasToll = summary.optBoolean("has_toll", false),
            hasFerry = summary.optBoolean("has_ferry", false),
            speedLimits = limits
        )
    }

    /** Speed limits along a Valhalla route shape (polyline6), from OpenStreetMap maxspeed data. */
    private fun speedLimits(shape: String): List<Pair<LatLng, Int>> {
        val body = JSONObject()
            .put("encoded_polyline", shape)
            .put("shape_match", "edge_walk")
            .put("costing", "auto")
            .put(
                "filters",
                JSONObject()
                    .put("attributes", JSONArray().put("edge.speed_limit").put("edge.begin_shape_index").put("shape"))
                    .put("action", "include")
            )
        val j = JSONObject(request("$VALHALLA/trace_attributes", "POST", body.toString(), "application/json"))
        val pts = Geo.decode(j.optString("shape").ifBlank { shape }, 6)
        val edges = j.optJSONArray("edges") ?: return emptyList()
        val out = ArrayList<Pair<LatLng, Int>>()
        var last = -1
        for (k in 0 until edges.length()) {
            val e = edges.getJSONObject(k)
            val idx = e.optInt("begin_shape_index", -1)
            if (idx < 0 || idx >= pts.size) continue
            val lim = e.optInt("speed_limit", 0).let { if (it in 5..200) it else 0 }
            if (lim != last) {
                out.add(pts[idx] to lim)
                last = lim
            }
        }
        return out
    }

    /** Heights in metres for the given points. */
    fun heights(points: List<LatLng>): List<Double> {
        val shape = JSONArray()
        for (p in points) shape.put(JSONObject().put("lat", p.lat).put("lon", p.lng))
        val body = JSONObject().put("range", false).put("shape", shape)
        val j = JSONObject(request("$VALHALLA/height", "POST", body.toString(), "application/json"))
        val h = j.optJSONArray("height") ?: throw ApiException("Yükseklik verisi yok")
        if (h.length() != points.size) throw ApiException("Yükseklik verisi eksik")
        return List(h.length()) { h.optDouble(it, 0.0) }
    }

    // ---------------------------------------------------------------- Overpass chargers

    /** Runs an Overpass query, trying the next server when one is busy. */
    private fun overpassRaw(q: String): String {
        var err: Exception? = null
        for (url in OVERPASS) {
            try {
                return request(url, "POST", "data=" + enc(q), "application/x-www-form-urlencoded")
            } catch (e: Exception) {
                err = e
            }
        }
        throw err ?: ApiException("OpenStreetMap sunucusuna ulaşılamadı")
    }

    /** Fixed speed cameras within ~60 m of a line through [points]. */
    fun speedCamerasAlong(points: List<LatLng>): List<LatLng> {
        val coords = points.joinToString(",") { String.format(Locale.US, "%.5f,%.5f", it.lat, it.lng) }
        val q = "[out:json][timeout:25];node[\"highway\"=\"speed_camera\"](around:60,$coords);out;"
        val els = JSONObject(overpassRaw(q)).optJSONArray("elements") ?: return emptyList()
        return List(els.length()) { LatLng(els.getJSONObject(it).optDouble("lat"), els.getJSONObject(it).optDouble("lon")) }
    }

    private fun overpass(filter: String, minKw: Int): List<Models.Charger> {
        val q = "[out:json][timeout:25];nwr[\"amenity\"=\"charging_station\"]$filter;out center tags;"
        val text = overpassRaw(q)
        val els = JSONObject(text).optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<Models.Charger>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val tags = e.optJSONObject("tags") ?: JSONObject()
            val lat: Double
            val lon: Double
            if (e.has("lat")) {
                lat = e.optDouble("lat")
                lon = e.optDouble("lon")
            } else {
                val c = e.optJSONObject("center") ?: continue
                lat = c.optDouble("lat")
                lon = c.optDouble("lon")
            }
            var anySocket = false
            var hasCombo = false
            val keys = tags.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k.startsWith("socket:")) anySocket = true
                if (k.startsWith("socket:type2_combo") || k.startsWith("socket:ccs")) hasCombo = true
            }
            if (anySocket && !hasCombo) continue // AC only
            val kw = maxOf(
                parseKw(tags.optString("socket:type2_combo:output")),
                parseKw(tags.optString("socket:ccs:output")),
                if (hasCombo) parseKw(tags.optString("charging_station:output")) else 0.0
            )
            if (kw > 0 && kw < minKw) continue
            val name = tags.optString("name")
                .ifBlank { tags.optString("brand") }
                .ifBlank { tags.optString("operator") }
                .ifBlank { "Şarj istasyonu" }
            val operator = tags.optString("operator").ifBlank { tags.optString("brand") }
            val addr = listOf(
                operator.takeIf { it.isNotBlank() && it != name },
                tags.optString("addr:street").takeIf { it.isNotBlank() },
                tags.optString("addr:city").takeIf { it.isNotBlank() }
            ).filterNotNull().joinToString(" · ")
            out.add(
                Models.Charger(
                    id = "osm-${e.optString("type")}-${e.optLong("id")}",
                    name = name,
                    address = addr,
                    loc = LatLng(lat, lon),
                    maxKw = kw,
                    connectors = tags.optString("capacity").toIntOrNull() ?: 0,
                    dcKnown = hasCombo
                )
            )
        }
        return out
    }

    /** "150 kW", "50", "22 kW;50 kW" → the largest number in kW. */
    private fun parseKw(s: String): Double {
        if (s.isBlank()) return 0.0
        var best = 0.0
        for (m in Regex("([0-9]+(?:[.,][0-9]+)?)\\s*(kW|KW|kw|W)?").findAll(s)) {
            var v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: continue
            if (m.groupValues[2] == "W") v /= 1000.0
            if (v > best) best = v
        }
        return best
    }

    /** Stations within [radiusM] of a line through [points] (keep it under ~100 points). */
    fun chargersAlong(points: List<LatLng>, radiusM: Int, minKw: Int): List<Models.Charger> {
        val coords = points.joinToString(",") {
            String.format(Locale.US, "%.5f,%.5f", it.lat, it.lng)
        }
        return overpass("(around:$radiusM,$coords)", minKw)
    }

    fun nearbyChargers(center: LatLng, radiusM: Double, minKw: Int): List<Models.Charger> {
        val r = radiusM.coerceIn(500.0, 30000.0).toInt()
        val c = String.format(Locale.US, "%.5f,%.5f", center.lat, center.lng)
        return overpass("(around:$r,$c)", minKw).take(60)
    }
}

/** The services the app uses for search and stations. */
object Providers {
    fun search(query: String, bias: LatLng?): List<Models.Suggestion> = FreeApi.search(query, bias)

    /** TomTom stations (complete data) merged with OpenStreetMap ones; works if either answers. */
    fun nearbyChargers(center: LatLng, radiusM: Double, minKw: Int): List<Models.Charger> {
        val a = if (TomTom.enabled) runCatching { TomTom.chargersNearby(center, radiusM, minKw) } else null
        val b = runCatching { FreeApi.nearbyChargers(center, radiusM, minKw) }
        val tt = a?.getOrNull()
        val osm = b.getOrNull()
        if (tt == null && osm == null) {
            throw (a?.exceptionOrNull() ?: b.exceptionOrNull() ?: ApiException("İstasyonlar alınamadı"))
        }
        return merge(tt.orEmpty(), osm.orEmpty())
    }

    fun chargersAlong(points: List<LatLng>, minKw: Int): List<Models.Charger> {
        val a = if (TomTom.enabled) runCatching { TomTom.chargersAlong(points, minKw) } else null
        val b = runCatching { FreeApi.chargersAlong(Geo.thin(points, 80), 1500, minKw) }
        val tt = a?.getOrNull()
        val osm = b.getOrNull()
        if (tt == null && osm == null) {
            throw (a?.exceptionOrNull() ?: b.exceptionOrNull() ?: ApiException("İstasyonlar alınamadı"))
        }
        return merge(tt.orEmpty(), osm.orEmpty())
    }

    /** Keeps TomTom's entry when both sources list the same station (within 80 m). */
    private fun merge(primary: List<Models.Charger>, extra: List<Models.Charger>): List<Models.Charger> {
        val out = ArrayList(primary)
        for (c in extra) {
            if (out.none { Geo.dist(it.loc, c.loc) < 80 }) out.add(c)
        }
        return out
    }
}

/**
 * TomTom: live traffic with a free key (no credit card; 2,500 requests and 50,000 map tiles a day).
 * The key is pasted in the app's menu.
 */
object TomTom {
    val enabled: Boolean
        get() = Prefs.tomtomKey.isNotBlank()

    /** Map tiles that colour roads by live traffic, for dark maps. */
    fun flowTileUrl(): String =
        "https://api.tomtom.com/traffic/map/4/tile/flow/relative0-dark/{z}/{x}/{y}.png?tileSize=256&key=" +
            URLEncoder.encode(Prefs.tomtomKey, "UTF-8")

    private fun call(url: String, body: String?): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
            val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (code !in 200..299) {
                val msg = try {
                    val j = JSONObject(text)
                    j.optJSONObject("detailedError")?.optString("message")
                        ?: j.optString("errorText").ifBlank { text.take(200) }
                } catch (_: Exception) {
                    text.take(200)
                }
                throw ApiException("TomTom HTTP $code: $msg")
            }
            return JSONObject(text)
        } finally {
            c.disconnect()
        }
    }

    private fun key(): String = URLEncoder.encode(Prefs.tomtomKey, "UTF-8")

    /** TomTom POI results → stations with CCS power where known. */
    private fun parseStations(j: JSONObject, minKw: Int): List<Models.Charger> {
        val results = j.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Models.Charger>()
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            val pos = r.optJSONObject("position") ?: continue
            val poi = r.optJSONObject("poi") ?: JSONObject()
            val conns = r.optJSONObject("chargingPark")?.optJSONArray("connectors")
            var hasCcs = false
            var anyConn = false
            var kw = 0.0
            var count = 0
            if (conns != null) {
                for (k in 0 until conns.length()) {
                    val cn = conns.getJSONObject(k)
                    anyConn = true
                    count++
                    if (cn.optString("connectorType").contains("CCS", ignoreCase = true)) {
                        hasCcs = true
                        kw = maxOf(kw, cn.optDouble("ratedPowerKW", 0.0))
                    }
                }
            }
            if (anyConn && !hasCcs) continue // AC only
            if (kw > 0 && kw < minKw) continue
            val brand = poi.optJSONArray("brands")?.optJSONObject(0)?.optString("name").orEmpty()
            val name = poi.optString("name").ifBlank { brand }.ifBlank { "Şarj istasyonu" }
            val addr = listOf(
                brand.takeIf { it.isNotBlank() && it != name },
                r.optJSONObject("address")?.optString("freeformAddress")?.takeIf { it.isNotBlank() }
            ).filterNotNull().joinToString(" · ")
            out.add(
                Models.Charger(
                    id = "tt-" + r.optString("id"),
                    name = name,
                    address = addr,
                    loc = LatLng(pos.optDouble("lat"), pos.optDouble("lon")),
                    maxKw = kw,
                    connectors = count,
                    dcKnown = hasCcs
                )
            )
        }
        return out
    }

    fun chargersNearby(center: LatLng, radiusM: Double, minKw: Int): List<Models.Charger> {
        val url = String.format(
            Locale.US,
            "https://api.tomtom.com/search/2/nearbySearch/.json?lat=%.6f&lon=%.6f&radius=%d&limit=100&categorySet=7309&language=tr-TR&key=%s",
            center.lat, center.lng, radiusM.coerceIn(500.0, 50000.0).toInt(), key()
        )
        return parseStations(call(url, null), minKw)
    }

    /** Stations reachable with a short detour from a route (one call; at most 20 results). */
    fun chargersAlong(points: List<LatLng>, minKw: Int): List<Models.Charger> {
        val arr = JSONArray()
        for (p in Geo.thin(points, 150)) arr.put(JSONObject().put("lat", p.lat).put("lon", p.lng))
        val body = JSONObject().put("route", JSONObject().put("points", arr)).toString()
        val url = "https://api.tomtom.com/search/2/searchAlongRoute/electric%20vehicle%20station.json" +
            "?maxDetourTime=600&limit=20&categorySet=7309&spreadingMode=auto&language=tr-TR&key=" + key()
        return parseStations(call(url, body), minKw)
    }

    /** Driving time with current traffic, in seconds. */
    fun travelTimeS(from: LatLng, to: LatLng): Double {
        val url = String.format(
            Locale.US,
            "https://api.tomtom.com/routing/1/calculateRoute/%.6f,%.6f:%.6f,%.6f/json?traffic=true&travelMode=car&routeType=fastest&key=%s",
            from.lat, from.lng, to.lat, to.lng, URLEncoder.encode(Prefs.tomtomKey, "UTF-8")
        )
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
            val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (code !in 200..299) {
                val msg = try {
                    JSONObject(text).optJSONObject("detailedError")?.optString("message") ?: text.take(200)
                } catch (_: Exception) {
                    text.take(200)
                }
                throw ApiException("TomTom HTTP $code: $msg")
            }
            val routes = JSONObject(text).optJSONArray("routes") ?: throw ApiException("TomTom rota vermedi")
            val summary = routes.getJSONObject(0).getJSONObject("summary")
            return summary.getDouble("travelTimeInSeconds")
        } finally {
            c.disconnect()
        }
    }
}
