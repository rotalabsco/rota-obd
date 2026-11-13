package io.github.obdlivre.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.obdlivre.android.BleLink
import io.github.obdlivre.android.ClassicLink
import io.github.obdlivre.android.DeviceScanner
import io.github.obdlivre.android.FoundDevice
import io.github.obdlivre.core.AdapterInfo
import io.github.obdlivre.core.ByteLink
import io.github.obdlivre.core.Deadline
import io.github.obdlivre.core.DeadlineStore
import io.github.obdlivre.core.Deadlines
import io.github.obdlivre.core.Discovery
import io.github.obdlivre.core.Elm327
import io.github.obdlivre.core.ElmExchanger
import io.github.obdlivre.core.FuelEntry
import io.github.obdlivre.core.Garage
import io.github.obdlivre.core.GarageData
import io.github.obdlivre.core.GarageStore
import io.github.obdlivre.core.MaintenanceItem
import io.github.obdlivre.core.MaintenanceRecord
import io.github.obdlivre.core.Module
import io.github.obdlivre.core.Modules
import io.github.obdlivre.core.NdjsonRecorder
import io.github.obdlivre.core.ObdPids
import io.github.obdlivre.core.ObdResult
import io.github.obdlivre.core.SimulatedLink
import io.github.obdlivre.core.SleepPolicy
import io.github.obdlivre.core.Vehicle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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

