package io.github.obdlivre.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import io.github.obdlivre.core.ByteLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread

/** Canal Bluetooth clássico (perfil serial SPP), usado pela maioria dos ELM327 baratos. */
@SuppressLint("MissingPermission")
class ClassicLink(private val adapter: BluetoothAdapter?, private val device: BluetoothDevice) : ByteLink {
    private var socket: BluetoothSocket? = null
    private var receiver: (ByteArray) -> Unit = {}
    var onDisconnected: () -> Unit = {}

    suspend fun connect() = withContext(Dispatchers.IO) {
        adapter?.cancelDiscovery()
        val s = try {
            device.createRfcommSocketToServiceRecord(SPP).also { it.connect() }
        } catch (e: IOException) {
            // Alguns adaptadores só aceitam o socket "inseguro".
            device.createInsecureRfcommSocketToServiceRecord(SPP).also { it.connect() }
        }
        socket = s
        thread(name = "obd-classic-reader", isDaemon = true) {
            val buf = ByteArray(1024)
            try {
                val input = s.inputStream
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) receiver(buf.copyOf(n))
                }
            } catch (_: IOException) {
            }
            onDisconnected()
        }
    }

    override suspend fun send(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val out = socket?.outputStream ?: error("Desconectado")
        out.write(bytes)
        out.flush()
    }

    override fun setReceiver(receiver: (ByteArray) -> Unit) {
        this.receiver = receiver
    }

    override suspend fun close() {
        withContext(Dispatchers.IO) { runCatching { socket?.close() } }
        socket = null
    }

    companion object {
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
