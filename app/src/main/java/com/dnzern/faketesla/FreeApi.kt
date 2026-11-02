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
    private const val OVERPASS = "https://overpass-api.de/api/interpreter"

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
            .put("units", "kilometers")
            .put("language", "tr-TR")
        val j = JSONObject(request("$VALHALLA/route", "POST", body.toString(), "application/json"))
        val trip = j.optJSONObject("trip") ?: throw ApiException("Rota bulunamadı")
        val legs = trip.optJSONArray("legs") ?: throw ApiException("Rota bulunamadı")
        val steps = ArrayList<Models.Step>()
        val maneuvers = ArrayList<Models.Maneuver>()
        val all = ArrayList<LatLng>()
        for (i in 0 until legs.length()) {
            val leg = legs.getJSONObject(i)
            val pts = Geo.decode(leg.optString("shape"), 6)
            all.addAll(pts)
            val mans = leg.optJSONArray("maneuvers") ?: continue
            for (k in 0 until mans.length()) {
                val m = mans.getJSONObject(k)
                val a = m.optInt("begin_shape_index", 0).coerceIn(0, maxOf(0, pts.size - 1))
                val text = m.optString("instruction")
                if (text.isNotBlank() && pts.isNotEmpty()) {
                    val voice = m.optString("verbal_pre_transition_instruction").ifBlank { text }
                    maneuvers.add(Models.Maneuver(pts[a], text, voice))
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
            maneuvers
        )
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

    private fun overpass(filter: String, minKw: Int): List<Models.Charger> {
        val q = "[out:json][timeout:25];nwr[\"amenity\"=\"charging_station\"]$filter;out center tags;"
        val text = request(OVERPASS, "POST", "data=" + enc(q), "application/x-www-form-urlencoded")
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

/** The services the app uses for search and nearby stations. */
object Providers {
    fun search(query: String, bias: LatLng?): List<Models.Suggestion> = FreeApi.search(query, bias)

    fun nearbyChargers(center: LatLng, radiusM: Double, minKw: Int): List<Models.Charger> =
        FreeApi.nearbyChargers(center, radiusM, minKw)
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
