package com.dnzern.faketesla

/**
 * A manufacturer-specific (mode 22) value read from the car.
 * [header] is the CAN ID of the ECU, [command] the full request (e.g. "224801").
 * [formula] receives the data bytes that follow the "62xxxx" positive response.
 */
class Pid(
    val key: String,
    val label: String,
    val header: String,
    val command: String,
    val formula: (IntArray) -> Double
)

/** Citroën ë-C3 (2024+, 44 kWh LFP) — PIDs reported working by the community via Car Scanner. */
object EC3 {
    const val USABLE_KWH = 44.0
    const val DEFAULT_WH_PER_KM = 169.0

    val PIDS = listOf(
        Pid("soc", "Şarj (gösterge)", "7E0", "222002") { b -> b[0] / 2.55 },
        Pid("bms_soc", "Şarj (BMS)", "7E7", "224801") { b -> (b[0] * 256 + b[1]) / 100.0 },
        Pid("volt", "Batarya voltajı", "7E7", "224802") { b -> (b[0] * 256 + b[1]) / 10.0 },
        Pid("amp", "Batarya akımı", "7E7", "224803") { b -> (b[0] * 256 + b[1] - 32768) / 10.0 },
    )
}

/** Extracts the data bytes from a mode 22 response such as "62480113F2". Returns null on NO DATA / errors. */
fun parseMode22(response: String, command: String): IntArray? {
    val up = response.uppercase()
    if (up.contains("NO DATA") || up.contains("ERROR") || up.contains("?") ||
        up.contains("ZAMAN") || up.contains("STOPPED") || up.contains("UNABLE")
    ) return null
    val hex = up.replace(Regex("[^0-9A-F]"), "")
    val prefix = "62" + command.uppercase().substring(2)
    val i = hex.indexOf(prefix)
    if (i < 0) return null
    val data = hex.substring(i + prefix.length)
    val n = data.length / 2
    if (n == 0) return null
    return IntArray(n) { data.substring(it * 2, it * 2 + 2).toInt(16) }
}
