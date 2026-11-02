package com.dnzern.faketesla

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FoundDevice(val device: BluetoothDevice, val name: String, val rssi: Int)

/**
 * App-wide owner of the OBD connection, GPS and drive log.
 * Lives as long as the app process, so the phone screen and Android Auto see the same data.
 */
@SuppressLint("MissingPermission")
object ObdManager {

    private lateinit var app: Context
    private lateinit var ble: ObdBle
    private lateinit var locationManager: LocationManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val status = MutableStateFlow("Bağlı değil")
    val scanning = MutableStateFlow(false)
    val connected = MutableStateFlow(false)
    val devices = MutableStateFlow<List<FoundDevice>>(emptyList())
    val values = MutableStateFlow<Map<String, Double>>(emptyMap())
    val speedKmh = MutableStateFlow<Double?>(null)
    val tripKm = MutableStateFlow(0.0)
    val tripWh = MutableStateFlow(0.0)
    val logLines = MutableStateFlow<List<String>>(emptyList())
    val logFile = MutableStateFlow<File?>(null)
    val location = MutableStateFlow<Location?>(null)
    val invertCurrent = MutableStateFlow(false)

    private var pollJob: Job? = null
    private var scanTimeout: Job? = null
    private var currentHeader: String? = null
    private val failCount = HashMap<String, Int>()
    private var lastTripLocation: Location? = null
    private var writer: BufferedWriter? = null
    private var locationOn = false
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun init(context: Context) {
        app = context.applicationContext
        ble = ObdBle(app) { msg -> log(msg) }
        locationManager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        invertCurrent.value = Prefs.invertCurrent
        scope.launch {
            ble.connected.collect { isUp ->
                if (!isUp && connected.value) {
                    status.value = "Bağlantı koptu"
                    stopSession()
                }
            }
        }
    }

    fun log(msg: String) {
        val line = "${timeFmt.format(Date())}  $msg"
        logLines.update { (it + line).takeLast(300) }
    }

    fun setInvertCurrent(v: Boolean) {
        invertCurrent.value = v
        Prefs.invertCurrent = v
    }

    // ---------------------------------------------------------------- scanning

    fun startScan() {
        if (!ble.isBluetoothOn()) {
            status.value = "Bluetooth kapalı, lütfen açın"
            return
        }
        devices.value = emptyList()
        scanning.value = true
        status.value = "Cihazlar aranıyor…"
        try {
            ble.startScan { dev, name, rssi ->
                scope.launch {
                    val fd = FoundDevice(dev, name, rssi)
                    val list = devices.value.filter { it.device.address != dev.address } + fd
                    devices.value = list.sortedByDescending { looksLikeObd(it.name) }
                }
            }
        } catch (e: SecurityException) {
            status.value = "Bluetooth izni yok"
            scanning.value = false
            return
        }
        scanTimeout?.cancel()
        scanTimeout = scope.launch {
            delay(15_000L)
            stopScan()
        }
    }

    fun stopScan() {
        try {
            ble.stopScan()
        } catch (_: Exception) {
        }
        scanning.value = false
        if (!connected.value) {
            status.value = if (devices.value.isEmpty()) "Cihaz bulunamadı" else "Listeden adaptörünü seç"
        }
    }

    fun looksLikeObd(name: String): Boolean {
        val n = name.lowercase()
        return listOf("vlink", "obd", "icar", "vgate", "elm", "v-link").any { n.contains(it) }
    }

    // ---------------------------------------------------------------- connection

    fun connect(d: FoundDevice) {
        scanTimeout?.cancel()
        try {
            ble.stopScan()
        } catch (_: Exception) {
        }
        scanning.value = false
        scope.launch {
            status.value = "${d.name} bağlanıyor…"
            val ok = try {
                ble.connect(d.device)
            } catch (e: SecurityException) {
                false
            }
            if (!ok) {
                status.value = "Bağlanamadı, tekrar dene"
                return@launch
            }
            connected.value = true
            status.value = "Adaptör hazırlanıyor…"
            initElm()
            tripKm.value = 0.0
            tripWh.value = 0.0
            lastTripLocation = null
            startCsv()
            startLocation()
            startDriveService()
            status.value = "Bağlı: ${d.name}"
            pollJob = scope.launch { poll() }
        }
    }

    fun disconnect() {
        ble.disconnect()
        stopSession()
        status.value = "Bağlı değil"
    }

    private fun stopSession() {
        connected.value = false
        pollJob?.cancel()
        pollJob = null
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        writer = null
        try {
            app.stopService(Intent(app, DriveService::class.java))
        } catch (_: Exception) {
        }
    }

    private fun startDriveService() {
        try {
            ContextCompat.startForegroundService(app, Intent(app, DriveService::class.java))
        } catch (e: Exception) {
            log("Arka plan servisi başlatılamadı: ${e.message}")
        }
    }

    private suspend fun initElm() {
        val cmds = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP6")
        for (c in cmds) {
            val r = ble.send(c, if (c == "ATZ") 6000L else 3000L)
            log("$c → ${r.replace("\n", " | ")}")
        }
        currentHeader = null
        failCount.clear()
    }

