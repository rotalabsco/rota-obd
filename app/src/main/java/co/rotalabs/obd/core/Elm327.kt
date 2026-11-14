package co.rotalabs.obd.core

import kotlinx.coroutines.delay

/** Um módulo eletrônico do carro, endereçado em CAN 11 bits. */
data class Module(val name: String, val tx: Int, val rx: Int) {
    val id: String get() = "%03X".format(tx)
}

object Modules {
    val ENGINE = Module("Motor", 0x7E0, 0x7E8)
    val TRANSMISSION = Module("Câmbio", 0x7E1, 0x7E9)

    /** Endereços físicos padrão OBD em CAN 11 bits. */
    val standard: List<Module> = listOf(ENGINE, TRANSMISSION) + (2..7).map {
        Module("Módulo 7E$it", 0x7E0 + it, 0x7E8 + it)
    }
}

data class AdapterInfo(val version: String, val voltage: String, val protocol: String)

/**
 * Cliente ELM327. Só envia comandos de leitura, com uma exceção explícita: [clearDtcs].
 */
class Elm327(
    private val exchanger: ElmExchanger,
    private val recorder: Recorder = NoopRecorder,
    private val log: (String) -> Unit = {},
) {
    private var currentModule: Module? = null

    suspend fun raw(command: String, timeoutMs: Long = 1500): String? {
        val r = exchanger.exchange(command, timeoutMs)
        log("> $command  < ${r?.let { ElmParser.lines(it, command).joinToString(" | ") } ?: "(sem resposta)"}")
        return r
    }

    /**
     * Prepara o adaptador. [protocol] "6" = CAN 11 bits / 500 kbps (Mitsubishi e a maioria dos carros
     * de 2008+). Se falhar, tenta detecção automática.
     */
    suspend fun initialize(protocol: String = "6"): AdapterInfo {
        raw("ATZ", 4000)
        delay(500)
        raw("ATE0") // sem eco
        raw("ATL0") // sem quebra de linha extra
        raw("ATS0") // sem espaços
        raw("ATH0") // sem cabeçalhos
        raw("ATCAF1") // formatação CAN (remove PCI)
        raw("ATAT1") // tempo de espera adaptativo
        val version = ElmParser.lines(raw("ATI") ?: "").joinToString(" ")
        val voltage = ElmParser.lines(raw("ATRV") ?: "").joinToString(" ")

        raw("ATSP$protocol")
        var probe = ElmParser.parse(raw("0100", 6000), "0100")
        if (probe !is ObdResult.Ok) {
            log("Protocolo $protocol não respondeu, tentando detecção automática")
            raw("ATSP0")
            probe = ElmParser.parse(raw("0100", 10000), "0100")
        }
        val dpn = ElmParser.lines(raw("ATDPN") ?: "").joinToString(" ")
        val info = AdapterInfo(version, voltage, dpn)
        recorder.record(
            "adapter",
            mapOf("version" to version, "voltage" to voltage, "protocol" to dpn, "probe_ok" to (probe is ObdResult.Ok)),
        )
        currentModule = null
        return info
    }

    /** Endereça os próximos pedidos a um módulo e filtra só a resposta dele. */
    suspend fun select(module: Module) {
        if (currentModule == module) return
        raw("ATSH%03X".format(module.tx))
        raw("ATCRA%03X".format(module.rx))
        currentModule = module
    }

    /** Volta ao modo broadcast (7DF) e recepção automática. */
    suspend fun broadcast() {
        raw("ATSH7DF")
        raw("ATAR")
        currentModule = null
    }

    suspend fun request(module: Module, hex: String, timeoutMs: Long = 1500, kind: String = "raw"): ObdResult {
        select(module)
        val result = ElmParser.parse(raw(hex, timeoutMs), hex)
        recorder.record(kind, resultFields(module, hex, result))
        return result
    }

    suspend fun supportedPids(module: Module): List<Int> {
        val all = mutableListOf<Int>()
        for (base in ObdPids.bitmapPids) {
            if (base != 0 && (base !in all)) break
            val hex = "01%02X".format(base)
            val r = request(module, hex, kind = "supported")
            if (r !is ObdResult.Ok || r.bytes.size < 6 || r.bytes[0] != 0x41 || r.bytes[1] != base) break
            all.addAll(ObdPids.decodeBitmap(base, r.bytes.copyOfRange(2, 6)))
        }
        return all.filterNot { it in ObdPids.bitmapPids }
    }

    suspend fun readPid(module: Module, pid: Int): Pair<ObdResult, Double?> {
        val hex = "01%02X".format(pid)
        select(module)
        val result = ElmParser.parse(raw(hex), hex)
        val value = (result as? ObdResult.Ok)?.let { ObdPids.decode(pid, it.bytes) }
        val def = ObdPids.defs[pid]
        recorder.record(
            "pid",
            resultFields(module, hex, result) + mapOf("name" to def?.name, "unit" to def?.unit, "value" to value),
        )
        return result to value
    }

    data class DtcRead(val module: Module, val mode: String, val codes: List<String>?, val result: ObdResult)

    /** Lê códigos armazenados (03), pendentes (07) e permanentes (0A). codes == null: módulo não respondeu. */
    suspend fun readDtcs(module: Module): List<DtcRead> = listOf("03", "07", "0A").map { mode ->
        val r = request(module, mode, 3000, kind = "dtc")
        val codes = when (r) {
            is ObdResult.Ok -> Dtc.decode(r.bytes)
            is ObdResult.NoData -> emptyList()
            else -> null
        }
        recorder.record("dtc_decoded", mapOf("mod" to module.id, "mode" to mode, "codes" to codes))
        DtcRead(module, mode, codes, r)
    }

    /**
     * Apaga códigos de falha (modo 04). Também zera os monitores de prontidão.
     * Use com o motor desligado e a ignição ligada. Única escrita deste app.
     */
    suspend fun clearDtcs(module: Module): ObdResult = request(module, "04", 5000, kind = "clear_dtc")

    suspend fun vehicleInfo(module: Module): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((pid, label) in listOf("02" to "VIN", "04" to "Calibração", "06" to "CVN", "0A" to "Nome da ECU")) {
            val r = request(module, "09$pid", 3000, kind = "vehicle_info")
            if (r is ObdResult.Ok && r.bytes.size > 3) {
                // 49 <pid> <nº de itens> <dados>
                val data = r.bytes.copyOfRange(3, r.bytes.size)
                out[label] = if (pid == "06") data.toHex() else ascii(data)
            }
        }
        return out
    }

    private fun ascii(data: IntArray) =
        data.filter { it in 0x20..0x7E }.map { it.toChar() }.joinToString("").trim()

    companion object {
        fun resultFields(module: Module, req: String, r: ObdResult): Map<String, Any?> = when (r) {
            is ObdResult.Ok -> mapOf("mod" to module.id, "req" to req, "status" to "ok", "resp" to r.hex)
            is ObdResult.Negative -> mapOf(
                "mod" to module.id, "req" to req, "status" to "negative",
                "nrc" to "%02X".format(r.nrc), "nrc_meaning" to r.meaning,
            )
            is ObdResult.NoData -> mapOf("mod" to module.id, "req" to req, "status" to "no_data")
            is ObdResult.Timeout -> mapOf("mod" to module.id, "req" to req, "status" to "timeout")
            is ObdResult.Error -> mapOf("mod" to module.id, "req" to req, "status" to "error", "error" to r.message)
        }
    }
}
