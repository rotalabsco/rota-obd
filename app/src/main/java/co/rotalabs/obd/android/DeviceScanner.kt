package co.rotalabs.obd.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class FoundDevice(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val ble: Boolean,
    val rssi: Int?,
) {
    /** Palpite: nomes típicos de adaptadores OBD. */
    val looksLikeObd: Boolean
        get() = listOf("OBD", "ELM", "VGATE", "ICAR", "VLINK", "V-LINK", "OBDLINK", "KONNWEI", "VIECAR", "CAN")
            .any { name.uppercase().contains(it) }
}

/** Lista adaptadores pareados (Bluetooth clássico) e procura adaptadores BLE próximos. */
@SuppressLint("MissingPermission")
class DeviceScanner(context: Context) {
    val adapter: BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val _devices = MutableStateFlow<List<FoundDevice>>(emptyList())
    val devices: StateFlow<List<FoundDevice>> = _devices
    private var scanning = false

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device
            val name = result.scanRecord?.deviceName ?: d.name ?: return
            add(FoundDevice(d, name, d.address, ble = true, rssi = result.rssi))
        }
    }

    private fun add(found: FoundDevice) {
        val key = found.address + found.ble
        val list = _devices.value.filterNot { it.address + it.ble == key } + found
        _devices.value = list.sortedWith(compareByDescending<FoundDevice> { it.looksLikeObd }.thenBy { it.name })
    }

    fun start() {
        val a = adapter ?: return
        _devices.value = emptyList()
        a.bondedDevices?.forEach { d ->
            add(FoundDevice(d, d.name ?: d.address, d.address, ble = false, rssi = null))
        }
        val scanner = a.bluetoothLeScanner ?: return
        if (!scanning) {
            scanning = true
            // Sem filtro por serviço: muitos adaptadores não anunciam UUIDs.
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        }
    }

    fun stop() {
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
    }
}
