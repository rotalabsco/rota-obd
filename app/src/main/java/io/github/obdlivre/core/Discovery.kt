package io.github.obdlivre.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Descoberta: descobre, sem escrever nada no carro, o que cada módulo responde.
 * 1. módulos presentes; 2. PIDs padrão suportados e uma leitura de cada; 3. identificação (modo 09);
 * 4. códigos de falha; 5. varredura do serviço 21 (dados do fabricante, usado pela Mitsubishi).
 */
class Discovery(
    private val elm: Elm327,
    private val recorder: Recorder,
    private val progress: (step: String, fraction: Float) -> Unit,
) {
    class Lid(val module: Module, val lid: Int, val result: ObdResult)

    class ModuleReport(
        val module: Module,
        val supportedPids: List<Int>,
        val pidValues: Map<Int, Double?>,
        val info: Map<String, String>,
        val dtcs: List<Elm327.DtcRead>,
        val lids: List<Lid>,
    )

    suspend fun run(sweepService21: Boolean = true, modules: List<Module> = Modules.standard): List<ModuleReport> {
        progress("Procurando módulos", 0f)
        val present = mutableListOf<Module>()
        for (m in modules) {
            currentCoroutineContext().ensureActive()
            val r = elm.request(m, "0100", 800, kind = "probe")
            if (r is ObdResult.Ok || r is ObdResult.Negative) present.add(m)
        }
        // O câmbio pode não responder ao modo 01 mas responder ao serviço 21; mantém motor e câmbio sempre.
        for (m in listOf(Modules.ENGINE, Modules.TRANSMISSION)) if (m !in present && m in modules) present.add(m)
        recorder.record("modules", mapOf("present" to present.map { it.id }))

        val reports = mutableListOf<ModuleReport>()
        present.forEachIndexed { idx, m ->
            val base = idx.toFloat() / present.size
            val span = 1f / present.size
            currentCoroutineContext().ensureActive()

            progress("${m.name}: PIDs padrão", base)
            val pids = elm.supportedPids(m)
            val values = LinkedHashMap<Int, Double?>()
            for (p in pids) {
                currentCoroutineContext().ensureActive()
                values[p] = elm.readPid(m, p).second
            }

            progress("${m.name}: identificação", base + span * 0.15f)
            val info = if (pids.isNotEmpty()) elm.vehicleInfo(m) else emptyMap()

            progress("${m.name}: códigos de falha", base + span * 0.25f)
            val dtcs = elm.readDtcs(m)

            val lids = mutableListOf<Lid>()
            if (sweepService21) {
                var serviceUnsupported = 0
                for (lid in 0x00..0xFF) {
                    currentCoroutineContext().ensureActive()
                    if (lid % 16 == 0) progress("${m.name}: serviço 21, LID %02X".format(lid), base + span * (0.3f + 0.7f * lid / 256f))
                    val r = elm.request(m, "21%02X".format(lid), 1000, kind = "lid_sweep")
                    lids.add(Lid(m, lid, r))
                    // Se as primeiras 16 respostas forem "serviço não suportado" ou silêncio, o módulo não fala 21.
                    if ((r is ObdResult.Negative && r.nrc == 0x11) || r is ObdResult.Timeout || r is ObdResult.NoData) serviceUnsupported++
                    if (lid == 0x0F && serviceUnsupported == 16) {
                        recorder.record("lid_sweep_aborted", mapOf("mod" to m.id, "reason" to "sem resposta ou serviço 21 não suportado"))
                        break
                    }
                }
            }
            reports.add(ModuleReport(m, pids, values, info, dtcs, lids))
        }
        elm.broadcast()
        progress("Concluído", 1f)
        return reports
    }

    companion object {
        /** Relatório em texto para colar numa conversa, issue ou fórum. */
        fun textReport(adapter: AdapterInfo?, reports: List<ModuleReport>): String = buildString {
            appendLine("# OBD Livre — relatório de descoberta")
            adapter?.let {
                appendLine("Adaptador: ${it.version} | tensão ${it.voltage} | protocolo ${it.protocol}")
            }
            for (r in reports) {
                appendLine()
                appendLine("## ${r.module.name} (${r.module.id})")
                r.info.forEach { (k, v) -> appendLine("- $k: $v") }
                appendLine("- PIDs padrão suportados (${r.supportedPids.size}): " +
                    r.supportedPids.joinToString(" ") { "%02X".format(it) })
                for ((pid, value) in r.pidValues) {
                    val def = ObdPids.defs[pid]
                    val name = def?.name ?: "PID %02X".format(pid)
                    val v = value?.let { "%.2f %s".format(java.util.Locale.ROOT, it, def?.unit ?: "") } ?: "(sem fórmula)"
                    appendLine("  - %02X %s: %s".format(pid, name, v))
                }
                for (d in r.dtcs) {
                    val label = mapOf("03" to "armazenados", "07" to "pendentes", "0A" to "permanentes")[d.mode]
                    val text = when {
                        d.codes == null -> "sem resposta (${describe(d.result)})"
                        d.codes.isEmpty() -> "nenhum"
                        else -> d.codes.joinToString(", ")
                    }
                    appendLine("- Códigos $label: $text")
                }
                val ok = r.lids.filter { it.result is ObdResult.Ok }
                if (r.lids.isNotEmpty()) {
                    appendLine("- Serviço 21: ${ok.size} identificadores responderam de ${r.lids.size} testados")
                    for (l in ok) {
                        val res = l.result as ObdResult.Ok
                        appendLine("  - 21%02X (%d bytes): %s".format(l.lid, res.bytes.size, res.hex))
                    }
                    val negatives = r.lids.mapNotNull { it.result as? ObdResult.Negative }.groupingBy { it.nrc }.eachCount()
                    if (negatives.isNotEmpty()) {
                        appendLine("  - Negativas: " + negatives.entries.joinToString { "%02X %s ×%d".format(it.key, nrcMeaning(it.key), it.value) })
                    }
                }
            }
        }

        fun describe(r: ObdResult): String = when (r) {
            is ObdResult.Ok -> r.hex
            is ObdResult.Negative -> "negativa %02X: %s".format(r.nrc, r.meaning)
            is ObdResult.NoData -> "sem dados"
            is ObdResult.Timeout -> "tempo esgotado"
            is ObdResult.Error -> r.message
        }
    }
}
