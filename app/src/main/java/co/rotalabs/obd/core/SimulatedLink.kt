package co.rotalabs.obd.core

import kotlinx.coroutines.delay
import kotlin.math.sin

/**
 * Adaptador ELM327 simulado, para testar o app sem carro (modo demonstração) e nos testes.
 * Imita um diesel em CAN 11 bits: motor em 7E0 e câmbio em 7E1, ambos com serviço 21.
 */
class SimulatedLink(private val chunkSize: Int = 20, private val latencyMs: Long = 5) : ByteLink {
    private var receiver: (ByteArray) -> Unit = {}
    private var echo = true
    private var spaces = true
    private var header = 0x7DF
    private var tick = 0

    override fun setReceiver(receiver: (ByteArray) -> Unit) {
        this.receiver = receiver
    }

    override suspend fun close() {}

    override suspend fun send(bytes: ByteArray) {
        val cmd = String(bytes, Charsets.US_ASCII).trim().uppercase().replace(" ", "")
        val reply = respond(cmd)
        val text = (if (echo) "$cmd\r" else "") + reply + "\r\r>"
        delay(latencyMs)
        // Entrega em pedaços, como fazem as notificações BLE.
        text.toByteArray(Charsets.ISO_8859_1).toList().chunked(chunkSize).forEach {
            receiver(it.toByteArray())
        }
    }

    private fun fmt(hex: String): String = if (spaces) hex.chunked(2).joinToString(" ") else hex

    private fun respond(cmd: String): String {
        if (cmd.startsWith("AT")) return at(cmd.removePrefix("AT"))
        tick++
        val engine = header == 0x7E0 || header == 0x7DF
        val trans = header == 0x7E1
        if (!engine && !trans) return "NO DATA"
        val t = tick / 10.0
        val rpm = (800 + 400 * (1 + sin(t))).toInt()
        return when {
            engine && cmd == "0100" -> fmt("4100BE3EB811")
            engine && cmd == "0120" -> fmt("4120A005B011")
            engine && cmd == "0140" -> fmt("4140FED00400")
            trans && cmd == "0100" -> fmt("410018000001")
            trans && cmd == "0120" -> fmt("412000000001")
            trans && cmd.startsWith("01") -> "NO DATA"
            cmd == "0105" -> fmt("4105%02X".format(85 + 40))
            cmd == "010C" -> fmt("410C%04X".format(rpm * 4))
            cmd == "010D" -> fmt("410D%02X".format((rpm - 800) / 10))
            // Carga acompanha a rotação (marcha lenta ~13 %), para o consumo estimado ter o que mostrar.
            cmd == "0104" -> fmt("4104%02X".format(((13 + (rpm - 800) / 25) * 255 / 100).coerceIn(0, 255)))
            cmd == "010F" -> fmt("410F%02X".format(30 + 40))
            cmd == "010B" -> fmt("410B%02X".format(100 + (rpm - 800) / 8))
            cmd == "0123" -> fmt("4123%04X".format(3500 + rpm))
            cmd == "0133" -> fmt("413364")
            cmd == "0142" -> fmt("4142%04X".format(14120))
            cmd.startsWith("01") && cmd.length == 4 -> fmt("41${cmd.substring(2)}00")
            engine && cmd == "0902" -> multi("490201" + "4A4D594C595639385745" + "4A41303030303000")
            engine && cmd == "0904" -> multi("490401" + "31383630433032323032000000000000")
            cmd.startsWith("09") -> fmt("7F0912")
            cmd == "03" -> if (engine) fmt("43010401") else fmt("4300")
            cmd == "07" || cmd == "0A" -> fmt("${(cmd.toInt(16) + 0x40).toString(16).uppercase().padStart(2, '0')}00")
            cmd == "04" -> fmt("44")
            cmd.startsWith("21") && cmd.length == 4 -> service21(cmd.substring(2).toInt(16), engine, rpm)
            else -> fmt("7F${cmd.take(2)}11")
        }
    }

    private fun service21(lid: Int, engine: Boolean, rpm: Int): String {
        val supported = if (engine) setOf(0x01, 0x02, 0x03, 0x10, 0x11) else setOf(0x01, 0x30)
        if (lid !in supported) return fmt("7F2131")
        // Bytes 0..1: rotação; byte 2: volume injetado (mm³/ciclo ×4); demais fixos.
        val inj = ((rpm - 700) / 20 + 8).coerceIn(0, 255)
        val body = "%04X%02X".format(rpm, inj) + "00112233445566778899"
        return multi("61%02X".format(lid) + body)
    }

    /** Formata como ISO-TP multiquadro do ELM327 (linha de tamanho + "0:", "1:"...). */
    private fun multi(hex: String): String {
        val len = hex.length / 2
        if (len <= 7) return fmt(hex)
        val out = StringBuilder("%03X\r".format(len))
        var rest = hex
        var idx = 0
        val first = rest.take(12)
        out.append("$idx: ${fmt(first)}\r")
        rest = rest.drop(12)
        while (rest.isNotEmpty()) {
            idx = (idx + 1) % 16
            val part = rest.take(14).padEnd(14, '0').let { if (rest.length <= 14) it else it }
            out.append("%X: %s\r".format(idx, fmt(part)))
            rest = rest.drop(14)
        }
        return out.toString().trimEnd('\r')
    }

    private fun at(c: String): String = when {
        c == "Z" -> { echo = true; spaces = true; header = 0x7DF; "\r\rELM327 v2.3" }
        c == "E0" -> { echo = false; "OK" }
        c == "E1" -> { echo = true; "OK" }
        c == "S0" -> { spaces = false; "OK" }
        c == "S1" -> { spaces = true; "OK" }
        c == "I" -> "ELM327 v2.3 (simulado)"
        c == "RV" -> "12.6V"
        c == "DPN" -> "6"
        c == "AR" -> { header = 0x7DF; "OK" }
        c.startsWith("SH") -> { header = c.removePrefix("SH").toInt(16); "OK" }
        else -> "OK"
    }
}
