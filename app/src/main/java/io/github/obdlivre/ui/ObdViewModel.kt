package io.github.obdlivre.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.obdlivre.android.BleLink
import io.github.obdlivre.android.ClassicLink
import io.github.obdlivre.android.DeviceScanner
import io.github.obdlivre.android.FoundDevice
import io.github.obdlivre.core.AdapterInfo
import io.github.obdlivre.core.ByteLink
import io.github.obdlivre.core.Discovery
import io.github.obdlivre.core.Elm327
import io.github.obdlivre.core.ElmExchanger
import io.github.obdlivre.core.Module
import io.github.obdlivre.core.Modules
import io.github.obdlivre.core.NdjsonRecorder
import io.github.obdlivre.core.ObdPids
import io.github.obdlivre.core.ObdResult
import io.github.obdlivre.core.SimulatedLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

sealed class ConnState {
    object Idle : ConnState()
    data class Connecting(val name: String) : ConnState()
    data class Connected(val name: String, val detail: String) : ConnState()
    data class Failed(val message: String) : ConnState()
}

data class LiveValue(val pid: Int, val name: String, val unit: String, val value: Double?)

data class LidWatch(
    val module: Module,
    val lid: Int,
    val bytes: IntArray = IntArray(0),
    val changeCount: IntArray = IntArray(0),
    val min: IntArray = IntArray(0),
    val max: IntArray = IntArray(0),
    val samples: Int = 0,
    val last: String = "",
)

class ObdViewModel(app: Application) : AndroidViewModel(app) {
    val scanner = DeviceScanner(app)

    private val _conn = MutableStateFlow<ConnState>(ConnState.Idle)
    val conn: StateFlow<ConnState> = _conn
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress
    private val _report = MutableStateFlow<String?>(null)
    val report: StateFlow<String?> = _report
    private val _live = MutableStateFlow<List<LiveValue>>(emptyList())
    val live: StateFlow<List<LiveValue>> = _live
    private val _dtcs = MutableStateFlow<List<Elm327.DtcRead>?>(null)
    val dtcs: StateFlow<List<Elm327.DtcRead>?> = _dtcs
    private val _watch = MutableStateFlow<LidWatch?>(null)
    val watch: StateFlow<LidWatch?> = _watch
    private val _lidsFound = MutableStateFlow<Map<Module, List<Int>>>(emptyMap())
    val lidsFound: StateFlow<Map<Module, List<Int>>> = _lidsFound

    private var link: ByteLink? = null
    private var elm: Elm327? = null
    private var recorder: NdjsonRecorder? = null
    private var adapterInfo: AdapterInfo? = null
    private var engineSupported: List<Int>? = null
    private var job: Job? = null

    val sessionsDir: File = File(app.getExternalFilesDir(null) ?: app.filesDir, "sessoes").apply { mkdirs() }

