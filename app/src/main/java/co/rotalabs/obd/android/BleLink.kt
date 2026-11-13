package co.rotalabs.obd.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import co.rotalabs.obd.core.ByteLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Canal BLE com adaptadores ELM327 (Vgate iCar Pro, clones FFF0, Microchip etc.).
 * Não assume UUIDs: procura um serviço com uma característica de notificação e uma de escrita,
 * preferindo os conhecidos.
 */
@SuppressLint("MissingPermission")
class BleLink(private val context: Context, private val device: BluetoothDevice) : ByteLink {
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var receiver: (ByteArray) -> Unit = {}
    private var mtu = 23
    private val writeMutex = Mutex()

    private var connected: CompletableDeferred<Boolean>? = null
    private var servicesDiscovered: CompletableDeferred<Boolean>? = null
    private var mtuChanged: CompletableDeferred<Int>? = null
    private var descriptorWritten: CompletableDeferred<Boolean>? = null
    private var charWritten: CompletableDeferred<Boolean>? = null

    var onDisconnected: () -> Unit = {}
    var describe: String = ""
        private set

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected?.complete(true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected?.complete(false)
                onDisconnected()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            servicesDiscovered?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            mtuChanged?.complete(mtu)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            descriptorWritten?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            charWritten?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            receiver(value)
        }

        @Deprecated("Android 12 ou anterior")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                c.value?.let { receiver(it) }
            }
        }
    }

    suspend fun connect() {
        connected = CompletableDeferred()
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        val ok = withTimeoutOrNull(15_000) { connected!!.await() } ?: false
        if (!ok) {
            close()
            error("Não conectou ao adaptador por BLE")
        }
        val g = gatt!!

        mtuChanged = CompletableDeferred()
        g.requestMtu(247)
        withTimeoutOrNull(3_000) { mtuChanged!!.await() }

        servicesDiscovered = CompletableDeferred()
        g.discoverServices()
        if (withTimeoutOrNull(10_000) { servicesDiscovered!!.await() } != true) error("Falha ao listar serviços BLE")

        val (notify, write) = pickCharacteristics(g) ?: error(
            "Nenhuma característica serial encontrada. Serviços: " +
                g.services.joinToString { it.uuid.toString().take(8) },
        )
        writeChar = write
        describe = "BLE ${notify.service.uuid.toString().take(8)} notif ${notify.uuid.toString().take(8)} " +
            "escrita ${write.uuid.toString().take(8)} MTU $mtu"

        g.setCharacteristicNotification(notify, true)
        val cccd = notify.getDescriptor(CCCD)
        if (cccd != null) {
            val value = if (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
            descriptorWritten = CompletableDeferred()
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, value)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
            withTimeoutOrNull(3_000) { descriptorWritten!!.await() }
        }
    }

    private fun pickCharacteristics(g: BluetoothGatt): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        val services = g.services.filterNot { it.uuid in IGNORED }
            .sortedBy { s -> PREFERRED.indexOf(s.uuid).let { if (it < 0) Int.MAX_VALUE else it } }
        for (s in services) {
            val notify = s.characteristics.firstOrNull {
                it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            } ?: continue
            val write = s.characteristics.firstOrNull {
                it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            } ?: continue
            return notify to write
        }
        return null
    }

    override suspend fun send(bytes: ByteArray) = writeMutex.withLock {
        val g = gatt ?: error("Desconectado")
        val c = writeChar ?: error("Desconectado")
        val withResponse = c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        val type = if (withResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        for (chunk in bytes.toList().chunked(maxOf(20, mtu - 3))) {
            val data = chunk.toByteArray()
            charWritten = CompletableDeferred()
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, data, type)
            } else {
                @Suppress("DEPRECATION")
                c.writeType = type
                @Suppress("DEPRECATION")
                c.value = data
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
            if (withResponse) {
                withTimeout(3_000) { charWritten!!.await() }
            } else {
                withTimeoutOrNull(500) { charWritten!!.await() }
            }
        }
    }

    override fun setReceiver(receiver: (ByteArray) -> Unit) {
        this.receiver = receiver
    }

    override suspend fun close() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    companion object {
        private fun short(x: String) = UUID.fromString("0000$x-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = short("2902")
        private val IGNORED = setOf(short("1800"), short("1801"), short("180a"), short("180f"))
        private val PREFERRED = listOf(
            UUID.fromString("e7810a71-73ae-499d-8c15-faa9aef0c3f2"), // Vgate iCar Pro BLE
            short("fff0"),
            short("ffe0"),
            short("18f0"),
            UUID.fromString("49535343-fe7d-4ae5-8fa9-9fafd205e455"), // Microchip Transparent UART
        )
    }
}
