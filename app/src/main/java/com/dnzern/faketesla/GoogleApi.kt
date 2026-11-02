package com.dnzern.faketesla

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.max

class ApiException(msg: String) : Exception(msg)

/**
 * Minimal client for the Google Maps Platform web services we need.
 * All calls are blocking: run them on Dispatchers.IO.
 *
 * APIs to enable in Google Cloud: Places API (New), Routes API, Elevation API, Maps Static API.
 */
object GoogleApi {

    class Suggestion(val placeId: String, val main: String, val secondary: String, val latLng: LatLng? = null)

    class Step(val distanceM: Int, val staticDurationS: Double, val polyline: String)

    class Route(
        val distanceM: Int,
        val durationS: Double,
        val staticDurationS: Double,
        val polyline: String,
        val steps: List<Step>
    )

    class Charger(
        val id: String,
        val name: String,
        val address: String,
        val loc: LatLng,
        /** 0 when there is no power data for the station. */
        val maxKw: Double,
        val connectors: Int,
        /** false when we do not know whether the station has a fast (CCS) plug. */
        val dcKnown: Boolean = true
    )

    private fun key(): String {
        val k = Prefs.apiKey
        if (k.isBlank()) throw ApiException("Google API anahtarı girilmemiş (Ayarlar sekmesi)")
        return k
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun post(url: String, body: JSONObject, fieldMask: String?): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setRequestProperty("X-Goog-Api-Key", key())
            if (fieldMask != null) c.setRequestProperty("X-Goog-FieldMask", fieldMask)
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            return readJson(c)
        } finally {
            c.disconnect()
        }
    }

    private fun get(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            return readJson(c)
        } finally {
            c.disconnect()
        }
    }

    private fun readJson(c: HttpURLConnection): JSONObject {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        if (code !in 200..299) throw ApiException("HTTP $code: ${errorMessage(text)}")
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun errorMessage(text: String): String =
        try {
            val e = JSONObject(text).optJSONObject("error")
            e?.optString("message")?.takeIf { it.isNotBlank() } ?: text.take(300)
        } catch (_: Exception) {
            text.take(300)
        }

    fun latLngJson(p: LatLng): JSONObject =
        JSONObject().put(
            "location",
            JSONObject().put("latLng", JSONObject().put("latitude", p.lat).put("longitude", p.lng))
        )

    fun placeIdJson(placeId: String): JSONObject = JSONObject().put("placeId", placeId)

    // ---------------------------------------------------------------- Places autocomplete

    fun autocomplete(input: String, bias: LatLng?): List<Suggestion> {
        val body = JSONObject()
            .put("input", input)
            .put("languageCode", "tr")
            .put("regionCode", "tr")
        if (bias != null) {
            body.put(
                "locationBias",
                JSONObject().put(
                    "circle",
                    JSONObject()
                        .put("center", JSONObject().put("latitude", bias.lat).put("longitude", bias.lng))
                        .put("radius", 50000.0)
                )
            )
        }
        val r = post("https://places.googleapis.com/v1/places:autocomplete", body, null)
        val arr = r.optJSONArray("suggestions") ?: return emptyList()
        val out = ArrayList<Suggestion>()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i).optJSONObject("placePrediction") ?: continue
            val sf = p.optJSONObject("structuredFormat")
            val main = sf?.optJSONObject("mainText")?.optString("text")
                ?: p.optJSONObject("text")?.optString("text") ?: continue
            val secondary = sf?.optJSONObject("secondaryText")?.optString("text") ?: ""
            val id = p.optString("placeId")
            if (id.isNotBlank()) out.add(Suggestion(id, main, secondary))
        }
        return out
    }

    // ---------------------------------------------------------------- Routes API

    private fun seconds(s: String?): Double = s?.removeSuffix("s")?.toDoubleOrNull() ?: 0.0

    fun computeRoute(origin: JSONObject, destination: JSONObject): Route {
        val body = JSONObject()
            .put("origin", origin)
            .put("destination", destination)
            .put("travelMode", "DRIVE")
            .put("routingPreference", "TRAFFIC_AWARE")
            .put("languageCode", "tr-TR")
            .put("units", "METRIC")
        val mask = "routes.distanceMeters,routes.duration,routes.staticDuration," +
            "routes.polyline.encodedPolyline," +
            "routes.legs.steps.distanceMeters,routes.legs.steps.staticDuration," +
            "routes.legs.steps.polyline.encodedPolyline"
        val r = post("https://routes.googleapis.com/directions/v2:computeRoutes", body, mask)
        val routes = r.optJSONArray("routes")
        if (routes == null || routes.length() == 0) throw ApiException("Bu adrese rota bulunamadı")
        val rt = routes.getJSONObject(0)
        val steps = ArrayList<Step>()
        val legs = rt.optJSONArray("legs") ?: JSONArray()
        for (i in 0 until legs.length()) {
            val ls = legs.getJSONObject(i).optJSONArray("steps") ?: continue
            for (k in 0 until ls.length()) {
                val s = ls.getJSONObject(k)
                steps.add(
                    Step(
                        s.optInt("distanceMeters", 0),
                        seconds(s.optString("staticDuration")),
                        s.optJSONObject("polyline")?.optString("encodedPolyline") ?: ""
                    )
                )
            }
        }
        return Route(
            rt.optInt("distanceMeters", 0),
            seconds(rt.optString("duration")),
            seconds(rt.optString("staticDuration")),
            rt.optJSONObject("polyline")?.optString("encodedPolyline") ?: "",
            steps
        )
    }

    // ---------------------------------------------------------------- Elevation API

    /** Heights in metres for up to 512 points. */
    fun elevations(points: List<LatLng>): List<Double> {
        val url = "https://maps.googleapis.com/maps/api/elevation/json?locations=" +
            enc("enc:" + Geo.encode(points)) + "&key=" + enc(key())
        val r = get(url)
        val status = r.optString("status")
        if (status != "OK") throw ApiException("Yükseklik: $status ${r.optString("error_message")}")
        val res = r.getJSONArray("results")
        return List(res.length()) { res.getJSONObject(it).optDouble("elevation", 0.0) }
    }

    // ---------------------------------------------------------------- Places along route

    fun chargersAlong(encodedPolyline: String, minKw: Int): List<Charger> {
        val mask = "places.id,places.displayName,places.formattedAddress,places.location,places.evChargeOptions"
        val url = "https://places.googleapis.com/v1/places:searchText"
        val full = JSONObject()
            .put("textQuery", "elektrikli araç şarj istasyonu")
            .put("includedType", "electric_vehicle_charging_station")
            .put("languageCode", "tr")
            .put("pageSize", 20)
            .put("searchAlongRouteParameters", JSONObject().put("polyline", JSONObject().put("encodedPolyline", encodedPolyline)))
            .put(
                "evOptions",
                JSONObject()
                    .put("minimumChargingRateKw", minKw)
                    .put("connectorTypes", JSONArray().put("EV_CONNECTOR_TYPE_CCS_COMBO_2"))
            )
        val r = try {
            post(url, full, mask)
        } catch (e: ApiException) {
            // Fall back to the simplest request and filter on our side.
            val simple = JSONObject()
                .put("textQuery", "elektrikli araç şarj istasyonu")
                .put("languageCode", "tr")
                .put("searchAlongRouteParameters", JSONObject().put("polyline", JSONObject().put("encodedPolyline", encodedPolyline)))
            post(url, simple, mask)
        }
        return parseChargers(r, minKw)
    }

    /** Fast chargers around a point (for the map), at most 20. */
    fun nearbyChargers(center: LatLng, radiusM: Double, minKw: Int): List<Charger> {
        val mask = "places.id,places.displayName,places.formattedAddress,places.location,places.evChargeOptions"
        val body = JSONObject()
            .put("includedTypes", JSONArray().put("electric_vehicle_charging_station"))
            .put("maxResultCount", 20)
            .put("languageCode", "tr")
            .put(
                "locationRestriction",
                JSONObject().put(
                    "circle",
                    JSONObject()
                        .put("center", JSONObject().put("latitude", center.lat).put("longitude", center.lng))
                        .put("radius", radiusM.coerceIn(500.0, 50000.0))
                )
            )
        val r = post("https://places.googleapis.com/v1/places:searchNearby", body, mask)
        return parseChargers(r, minKw)
    }

    private fun parseChargers(r: JSONObject, minKw: Int): List<Charger> {
        val places = r.optJSONArray("places") ?: return emptyList()
        val out = ArrayList<Charger>()
        for (i in 0 until places.length()) {
            val p = places.getJSONObject(i)
            val locJ = p.optJSONObject("location") ?: continue
            val loc = LatLng(locJ.optDouble("latitude", 0.0), locJ.optDouble("longitude", 0.0))
            val ev = p.optJSONObject("evChargeOptions")
            var maxKw = 0.0
            var hasCcs = false
            var anyAgg = false
            val aggs = ev?.optJSONArray("connectorAggregation")
            if (aggs != null) {
                for (k in 0 until aggs.length()) {
                    val a = aggs.getJSONObject(k)
                    anyAgg = true
                    if (a.optString("type").contains("CCS")) {
                        hasCcs = true
                        maxKw = max(maxKw, a.optDouble("maxChargeRateKw", 0.0))
                    }
                }
            }
            if (anyAgg && !hasCcs) continue
            if (maxKw > 0 && maxKw < minKw) continue
            out.add(
                Charger(
                    p.optString("id"),
                    p.optJSONObject("displayName")?.optString("text") ?: "Şarj istasyonu",
                    p.optString("formattedAddress"),
                    loc,
                    maxKw,
                    ev?.optInt("connectorCount", 0) ?: 0
                )
            )
        }
        return out
    }

    // ---------------------------------------------------------------- Static map image

    fun staticMap(route: List<LatLng>, stops: List<LatLng>): Bitmap? {
        if (route.size < 2) return null
        val path = Geo.encode(Geo.thin(route, 250))
        val sb = StringBuilder("https://maps.googleapis.com/maps/api/staticmap?size=640x360&scale=2&language=tr")
        sb.append("&path=").append(enc("weight:5|color:0x3D8BFDFF|enc:$path"))
        sb.append("&markers=").append(enc("color:green|label:A|${route.first().str()}"))
        stops.forEachIndexed { i, s ->
            val label = if (i < 9) (i + 1).toString() else "S"
            sb.append("&markers=").append(enc("color:orange|label:$label|${s.str()}"))
        }
        sb.append("&markers=").append(enc("color:red|label:B|${route.last().str()}"))
        sb.append("&key=").append(enc(key()))
        val c = URL(sb.toString()).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            val code = c.responseCode
            if (code != 200) {
                val t = (c.errorStream ?: c.inputStream).bufferedReader().use { it.readText() }
                throw ApiException("Harita resmi: HTTP $code ${t.take(200)}")
            }
            return c.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            c.disconnect()
        }
    }
}
