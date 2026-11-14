package co.rotalabs.obd.core

/** Definição de um PID padrão do modo 01. */
class PidDef(
    val pid: Int,
    val name: String,
    val unit: String,
    val decode: (IntArray) -> Double,
) {
    val code: String get() = "%02X".format(pid)
}

object ObdPids {
    private fun a(d: IntArray) = d[0].toDouble()
    private fun ab(d: IntArray) = (d[0] * 256 + d[1]).toDouble()

    val defs: Map<Int, PidDef> = listOf(
        PidDef(0x04, "Carga do motor", "%") { a(it) * 100 / 255 },
        PidDef(0x05, "Temp. do líquido de arrefecimento", "°C") { a(it) - 40 },
        PidDef(0x0B, "Pressão do coletor (MAP)", "kPa") { a(it) },
        PidDef(0x0C, "Rotação", "rpm") { ab(it) / 4 },
        PidDef(0x0D, "Velocidade", "km/h") { a(it) },
        PidDef(0x0F, "Temp. do ar de admissão", "°C") { a(it) - 40 },
        PidDef(0x10, "Fluxo de ar (MAF)", "g/s") { ab(it) / 100 },
        PidDef(0x11, "Posição da borboleta", "%") { a(it) * 100 / 255 },
        PidDef(0x1F, "Tempo com motor ligado", "s") { ab(it) },
        PidDef(0x21, "Distância com luz de falha acesa", "km") { ab(it) },
        PidDef(0x23, "Pressão do rail (diesel)", "kPa") { ab(it) * 10 },
        PidDef(0x2C, "EGR comandado", "%") { a(it) * 100 / 255 },
        PidDef(0x2D, "Erro do EGR", "%") { a(it) * 100 / 128 - 100 },
        PidDef(0x2F, "Nível de combustível", "%") { a(it) * 100 / 255 },
        PidDef(0x30, "Partidas desde a limpeza de códigos", "") { a(it) },
        PidDef(0x31, "Distância desde a limpeza de códigos", "km") { ab(it) },
        PidDef(0x33, "Pressão barométrica", "kPa") { a(it) },
        PidDef(0x42, "Tensão do módulo", "V") { ab(it) / 1000 },
        PidDef(0x43, "Carga absoluta", "%") { ab(it) * 100 / 255 },
        PidDef(0x46, "Temp. ambiente", "°C") { a(it) - 40 },
        PidDef(0x49, "Pedal do acelerador D", "%") { a(it) * 100 / 255 },
        PidDef(0x4A, "Pedal do acelerador E", "%") { a(it) * 100 / 255 },
        PidDef(0x4C, "Borboleta comandada", "%") { a(it) * 100 / 255 },
        PidDef(0x5C, "Temp. do óleo", "°C") { a(it) - 40 },
        PidDef(0x5E, "Consumo de combustível", "L/h") { ab(it) / 20 },
        PidDef(0x61, "Torque solicitado", "%") { a(it) - 125 },
        PidDef(0x62, "Torque real", "%") { a(it) - 125 },
        PidDef(0x63, "Torque de referência", "Nm") { ab(it) },
    ).associateBy { it.pid }

    /** PIDs que devolvem o mapa de suportados (00, 20, 40...). */
    val bitmapPids = listOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0)

    /**
     * Lê a resposta "41 <base> b0 b1 b2 b3" e devolve os PIDs marcados como suportados.
     */
    fun decodeBitmap(base: Int, data: IntArray): List<Int> {
        val out = mutableListOf<Int>()
        for (byteIdx in 0 until minOf(4, data.size)) {
            for (bit in 0 until 8) {
                if (data[byteIdx] and (0x80 shr bit) != 0) out.add(base + byteIdx * 8 + bit + 1)
            }
        }
        return out
    }

    /** Decodifica uma resposta positiva do modo 01. Devolve null se não houver fórmula. */
    fun decode(pid: Int, response: IntArray): Double? {
        val def = defs[pid] ?: return null
        if (response.size < 3 || response[0] != 0x41 || response[1] != pid) return null
        val data = response.copyOfRange(2, response.size)
        return runCatching { def.decode(data) }.getOrNull()
    }
}
