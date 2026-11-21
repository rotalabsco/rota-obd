package co.rotalabs.obd.core

/**
 * Organização do .zip exportado, por categoria:
 *
 * - `coleta/NN-passo/`: as leituras de cada passo da Coleta guiada (recortadas das sessões pelos
 *   marcadores `coleta`) e o relatório, quando o passo é uma Descoberta;
 * - `descobertas/`: relatórios de Descobertas feitas fora da Coleta guiada;
 * - `sessoes-completas/`: as sessões inteiras, sem recorte;
 * - `garagem/`: garagem e vencimentos;
 * - `LEIA-ME.txt`: o que tem em cada pasta e quais passos foram gravados.
 */
object ExportLayout {
    const val MARKER_KIND = "coleta"
    const val EVENT_START = "inicio"
    const val EVENT_END = "fim"

    private const val MARKER_TAG = "\"kind\":\"$MARKER_KIND\""
    private val guidedReport = Regex("relatorio_\\d+_(.+)\\.md")

    /** Nome do relatório de uma Descoberta feita dentro de um passo da Coleta guiada. */
    fun guidedReportName(millis: Long, stepId: String) = "relatorio_${millis}_$stepId.md"

    fun stepFolder(stepId: String): String {
        val i = GuidedCollection.steps.indexOfFirst { it.id == stepId }
        return if (i < 0) "coleta/xx-$stepId" else "coleta/%02d-%s".format(i + 1, stepId)
    }

    /** Onde cada arquivo gravado entra no .zip. */
    fun entryFor(fileName: String): String = when {
        fileName == "garagem.json" || fileName == "vencimentos.json" -> "garagem/$fileName"
        fileName.endsWith(".ndjson") -> "sessoes-completas/$fileName"
        guidedReport.matches(fileName) -> stepFolder(guidedReport.find(fileName)!!.groupValues[1]) + "/" + fileName
        fileName.startsWith("relatorio_") -> "descobertas/$fileName"
        else -> "outros/$fileName"
    }

    data class Marker(val event: String, val stepId: String, val status: String?, val ts: Long?)

    fun marker(line: String): Marker? {
        if (!line.contains(MARKER_TAG)) return null
        val m = runCatching { MiniJson.parse(line) as? Map<*, *> }.getOrNull() ?: return null
        val step = m["step"] as? String ?: return null
        val event = m["event"] as? String ?: return null
        return Marker(event, step, m["status"] as? String, (m["ts"] as? Double)?.toLong())
    }

    /**
     * Recorta as linhas de uma sessão por passo: do marcador de início ao de fim, inclusive. Linhas
     * fora de um passo ficam só na sessão completa. Um início sem fim (app fechado no meio) vai até o
     * fim do arquivo ou até o próximo início.
     */
    fun splitByStep(lines: Sequence<String>): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        var current: String? = null
        for (line in lines) {
            val mk = marker(line)
            if (mk != null && mk.event == EVENT_START) current = mk.stepId
            current?.let { out.getOrPut(it) { mutableListOf() }.add(line) }
            if (mk != null && mk.event == EVENT_END && mk.stepId == current) current = null
        }
        return out
    }

    /** Texto do LEIA-ME, com os passos encontrados nos marcadores. */
    fun readme(markers: List<Marker>, appVersion: String? = null): String = buildString {
        appendLine("Rota.OBD: dados exportados")
        appVersion?.let { appendLine("Exportado pelo app $it. Cada sessão e cada passo gravam a versão que estava instalada.") }
        appendLine()
        appendLine("coleta/              uma pasta por passo da Coleta guiada, com as leituras do passo")
        appendLine("                     (recortadas das sessões) e o relatório, se o passo é uma Descoberta")
        appendLine("descobertas/         relatórios de Descobertas feitas fora da Coleta guiada")
        appendLine("sessoes-completas/   cada conexão com o adaptador, inteira, sem recorte")
        appendLine("garagem/             garagem e vencimentos")
        appendLine()
        val ends = markers.filter { it.event == EVENT_END }
        if (ends.isEmpty()) {
            appendLine("Nenhum passo da Coleta guiada foi gravado.")
            return@buildString
        }
        appendLine("Passos gravados:")
        for (mk in ends) {
            val title = GuidedCollection.step(mk.stepId)?.title ?: mk.stepId
            appendLine("- ${stepFolder(mk.stepId)}: $title (${mk.status ?: "?"})")
        }
    }
}
