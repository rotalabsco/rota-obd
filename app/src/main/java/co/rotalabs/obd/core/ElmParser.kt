package co.rotalabs.obd.core

/** Resultado de um pedido OBD/UDS já interpretado. */
sealed class ObdResult {
    /** Resposta positiva. [bytes] começa no byte de serviço (ex.: 41 0C ...). */
    class Ok(val bytes: IntArray) : ObdResult() {
        val hex: String get() = bytes.toHex()
    }

    /** Resposta negativa 7F <serviço> <código>. */
    data class Negative(val service: Int, val nrc: Int) : ObdResult() {
        val meaning: String get() = nrcMeaning(nrc)
    }

    object NoData : ObdResult()

    data class Error(val message: String) : ObdResult()

    object Timeout : ObdResult()
}

private val HEX = "0123456789ABCDEF".toCharArray()

/** Hex em maiúsculas sem separador. Sem String.format: roda a cada leitura e pesava na CPU. */
fun IntArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val b = this[i] and 0xFF
        out[i * 2] = HEX[b ushr 4]
        out[i * 2 + 1] = HEX[b and 0x0F]
    }
    return String(out)
}

fun nrcMeaning(nrc: Int): String = when (nrc) {
    0x10 -> "recusado (geral)"
    0x11 -> "serviço não suportado"
    0x12 -> "subfunção não suportada"
    0x13 -> "tamanho ou formato inválido"
    0x22 -> "condições não atendidas"
    0x31 -> "identificador fora da faixa"
    0x33 -> "acesso de segurança negado"
    0x78 -> "resposta pendente"
    0x7E, 0x7F -> "não suportado na sessão atual"
    else -> "código 0x%02X".format(nrc)
}

object ElmParser {
    private val errorTokens = listOf(
        "CAN ERROR", "BUS ERROR", "BUS BUSY", "STOPPED", "UNABLE TO CONNECT",
        "BUFFER FULL", "FB ERROR", "DATA ERROR", "RX ERROR", "ACT ALERT", "LV RESET", "ERR"
    )
    private val hexLine = Regex("^[0-9A-F]+$")
    private val lengthLine = Regex("^[0-9A-F]{3}$")
    private val frameLine = Regex("^([0-9A-F]):([0-9A-F]*)$")

    /** Separa o texto em linhas úteis, sem eco, sem espaços e sem "SEARCHING...". */
    fun lines(raw: String, sentCommand: String? = null): List<String> {
        val echo = sentCommand?.replace(" ", "")?.uppercase()
        return raw.replace("\r", "\n").split("\n")
            .map { it.trim().replace(" ", "").uppercase() }
            .filter { it.isNotEmpty() }
            .filterNot { it.startsWith("SEARCHING") }
            .filterNot { echo != null && it == echo }
    }

    /**
     * Interpreta a resposta a um pedido com cabeçalhos desligados (ATH0) e formatação CAN ligada (ATCAF1).
     * Aceita quadro único ("410C1AF8") e multiquadro ISO-TP ("014", "0:490201...", "1:...").
     * Se vierem vários quadros únicos (mais de um módulo respondeu), devolve o primeiro que não seja
     * "resposta pendente" (7F xx 78). Para separar módulos, endereçe com ATSH/ATCRA.
     */
    fun parse(raw: String?, sentCommand: String? = null): ObdResult {
        if (raw == null) return ObdResult.Timeout
        val ls = lines(raw, sentCommand)
        if (ls.isEmpty()) return ObdResult.NoData
        if (ls.any { it.startsWith("NODATA") }) return ObdResult.NoData
        if (ls.any { it == "?" }) return ObdResult.Error("comando não reconhecido pelo adaptador")
        errorTokens.firstOrNull { tok -> ls.any { it.contains(tok.replace(" ", "")) } }?.let {
            return ObdResult.Error(it)
        }

        val frames = mutableListOf<IntArray>()
        var i = 0
        while (i < ls.size) {
            val line = ls[i]
            if (lengthLine.matches(line) && i + 1 < ls.size && frameLine.matches(ls[i + 1])) {
                val length = line.toInt(16)
                val sb = StringBuilder()
                i++
                while (i < ls.size) {
                    val m = frameLine.matchEntire(ls[i]) ?: break
                    sb.append(m.groupValues[2])
                    i++
                }
                val hex = sb.toString().take(length * 2)
                frames.add(hexToInts(hex))
                continue
            }
            if (hexLine.matches(line) && line.length % 2 == 0) frames.add(hexToInts(line))
            i++
        }
        if (frames.isEmpty()) return ObdResult.Error("resposta ilegível: ${ls.joinToString(" | ")}")

        val useful = frames.filterNot { it.size >= 3 && it[0] == 0x7F && it[2] == 0x78 }
        val frame = useful.firstOrNull() ?: frames.first()
        if (frame.size >= 3 && frame[0] == 0x7F) return ObdResult.Negative(frame[1], frame[2])
        return ObdResult.Ok(frame)
    }

    fun hexToInts(hex: String): IntArray =
        IntArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16) }
}
