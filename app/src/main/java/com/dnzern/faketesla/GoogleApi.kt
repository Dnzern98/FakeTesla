package com.dnzern.faketesla

/** Error from one of the online services, with a message for the user. */
class ApiException(msg: String) : Exception(msg)

/** Shared data shapes for search results, routes and charging stations. */
object Models {
    class Suggestion(val placeId: String, val main: String, val secondary: String, val latLng: LatLng? = null)

    class Step(val distanceM: Int, val staticDurationS: Double, val polyline: String)

    class Maneuver(val loc: LatLng, val text: String, val voice: String)

    class Route(
        val distanceM: Int,
        val durationS: Double,
        val staticDurationS: Double,
        val polyline: String,
        val steps: List<Step>,
        val maneuvers: List<Maneuver> = emptyList()
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
}