/** O tanque subiu desde a última leitura: provável abastecimento. */
data class RefuelPrompt(val fromPct: Double, val toPct: Double, val suggestedLiters: Double?)

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
    private var jobLabel: String? = null

    // Modo de espera do adaptador
    private var sleepPolicy: SleepPolicy? = null
    private var watchdog: Job? = null
    private var reconnectJob: Job? = null
    private var quiet = false
    private var resumeLive = false
    private var currentDevice: FoundDevice? = null
    private var currentName = ""
    private var currentDetail = ""
    private val prefs = app.getSharedPreferences("obd-livre", Context.MODE_PRIVATE)

    val sessionsDir: File = File(app.getExternalFilesDir(null) ?: app.filesDir, "sessoes").apply { mkdirs() }
    private val dataDir: File = app.getExternalFilesDir(null) ?: app.filesDir
    private val garageFile = File(dataDir, "garagem.json")
    private val garageStore = GarageStore(garageFile)
    private val _garage = MutableStateFlow(garageStore.load())
    val garage: StateFlow<GarageData> = _garage
    private val _refuel = MutableStateFlow<RefuelPrompt?>(null)
    val refuel: StateFlow<RefuelPrompt?> = _refuel
    private val deadlinesFile = File(dataDir, "vencimentos.json")
    private val deadlineStore = DeadlineStore(deadlinesFile)
    private val _deadlines = MutableStateFlow(deadlineStore.load())
    val deadlines: StateFlow<List<Deadline>> = _deadlines

    init {
        autoConnectOnStart()
    }

    private fun log(line: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date())
        _log.value = (_log.value + "$time $line").takeLast(400)
    }

    // ---------- Conexão ----------

    /** Conexão pedida pelo usuário (ou modo demonstração, com [found] nulo). */
    fun connect(found: FoundDevice?) {
        scanner.stop()
        stopReconnect()
        job?.cancel()
        if (found != null) {
            prefs.edit().putString(KEY_ADDRESS, found.address).putBoolean(KEY_BLE, found.ble)
                .putString(KEY_NAME, found.name).apply()
        }
        viewModelScope.launch { openConnection(found, silent = false) }
    }

    /** Abre a conexão e prepara o adaptador. Devolve true se ficou conectado. */
    private suspend fun openConnection(found: FoundDevice?, silent: Boolean): Boolean {
        disconnectInternal()
        val name = found?.name ?: "Simulador"
        if (!silent) _conn.value = ConnState.Connecting(name)
        return try {
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
            currentDevice = found
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date())
            val rec = NdjsonRecorder(File(sessionsDir, "sessao_$stamp.ndjson"))
            recorder = rec
            rec.record("session", mapOf("device" to name, "link" to detail, "app" to "obd-livre"))
            val e = Elm327(ElmExchanger(l), rec, ::log)
            elm = e
            val info = e.initialize()
            adapterInfo = info
            log("Adaptador: ${info.version} | ${info.voltage} | protocolo ${info.protocol}")
            currentName = name
            currentDetail = "$detail · ${info.version} · protocolo ${info.protocol}"
            _conn.value = ConnState.Connected(name, currentDetail)
            if (found != null) startWatchdog(e)
            // Roda como operação exclusiva para não disputar o adaptador com a próxima ação do usuário.
            // Se a leitura ao vivo foi pausada pelo modo de espera, retoma depois da identificação.
            exclusive("Identificação do carro", userAction = false) {
                identifyAndCheckFuel(it)
                if (resumeLive) {
                    resumeLive = false
                    viewModelScope.launch { startLive() }
                }
            }
            true
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            if (!silent) {
                log("Erro: ${ex.message}")
                _conn.value = ConnState.Failed(ex.message ?: ex.toString())
            }
            disconnectInternal()
            false
        }
    }

    private fun onLinkLost() {
        if (_conn.value is ConnState.Connected) {
            log("Conexão com o adaptador perdida (carro desligado ou adaptador em espera)")
            job?.cancel()
            waitForCar()
        }
    }

    /** Solta o adaptador e tenta reconectar de tempos em tempos, até o carro ligar de novo. */
    private fun waitForCar() {
        val device = currentDevice ?: return
        viewModelScope.launch {
            disconnectInternal()
            _conn.value = ConnState.Connecting("${device.name} · aguardando o carro ligar")
            startReconnect(device)
        }
    }

    private fun startReconnect(device: FoundDevice) {
        if (reconnectJob?.isActive == true) return
        reconnectJob = viewModelScope.launch {
            var wait = 20_000L
            while (isActive) {
                delay(wait)
                if (openConnection(device, silent = true)) {
                    log("Reconectado a ${device.name}")
                    return@launch
                }
                wait = minOf(wait + 10_000L, 60_000L)
            }
        }
    }

    private fun stopReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    /** Ao abrir o app, conecta sozinho ao último adaptador usado (se a permissão já foi dada). */
    private fun autoConnectOnStart() {
        val address = prefs.getString(KEY_ADDRESS, null) ?: return
        val adapter = scanner.adapter ?: return
        val permission = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
        if (ContextCompat.checkSelfPermission(getApplication(), permission) != PackageManager.PERMISSION_GRANTED) return
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return
        val found = FoundDevice(
            device, prefs.getString(KEY_NAME, null) ?: address, address,
            ble = prefs.getBoolean(KEY_BLE, true), rssi = null,
        )
        currentDevice = found
        viewModelScope.launch {
            _conn.value = ConnState.Connecting("${found.name} · aguardando o carro ligar")
            if (!openConnection(found, silent = true)) startReconnect(found)
        }
    }

    /**
     * Vigia o motor pela tensão lida no próprio adaptador (ATRV, sem tráfego no carro).
     * Motor desligado: pausa leituras contínuas e, depois, solta o adaptador para ele dormir.
     */
    private fun startWatchdog(e: Elm327) {
        watchdog?.cancel()
        quiet = false
        val policy = SleepPolicy(System.currentTimeMillis())
        sleepPolicy = policy
        watchdog = viewModelScope.launch {
            while (isActive) {
                delay(if (quiet) 60_000L else 30_000L)
                val volts = SleepPolicy.parseVoltage(e.raw("ATRV", 1500))
                when (policy.observe(System.currentTimeMillis(), volts)) {
                    SleepPolicy.Mode.ACTIVE -> if (quiet) {
                        quiet = false
                        log("Motor ligado (%.1f V): leituras retomadas".format(Locale.ROOT, volts ?: 0.0))
                        _conn.value = ConnState.Connected(currentName, currentDetail)
                        if (resumeLive) {
                            resumeLive = false
                            startLive()
                        }
                    }
                    SleepPolicy.Mode.QUIET -> if (!quiet) {
                        quiet = true
                        log("Motor desligado (%.1f V): leituras pausadas para não manter o carro acordado".format(Locale.ROOT, volts ?: 0.0))
                        _conn.value = ConnState.Connected(currentName, "$currentDetail · motor desligado, leituras pausadas")
                        if (jobLabel == LABEL_LIVE || jobLabel?.startsWith(LABEL_LID) == true) {
                            resumeLive = jobLabel == LABEL_LIVE
                            job?.cancel()
                        }
                    }
                    SleepPolicy.Mode.RELEASE -> {
                        log("Liberando o adaptador para entrar em espera")
                        job?.cancel()
                        waitForCar()
                        return@launch
                    }
                }
            }
        }
    }

    fun disconnect() {
        stopReconnect()
        job?.cancel()
        prefs.edit().remove(KEY_ADDRESS).apply()
        resumeLive = false
        viewModelScope.launch {
            disconnectInternal()
            _conn.value = ConnState.Idle
        }
    }

    private suspend fun disconnectInternal() {
        watchdog?.cancel()
        watchdog = null
        runCatching { link?.close() }
        runCatching { recorder?.close() }
        link = null
        elm = null
        recorder = null
        _busy.value = null
    }

    /** Roda uma operação por vez; a nova cancela a anterior. */
    private fun exclusive(label: String, userAction: Boolean = true, block: suspend (Elm327) -> Unit) {
        val e = elm ?: run { log("Conecte ao adaptador primeiro"); return }
        if (userAction) sleepPolicy?.touch(System.currentTimeMillis())
        val previous = job
        job = viewModelScope.launch {
            previous?.cancel()
            previous?.join()
            _busy.value = label
            jobLabel = label
            try {
                block(e)
            } catch (ex: CancellationException) {
                log("$label interrompido")
            } catch (ex: Exception) {
                log("$label falhou: ${ex.message}")
            } finally {
                if (_busy.value == label) _busy.value = null
                if (jobLabel == label) jobLabel = null
            }
        }
    }

    fun stop() {
        resumeLive = false
        job?.cancel()
    }

    // ---------- Leituras ----------

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

    fun startLive() = exclusive(LABEL_LIVE) { e ->
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
                if (p == FUEL_LEVEL_PID && v != null) {
                    val stored = _garage.value.lastFuelLevelPct
                    if (stored == null || kotlin.math.abs(v - stored) >= 1.0) recordFuelLevel(v)
                }
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
    fun watchLid(module: Module, lid: Int) = exclusive(LABEL_LID + "%02X".format(lid)) { e ->
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

    // ---------- Garagem ----------

    private fun updateGarage(change: (GarageData) -> GarageData) {
        val next = change(_garage.value)
        _garage.value = next
        viewModelScope.launch(Dispatchers.IO) { garageStore.save(next) }
    }

    fun updateVehicle(v: Vehicle) = updateGarage { it.copy(vehicle = v) }
    fun saveFuel(e: FuelEntry) = updateGarage { d -> d.copy(fuel = d.fuel.filterNot { it.id == e.id } + e) }
    fun deleteFuel(id: String) = updateGarage { d -> d.copy(fuel = d.fuel.filterNot { it.id == id }) }
    fun saveItem(i: MaintenanceItem) = updateGarage { d ->
        val idx = d.items.indexOfFirst { it.id == i.id }
        d.copy(items = if (idx < 0) d.items + i else d.items.toMutableList().also { it[idx] = i })
    }
    fun deleteItem(id: String) = updateGarage { d -> d.copy(items = d.items.filterNot { it.id == id }) }
    fun addRecord(r: MaintenanceRecord) = updateGarage { d -> d.copy(records = d.records + r) }
    fun deleteRecord(id: String) = updateGarage { d -> d.copy(records = d.records.filterNot { it.id == id }) }
    fun dismissRefuel() {
        _refuel.value = null
    }

    // ---------- Vencimentos ----------

    private fun updateDeadlines(change: (List<Deadline>) -> List<Deadline>) {
        val next = change(_deadlines.value)
        _deadlines.value = next
        viewModelScope.launch(Dispatchers.IO) { deadlineStore.save(next) }
    }

    fun saveDeadline(d: Deadline) = updateDeadlines { list ->
        val idx = list.indexOfFirst { it.id == d.id }
        if (idx < 0) list + d else list.toMutableList().also { it[idx] = d }
    }
    fun deleteDeadline(id: String) = updateDeadlines { list -> list.filterNot { it.id == id } }
    fun renewDeadline(id: String) = updateDeadlines { list -> list.map { if (it.id == id) Deadlines.renew(it) else it } }

    /** Ao conectar: preenche chassi e calibração se estiverem vazios e confere o nível do tanque. */
    private suspend fun identifyAndCheckFuel(e: Elm327) {
        val info = e.vehicleInfo(Modules.ENGINE)
        val v = _garage.value.vehicle
        val vin = v.vin.ifBlank { info["VIN"].orEmpty() }
        val cal = v.calibration.ifBlank { info["Calibração"].orEmpty() }
        if (vin != v.vin || cal != v.calibration) {
            updateGarage { it.copy(vehicle = it.vehicle.copy(vin = vin, calibration = cal)) }
            log("Carro identificado: chassi $vin, calibração $cal")
        }
        val (_, level) = e.readPid(Modules.ENGINE, FUEL_LEVEL_PID)
        if (level != null) recordFuelLevel(level) else log("A ECU não informa o nível do tanque (PID 2F)")
    }

    private fun recordFuelLevel(level: Double) {
        val previous = _garage.value.lastFuelLevelPct
        if (Garage.looksLikeRefuel(previous, level) && _refuel.value == null) {
            val tank = _garage.value.vehicle.tankLiters
            _refuel.value = RefuelPrompt(previous!!, level, tank?.let { (level - previous) / 100.0 * it })
            log("Tanque subiu de %.0f%% para %.0f%%".format(Locale.ROOT, previous, level))
        }
        updateGarage { it.copy(lastFuelLevelPct = level, lastFuelLevelAt = System.currentTimeMillis()) }
    }

    /** Junta as sessões gravadas, a garagem e os vencimentos num .zip para compartilhar. */
    fun exportZip(): File {
        val out = File(getApplication<Application>().cacheDir, "obd-livre-sessoes.zip")
        ZipOutputStream(out.outputStream()).use { zip ->
            for (f in listOf(garageFile, deadlinesFile)) {
                if (!f.exists()) continue
                zip.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            sessionsDir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                zip.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return out
    }

    companion object {
        const val FUEL_LEVEL_PID = 0x2F
        private const val LABEL_LIVE = "Ao vivo"
        private const val LABEL_LID = "Explorar 21"
        private const val KEY_ADDRESS = "ultimo_adaptador"
        private const val KEY_BLE = "ultimo_adaptador_ble"
        private const val KEY_NAME = "ultimo_adaptador_nome"
    }

    override fun onCleared() {
        scanner.stop()
        stopReconnect()
        watchdog?.cancel()
        job?.cancel()
        runCatching { recorder?.close() }
    }
}
