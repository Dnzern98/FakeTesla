package com.dnzern.faketesla

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLng(val lat: Double, val lng: Double) {
    /** "lat,lng" with a dot decimal separator, safe for URLs. */
    fun str(): String = "$lat,$lng"
}

object Geo {

    /** Distance in metres. */
    fun dist(a: LatLng, b: LatLng): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Google encoded polyline → points (precision 5; Valhalla uses 6). */
    fun decode(enc: String, precision: Int = 5): List<LatLng> {
        val factor = if (precision == 6) 1e6 else 1e5
        val out = ArrayList<LatLng>()
        var index = 0
        var lat = 0
        var lng = 0
        while (index < enc.length) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = enc[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && index < enc.length)
            lat += if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            shift = 0
            result = 0
            do {
                b = enc[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && index < enc.length)
            lng += if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            out.add(LatLng(lat / factor, lng / factor))
        }
        return out
    }

    /** Points → Google encoded polyline. */
    fun encode(points: List<LatLng>): String {
        val sb = StringBuilder()
        var pLat = 0L
        var pLng = 0L
        for (p in points) {
            val lat = Math.round(p.lat * 1e5)
            val lng = Math.round(p.lng * 1e5)
            encodeValue(lat - pLat, sb)
            encodeValue(lng - pLng, sb)
            pLat = lat
            pLng = lng
        }
        return sb.toString()
    }

    private fun encodeValue(v: Long, sb: StringBuilder) {
        var x = if (v < 0) (v shl 1).inv() else (v shl 1)
        while (x >= 0x20) {
            sb.append(((0x20L or (x and 0x1f)) + 63).toInt().toChar())
            x = x shr 5
        }
        sb.append((x + 63).toInt().toChar())
    }

    /** Keeps at most [max] points, always including the first and last. */
    fun thin(points: List<LatLng>, max: Int): List<LatLng> {
        if (points.size <= max) return points
        val step = points.size.toDouble() / (max - 1)
        val out = ArrayList<LatLng>(max)
        var i = 0.0
        while (i < points.size - 1) {
            out.add(points[i.toInt()])
            i += step
        }
        out.add(points.last())
        return out
    }
}
