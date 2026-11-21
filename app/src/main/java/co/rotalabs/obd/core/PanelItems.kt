package co.rotalabs.obd.core

/**
 * O que o painel flutuante (por cima do Maps) pode mostrar e como cada linha aparece.
 *
 * Os valores vêm da leitura que estiver rodando: Ao vivo ou um passo da Coleta guiada. O painel não faz
 * leitura própria, para não disputar o adaptador.
 */
object PanelItems {
    /** Tensão lida pelo ATRV (não é PID). */
    const val VOLTAGE = -1
    const val MAX = 4

    /** Valor mais velho que isso aparece como "—": a leitura daquele dado parou. */
    const val STALE_MS = 15_000L

    data class Option(val id: Int, val label: String, val unit: String)

    val options = listOf(
        Option(0x05, "Motor", "°C"),
        Option(0x0C, "Rotação", "rpm"),
        Option(0x0D, "Velocidade", "km/h"),
        Option(0x04, "Carga", "%"),
        Option(VOLTAGE, "Bateria", "V"),
    )

    val default = listOf(0x05, 0x0C, VOLTAGE)

    data class Reading(val value: Double, val atMillis: Long)

    fun encode(ids: List<Int>): String = ids.joinToString(",")

    fun decode(raw: String?): List<Int>? {
        if (raw.isNullOrBlank()) return null
        val known = options.map { it.id }.toSet()
        return raw.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in known }.distinct().take(MAX)
    }

    /** Marca ou desmarca, respeitando o máximo e mantendo a ordem da lista de opções. */
    fun toggle(selected: List<Int>, id: Int): List<Int> {
        val next = if (id in selected) selected - id else if (selected.size >= MAX) selected else selected + id
        return options.map { it.id }.filter { it in next }
    }

    /** Linhas do painel: rótulo e valor já formatado com unidade, ou "—" se não há leitura recente. */
    fun lines(selected: List<Int>, readings: Map<Int, Reading>, voltage: Double?, nowMillis: Long): List<Pair<String, String>> =
        selected.mapNotNull { id ->
            val o = options.firstOrNull { it.id == id } ?: return@mapNotNull null
            val v = if (id == VOLTAGE) voltage else readings[id]?.takeIf { nowMillis - it.atMillis <= STALE_MS }?.value
            o.label to (v?.let { Formato.leitura(it, o.unit) + " " + o.unit } ?: "—")
        }
}