    private fun log(line: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date())
        _log.value = (_log.value + "$time $line").takeLast(400)
    }

    fun connect(found: FoundDevice?) {
        scanner.stop()
        job?.cancel()
        viewModelScope.launch {
            disconnectInternal()
            val name = found?.name ?: "Simulador"
            _conn.value = ConnState.Connecting(name)
            try {
                val (l, detail) = when {
                    found == null -> SimulatedLink() to "modo demonstração"
                    found.ble -> BleLink(getApplication(), found.device).also {
                        it.onDisconnected = { onLinkLost() }
                        it.connect()
                    }.let { it to it.describe }
                    else -> ClassicLink(scanner.adapter, found.device).also {
                        it.onDisconnected = { onLinkLost() }
                        it.connect()
                    } to "Bluetooth clássico (SPP)"
                }
                link = l
                val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date())
                val rec = NdjsonRecorder(File(sessionsDir, "sessao_$stamp.ndjson"))
                recorder = rec
                rec.record("session", mapOf("device" to name, "link" to detail, "app" to "obd-livre"))
                val e = Elm327(ElmExchanger(l), rec, ::log)
                elm = e
                val info = e.initialize()
                adapterInfo = info
                log("Adaptador: ${info.version} | ${info.voltage} | protocolo ${info.protocol}")
                _conn.value = ConnState.Connected(name, "$detail · ${info.version} · protocolo ${info.protocol}")
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                log("Erro: ${ex.message}")
                _conn.value = ConnState.Failed(ex.message ?: ex.toString())
                disconnectInternal()
            }
        }
    }

    private fun onLinkLost() {
        if (_conn.value is ConnState.Connected) {
            log("Conexão com o adaptador perdida")
            _conn.value = ConnState.Failed("Conexão perdida")
            job?.cancel()
        }
    }

    fun disconnect() {
        job?.cancel()
        viewModelScope.launch {
            disconnectInternal()
            _conn.value = ConnState.Idle
        }
    }

    private suspend fun disconnectInternal() {
        runCatching { link?.close() }
        runCatching { recorder?.close() }
        link = null
        elm = null
        recorder = null
        _busy.value = null
    }

    /** Roda uma operação por vez; a nova cancela a anterior. */
    private fun exclusive(label: String, block: suspend (Elm327) -> Unit) {
        val e = elm ?: run { log("Conecte ao adaptador primeiro"); return }
        val previous = job
        job = viewModelScope.launch {
            previous?.cancel()
            previous?.join()
            _busy.value = label
            try {
                block(e)
            } catch (ex: CancellationException) {
                log("$label interrompido")
            } catch (ex: Exception) {
                log("$label falhou: ${ex.message}")
            } finally {
                if (_busy.value == label) _busy.value = null
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    fun runDiscovery(sweep21: Boolean) = exclusive("Descoberta") { e ->
        _report.value = null
        val reports = Discovery(e, recorder!!) { step, fraction ->
            _progress.value = fraction
            log(step)
        }.run(sweepService21 = sweep21)
        engineSupported = reports.firstOrNull { it.module == Modules.ENGINE }?.supportedPids
        _lidsFound.value = reports.associate { r ->
            r.module to r.lids.filter { it.result is ObdResult.Ok }.map { it.lid }
        }.filterValues { it.isNotEmpty() }
        val text = Discovery.textReport(adapterInfo, reports)
        File(sessionsDir, "relatorio_${System.currentTimeMillis()}.md").writeText(text)
        _report.value = text
    }

    fun startLive() = exclusive("Ao vivo") { e ->
        val supported = engineSupported ?: e.supportedPids(Modules.ENGINE).also { engineSupported = it }
        val pids = supported.filter { it in ObdPids.defs }
        if (pids.isEmpty()) {
            log("O motor não informou PIDs padrão com fórmula conhecida")
            return@exclusive
        }
        val values = LinkedHashMap<Int, LiveValue>()
        pids.forEach { p -> ObdPids.defs[p]!!.let { values[p] = LiveValue(p, it.name, it.unit, null) } }
        _live.value = values.values.toList()
        // Rápidos a cada volta; lentos (temperaturas, nível) a cada 10 voltas.
        val slow = setOf(0x05, 0x0F, 0x1F, 0x21, 0x2F, 0x30, 0x31, 0x33, 0x46, 0x5C, 0x63)
        var round = 0
        while (kotlin.coroutines.coroutineContext.isActive) {
            for (p in pids) {
                if (p in slow && round % 10 != 0) continue
                val (_, v) = e.readPid(Modules.ENGINE, p)
                values[p] = values[p]!!.copy(value = v)
                _live.value = values.values.toList()
            }
            round++
        }
    }

    fun readDtcs() = exclusive("Leitura de falhas") { e ->
        _dtcs.value = e.readDtcs(Modules.ENGINE) + e.readDtcs(Modules.TRANSMISSION)
    }

    fun clearDtcs(module: Module) = exclusive("Limpeza de falhas") { e ->
        val r = e.clearDtcs(module)
        log("Limpeza em ${module.name}: ${Discovery.describe(r)}")
        delay(500)
        _dtcs.value = e.readDtcs(Modules.ENGINE) + e.readDtcs(Modules.TRANSMISSION)
    }

    /** Lê um identificador do serviço 21 repetidamente e marca quais bytes mudam. */
    fun watchLid(module: Module, lid: Int) = exclusive("Explorar 21%02X".format(lid)) { e ->
        var w = LidWatch(module, lid)
        _watch.value = w
        while (kotlin.coroutines.coroutineContext.isActive) {
            val r = e.request(module, "21%02X".format(lid), 1000, kind = "lid_watch")
            if (r is ObdResult.Ok) {
                val b = r.bytes
                if (w.bytes.size != b.size) {
                    w = w.copy(
                        bytes = b, changeCount = IntArray(b.size), min = b.copyOf(), max = b.copyOf(),
                    )
                }
                val changes = w.changeCount.copyOf()
                val min = w.min.copyOf()
                val max = w.max.copyOf()
                for (i in b.indices) {
                    if (b[i] != w.bytes[i]) changes[i]++
                    if (b[i] < min[i]) min[i] = b[i]
                    if (b[i] > max[i]) max[i] = b[i]
                }
                w = w.copy(bytes = b, changeCount = changes, min = min, max = max, samples = w.samples + 1, last = "ok")
            } else {
                w = w.copy(last = Discovery.describe(r))
            }
            _watch.value = w
            delay(100)
        }
    }

    /** Junta as sessões gravadas num .zip para compartilhar. */
    fun exportZip(): File {
        val out = File(getApplication<Application>().cacheDir, "obd-livre-sessoes.zip")
        ZipOutputStream(out.outputStream()).use { zip ->
            sessionsDir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                zip.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return out
    }

    override fun onCleared() {
        scanner.stop()
        job?.cancel()
        runCatching { recorder?.close() }
    }
}
