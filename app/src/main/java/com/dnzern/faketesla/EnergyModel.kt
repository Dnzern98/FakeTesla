package com.dnzern.faketesla

import kotlin.math.min

/**
 * Simple physics model of the Citroën ë-C3 (44 kWh LFP, ~1.5 t with driver).
 * Tuned so that ~90 km/h ≈ 140 Wh/km and ~120 km/h ≈ 205 Wh/km.
 * The user can scale it with Prefs.consumptionFactor; real drive logs will refine it later.
 */
object EnergyModel {
    private const val MASS = 1500.0       // kg
    private const val G = 9.81
    private const val CRR = 0.010         // rolling resistance
    private const val CDA = 0.74          // drag coefficient × frontal area (m²)
    private const val RHO = 1.2           // air density
    private const val DRIVE_EFF = 0.87    // battery → wheels
    private const val REGEN_EFF = 0.60    // potential energy recovered downhill
    private const val AUX_W = 400.0       // climate, electronics

    /** Battery energy per km on flat road at a steady speed. */
    fun whPerKm(speedKmh: Double): Double {
        val v = (speedKmh / 3.6).coerceAtLeast(3.0)
        val force = CRR * MASS * G + 0.5 * RHO * CDA * v * v
        val drive = force * 1000.0 / 3600.0 / DRIVE_EFF
        val aux = AUX_W * (1000.0 / v) / 3600.0
        return (drive + aux) * Prefs.consumptionFactor
    }

    /** Extra battery energy (Wh) for a height change; negative when going downhill. */
    fun elevationWh(dh: Double): Double =
        if (dh > 0) MASS * G * dh / 3600.0 / DRIVE_EFF else MASS * G * dh / 3600.0 * REGEN_EFF

    // Estimated DC charging curve: battery % → kW the car accepts.
    private val curveSoc = doubleArrayOf(0.0, 10.0, 20.0, 45.0, 60.0, 70.0, 76.0, 80.0, 84.0, 90.0, 95.0, 100.0)
    private val curveKw = doubleArrayOf(48.0, 62.0, 75.0, 70.0, 64.0, 56.0, 49.0, 41.0, 35.0, 25.0, 16.0, 8.0)

    fun carKw(soc: Double): Double {
        val s = soc.coerceIn(0.0, 100.0)
        for (i in 1 until curveSoc.size) {
            if (s <= curveSoc[i]) {
                val f = (s - curveSoc[i - 1]) / (curveSoc[i] - curveSoc[i - 1])
                return curveKw[i - 1] + f * (curveKw[i] - curveKw[i - 1])
            }
        }
        return curveKw.last()
    }

    /** Minutes to charge from [fromPct] to [toPct] on a station rated [stationKw]. */
    fun chargeMinutes(fromPct: Double, toPct: Double, stationKw: Double): Double {
        if (toPct <= fromPct) return 0.0
        var t = 0.0
        var s = fromPct
        val stationLimit = stationKw * 0.92
        while (s < toPct - 1e-9) {
            val step = min(0.5, toPct - s)
            val p = min(carKw(s + step / 2), stationLimit).coerceAtLeast(3.0)
            t += (EC3.USABLE_KWH * step / 100.0) / p * 60.0
            s += step
        }
        return t
    }
}