    private suspend fun poll() {
        var lastT = System.currentTimeMillis()
        while (connected.value) {
            for (p in EC3.PIDS) {
                if (!connected.value) break
                if (currentHeader != p.header) {
                    ble.send("ATSH${p.header}")
                    currentHeader = p.header
                }
                val r = ble.send(p.command)
                val v = try {
                    parseMode22(r, p.command)?.let(p.formula)
                } catch (e: Exception) {
                    null
                }
                if (v != null) {
                    values.update { it + (p.key to v) }
                } else {
                    val n = (failCount[p.key] ?: 0) + 1
                    failCount[p.key] = n
                    if (n <= 3) log("${p.label} okunamadı (${p.command}): ${r.replace("\n", " | ")}")
                }
            }
            val now = System.currentTimeMillis()
            val volt = values.value["volt"]
            val amp = values.value["amp"]
            if (volt != null && amp != null) {
                val sign = if (invertCurrent.value) -1.0 else 1.0
                val kw = volt * amp * sign / 1000.0
                values.update { it + ("kw" to kw) }
                tripWh.value = tripWh.value + kw * 1000.0 * (now - lastT) / 3_600_000.0
            }
            lastT = now
            writeCsvRow(now)
            delay(250L)
        }
    }

    fun sendRaw(cmd: String) {
        val c = cmd.trim().uppercase()
        if (c.isEmpty()) return
        scope.launch {
            val r = ble.send(c)
            if (c.startsWith("AT")) currentHeader = null
            log("> $c")
            log(r.replace("\n", " | "))
        }
    }

    // ---------------------------------------------------------------- estimates

    fun socNow(): Double? = values.value["bms_soc"] ?: values.value["soc"]

    fun consumptionWhPerKm(): Double? {
        val km = tripKm.value
        return if (km > 0.5) tripWh.value / km else null
    }

    fun whPerKmUsed(): Double {
        val c = consumptionWhPerKm()
        return if (c != null && c > 60.0 && c < 400.0) c else EC3.DEFAULT_WH_PER_KM
    }

    fun estimatedRangeKm(): Double? {
        val soc = socNow() ?: return null
        return EC3.USABLE_KWH * soc / 100.0 * 1000.0 / whPerKmUsed()
    }

    // ---------------------------------------------------------------- GPS

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            val prevBest = location.value
            val isGps = loc.provider == LocationManager.GPS_PROVIDER
            if (isGps || prevBest == null || prevBest.provider != LocationManager.GPS_PROVIDER ||
                loc.time - prevBest.time > 15_000L
            ) {
                location.value = loc
            }
            if (!isGps) return
            if (loc.hasSpeed()) speedKmh.value = loc.speed * 3.6
            if (connected.value && loc.accuracy < 30f) {
                val prev = lastTripLocation
                if (prev != null) tripKm.value = tripKm.value + prev.distanceTo(loc) / 1000.0
                lastTripLocation = loc
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        }

        override fun onProviderEnabled(provider: String) {}

        override fun onProviderDisabled(provider: String) {}
    }

    fun startLocation() {
        if (locationOn) return
        var any = false
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.requestLocationUpdates(provider, 1000L, 0f, locationListener, Looper.getMainLooper())
                    any = true
                }
            } catch (_: SecurityException) {
            } catch (_: IllegalArgumentException) {
            }
        }
        locationOn = any
        if (location.value == null) location.value = lastKnown()
    }

    /** Stops GPS when nothing needs it (app in background and car not connected). */
    fun stopLocationIfIdle() {
        if (connected.value || !locationOn) return
        try {
            locationManager.removeUpdates(locationListener)
        } catch (_: Exception) {
        }
        locationOn = false
    }

    private fun lastKnown(): Location? {
        var best: Location? = null
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            try {
                val l = locationManager.getLastKnownLocation(provider) ?: continue
                if (best == null || l.time > best.time) best = l
            } catch (_: Exception) {
            }
        }
        return best
    }

    fun currentLatLng(): LatLng? {
        val l = location.value ?: lastKnown() ?: return null
        return LatLng(l.latitude, l.longitude)
    }

    // ---------------------------------------------------------------- CSV log

    private fun startCsv() {
        val dir = app.getExternalFilesDir("logs") ?: return
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(dir, "surus_$stamp.csv")
        try {
            val w = f.bufferedWriter()
            w.write("zaman_ms,soc_gosterge,soc_bms,volt,amper,kw,hiz_kmh,enlem,boylam,mesafe_km,enerji_wh\n")
            w.flush()
            writer = w
            logFile.value = f
            log("Kayıt dosyası: ${f.name}")
        } catch (e: Exception) {
            log("Kayıt dosyası açılamadı: ${e.message}")
        }
    }

    private fun num(v: Double?): String = if (v == null) "" else String.format(Locale.US, "%.5f", v)

    private fun writeCsvRow(now: Long) {
        val w = writer ?: return
        val loc = lastTripLocation
        val vals = values.value
        val row = listOf(
            now.toString(),
            num(vals["soc"]),
            num(vals["bms_soc"]),
            num(vals["volt"]),
            num(vals["amp"]),
            num(vals["kw"]),
            num(speedKmh.value),
            num(loc?.latitude),
            num(loc?.longitude),
            num(tripKm.value),
            num(tripWh.value)
        ).joinToString(",")
        try {
            w.write(row)
            w.write("\n")
            w.flush()
        } catch (_: Exception) {
        }
    }
}
