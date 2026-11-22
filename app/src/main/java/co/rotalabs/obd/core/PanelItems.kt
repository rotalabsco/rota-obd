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

    /** Valor mais velho que isso aparece como "—". O Ao vivo lê temperaturas a cada 10 voltas. */
    const val STALE_MS = 60_000L

    data class Option(val id: Int, val label: String, val unit: String)

    /** Rótulos curtos, porque o painel é pequeno; o resto usa o nome do Ao vivo. */
    private val shortLabels = mapOf(
        0x05 to "Motor", 0x0C to "Rotação", 0x0D to "Velocidade", 0x04 to "Carga",
        LiveSelection.BOOST to "Turbo", LiveSelection.CONSUMPTION to "Consumo", LiveSelection.TRIP_AVG to "Média", 0x23 to "Rail", 0x0F to "Ar admissão", 0x0B to "Coletor",
        0x33 to "Barométrica", 0x42 to "Módulo", 0x49 to "Pedal", 0x2C to "EGR", 0x2D to "Erro EGR",
        0x10 to "Fluxo de ar", 0x11 to "Borboleta", 0x4C to "Borboleta cmd.", 0x5C to "Óleo",
        0x5E to "Consumo", 0x2F to "Tanque", 0x1F to "Tempo ligado",
    )

    fun label(id: Int): String = if (id == VOLTAGE) "Bateria" else shortLabels[id] ?: LiveSelection.name(id)
    fun unit(id: Int): String = if (id == VOLTAGE) "V" else LiveSelection.unit(id)

    /** Antes da primeira leitura do Ao vivo (que descobre o que o carro tem), oferece o básico. */
    private val fallback = listOf(0x0D, 0x0C, 0x05, 0x04)

    /** Tudo o que o Ao vivo mostra neste carro, mais a bateria. */
    fun options(liveAvailable: List<Int>): List<Option> =
        (liveAvailable.ifEmpty { fallback } + VOLTAGE).distinct().map { Option(it, label(it), unit(it)) }

    val default = listOf(0x05, 0x0C, VOLTAGE)

    data class Reading(val value: Double, val atMillis: Long)

    fun encode(ids: List<Int>): String = ids.joinToString(",")

    fun decode(raw: String?): List<Int>? {
        if (raw.isNullOrBlank()) return null
        return raw.split(',').mapNotNull { it.trim().toIntOrNull() }.distinct().take(MAX)
    }

    /** Marca ou desmarca, respeitando o máximo e mantendo a ordem de [order] (a das opções). */
    fun toggle(selected: List<Int>, id: Int, order: List<Int>): List<Int> {
        val next = if (id in selected) selected - id else if (selected.size >= MAX) selected else selected + id
        return next.sortedBy { order.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }
    }

    /** PIDs que o painel precisa (o turbo pede MAP e barométrica; a bateria não é PID). */
    fun pidsToRead(selected: List<Int>): List<Int> = LiveSelection.pidsToRead(selected.filter { it != VOLTAGE })

    /** Linhas do painel: rótulo e valor já formatado com unidade, ou "—" se não há leitura recente. */
    fun lines(
        selected: List<Int>,
        readings: Map<Int, Reading>,
        voltage: Double?,
        nowMillis: Long,
        tripAverage: Double? = null,
    ): List<Pair<String, String>> {
        fun fresh(pid: Int) = readings[pid]?.takeIf { nowMillis - it.atMillis <= STALE_MS }?.value
        return selected.map { id ->
            val (v, u) = when (id) {
                VOLTAGE -> voltage to unit(id)
                LiveSelection.BOOST -> LiveSelection.boostBar(fresh(0x0B), fresh(0x33)) to unit(id)
                LiveSelection.CONSUMPTION -> FuelEstimate.instant(fresh(0x04), fresh(0x0C), fresh(0x0D)) ?: (null to unit(id))
                LiveSelection.TRIP_AVG -> tripAverage to unit(id)
                else -> LiveSelection.shown(id, fresh(id)) to unit(id)
            }
            label(id) to (v?.let { Formato.leitura(it, u) + " " + u } ?: "—")
        }
    }
}
