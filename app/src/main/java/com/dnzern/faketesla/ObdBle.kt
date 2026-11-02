package com.dnzern.faketesla

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Talks to an ELM327-compatible BLE adapter (e.g. Vgate vLinker MC+ BLE).
 * The adapter's service/characteristic UUIDs are discovered automatically:
 * the first non-standard service that has a notify and a write characteristic is used.
 */
@SuppressLint("MissingPermission")
class ObdBle(private val context: Context, private val log: (String) -> Unit) {

    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    private var ready: CompletableDeferred<Boolean>? = null

    private val rx = StringBuilder()
    @Volatile
    private var pending: CompletableDeferred<String>? = null
    private val sendLock = Mutex()

    val connected = MutableStateFlow(false)

    private var scanCallback: ScanCallback? = null

    fun isBluetoothOn(): Boolean = manager.adapter?.isEnabled == true

    fun startScan(onFound: (BluetoothDevice, String, Int) -> Unit) {
        val scanner = manager.adapter?.bluetoothLeScanner
        if (scanner == null) {
            log("Bluetooth kapalı veya desteklenmiyor")
            return
        }
        stopScan()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName ?: return
                onFound(result.device, name, result.rssi)
            }

            override fun onScanFailed(errorCode: Int) {
                log("Tarama hatası: $errorCode")
            }
        }
        scanCallback = cb
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, cb)
    }

    fun stopScan() {
        val cb = scanCallback ?: return
        try {
            manager.adapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (_: Exception) {
        }
        scanCallback = null
    }

    suspend fun connect(device: BluetoothDevice): Boolean {
        disconnect()
        val r = CompletableDeferred<Boolean>()
        ready = r
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        val ok = withTimeoutOrNull(20_000L) { r.await() } ?: false
        if (!ok) disconnect()
        connected.value = ok
        return ok
    }

    fun disconnect() {
        val g = gatt
        if (g != null) {
            try {
                g.disconnect()
                g.close()
            } catch (_: Exception) {
            }
        }
        gatt = null
        writeChar = null
        connected.value = false
        pending?.complete("DISCONNECTED")
    }

    /** Sends one ELM327 command and waits for the '>' prompt. Returns the cleaned response text. */
    suspend fun send(cmd: String, timeoutMs: Long = 4000L): String = sendLock.withLock {
        val g = gatt
        val c = writeChar
        if (g == null || c == null) return@withLock "BAGLI DEGIL"
        synchronized(rx) { rx.setLength(0) }
        val d = CompletableDeferred<String>()
        pending = d
        write(g, c, (cmd + "\r").toByteArray(Charsets.US_ASCII))
        val raw = withTimeoutOrNull(timeoutMs) { d.await() } ?: "ZAMAN ASIMI"
        pending = null
        clean(raw)
    }

    private fun clean(raw: String): String =
        raw.replace("\r", "\n")
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("SEARCHING") }
            .joinToString("\n")

    private fun onData(bytes: ByteArray) {
        val text = String(bytes, Charsets.ISO_8859_1)
        synchronized(rx) {
            rx.append(text)
            val idx = rx.indexOf(">")
            if (idx >= 0) {
                val resp = rx.substring(0, idx)
                rx.setLength(0)
                pending?.complete(resp)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun write(g: BluetoothGatt, c: BluetoothGattCharacteristic, data: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, data, writeType)
        } else {
            c.writeType = writeType
            c.value = data
            g.writeCharacteristic(c)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, value)
        } else {
            d.value = value
            g.writeDescriptor(d)
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("BLE bağlandı, servisler aranıyor…")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("BLE bağlantısı kapandı (durum $status)")
                connected.value = false
                ready?.complete(false)
                pending?.complete("DISCONNECTED")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            var chosenNotify: BluetoothGattCharacteristic? = null
            var chosenWrite: BluetoothGattCharacteristic? = null
            for (service in g.services) {
                val id = service.uuid.toString().lowercase()
                if (id.startsWith("00001800") || id.startsWith("00001801") || id.startsWith("0000180a")) continue
                log("Servis $id")
                var n: BluetoothGattCharacteristic? = null
                var w: BluetoothGattCharacteristic? = null
                for (c in service.characteristics) {
                    val p = c.properties
                    val canNotify = (p and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0
                    val canWrite = (p and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
                    log("  ${c.uuid} özellik=$p")
                    if (canNotify && canWrite) {
                        n = c
                        w = c
                        break
                    }
                    if (canNotify && n == null) n = c
                    if (canWrite && w == null) w = c
                }
                if (n != null && w != null) {
                    chosenNotify = n
                    chosenWrite = w
                    break
                }
            }
            if (chosenNotify == null || chosenWrite == null) {
                log("Uygun OBD karakteristiği bulunamadı")
                ready?.complete(false)
                return
            }
            writeChar = chosenWrite
            writeType = if ((chosenWrite.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            log("Yazma: ${chosenWrite.uuid}")
            log("Okuma: ${chosenNotify.uuid}")
            g.setCharacteristicNotification(chosenNotify, true)
            val cccd = chosenNotify.getDescriptor(CCCD)
            if (cccd == null) {
                ready?.complete(true)
                return
            }
            val value = if ((chosenNotify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
            writeDescriptor(g, cccd, value)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            log("Bildirimler açıldı (durum $status)")
            ready?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            onData(value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                val v = c.value
                if (v != null) onData(v)
            }
        }
    }

    companion object {
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
