package co.rotalabs.obd.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import co.rotalabs.obd.android.BleLink
import co.rotalabs.obd.android.ClassicLink
import co.rotalabs.obd.android.DeviceScanner
import co.rotalabs.obd.android.FoundDevice
import co.rotalabs.obd.android.InstallResultReceiver
import co.rotalabs.obd.android.Updater
import co.rotalabs.obd.core.UpdateCheck
import co.rotalabs.obd.core.AdapterInfo
import co.rotalabs.obd.core.ByteLink
import co.rotalabs.obd.core.Deadline
import co.rotalabs.obd.core.DeadlineStore
import co.rotalabs.obd.core.Deadlines
import co.rotalabs.obd.core.Discovery
import co.rotalabs.obd.core.DtcCatalog
import co.rotalabs.obd.core.LiveSelection
import co.rotalabs.obd.core.Elm327
import co.rotalabs.obd.core.ElmExchanger
import co.rotalabs.obd.core.ExportLayout
import co.rotalabs.obd.core.GuidedCollection
import co.rotalabs.obd.core.FuelEntry
import co.rotalabs.obd.core.Garage
import co.rotalabs.obd.core.GarageData
import co.rotalabs.obd.core.GarageStore
import co.rotalabs.obd.core.MaintenanceItem
import co.rotalabs.obd.core.MaintenanceRecord
import co.rotalabs.obd.core.Module
import co.rotalabs.obd.core.Modules
import co.rotalabs.obd.core.NdjsonRecorder
import co.rotalabs.obd.core.ObdPids
import co.rotalabs.obd.core.ObdResult
import co.rotalabs.obd.core.SimulatedLink
import co.rotalabs.obd.core.SleepPolicy
import co.rotalabs.obd.core.Vehicle
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

/** Uma leitura ao vivo. Sem valor, [status] diz por quê (sem dados, tempo esgotado...): nunca vira zero. */
data class LiveValue(val pid: Int, val name: String, val unit: String, val value: Double?, val status: String? = null)

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
/** Andamento da atualização pelo próprio app. */
sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class UpToDate(val build: Long) : UpdateState()
    data class Available(val release: UpdateCheck.Release) : UpdateState()
    data class Downloading(val release: UpdateCheck.Release, val progress: Float?) : UpdateState()
    /** Falta liberar "instalar apps desconhecidos" para o Rota.OBD. */
    data class NeedsPermission(val release: UpdateCheck.Release) : UpdateState()
    data class Installing(val release: UpdateCheck.Release) : UpdateState()
    data class Failed(val message: String, val release: UpdateCheck.Release?) : UpdateState()
}

/** Códigos lidos logo antes de uma limpeza (modo 04) e se o módulo confirmou. */
data class ClearedCodes(val module: Module, val codes: List<String>, val atMillis: Long, val ok: Boolean, val readFailed: Boolean)

/** Passo da Coleta guiada em andamento. */
data class GuidedRun(val stepId: String, val startedAt: Long, val samples: Int = 0)

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
    /** Últimos códigos apagados, para a tela continuar mostrando o que era depois da limpeza. */
    private val _lastCleared = MutableStateFlow<ClearedCodes?>(null)
    val lastCleared: StateFlow<ClearedCodes?> = _lastCleared
    private val _watch = MutableStateFlow<LidWatch?>(null)
    val watch: StateFlow<LidWatch?> = _watch
    private val _lidsFound = MutableStateFlow<Map<Module, List<Int>>>(emptyMap())
    val lidsFound: StateFlow<Map<Module, List<Int>>> = _lidsFound
    /** Tensão lida no adaptador (ATRV). */
    private val _voltage = MutableStateFlow<Double?>(null)
    val voltage: StateFlow<Double?> = _voltage
    /** Estado do modo de espera; null sem conexão real (desconectado ou simulador). */
    private val _sleepMode = MutableStateFlow<SleepPolicy.Mode?>(null)
    val sleepMode: StateFlow<SleepPolicy.Mode?> = _sleepMode
    /** Etapa atual da descoberta, para mostrar na tela. */
    private val _step = MutableStateFlow<String?>(null)
    val step: StateFlow<String?> = _step

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
    private val prefs = app.getSharedPreferences("rota-obd", Context.MODE_PRIVATE)
    private val _guidedDone = MutableStateFlow(prefs.getStringSet(KEY_GUIDED_DONE, null)?.toSet() ?: emptySet())
    val guidedDone: StateFlow<Set<String>> = _guidedDone
    private val _guidedRun = MutableStateFlow<GuidedRun?>(null)
    val guidedRun: StateFlow<GuidedRun?> = _guidedRun

    /** O que este carro permite mostrar no Ao vivo (guardado da última leitura) e o que está marcado. */
    private val _liveAvailable = MutableStateFlow(LiveSelection.decode(prefs.getString(KEY_LIVE_AVAILABLE, null))?.let { LiveSelection.order(it) } ?: emptyList())
    val liveAvailable: StateFlow<List<Int>> = _liveAvailable
    private val _liveSelection = MutableStateFlow(LiveSelection.effective(LiveSelection.decode(prefs.getString(KEY_LIVE_SELECTION, null)), _liveAvailable.value))
    val liveSelection: StateFlow<List<Int>> = _liveSelection

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

    // ---------- Atualização ----------

    private val updater = Updater(app)
    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update: StateFlow<UpdateState> = _update
    val installedVersion: String get() = updater.installedName
    private var downloaded: File? = null

    init {
        // Depois das declarações acima: o Kotlin inicializa na ordem do arquivo.
        checkUpdate(manual = false)
    }

    /**
     * Procura a versão de teste no GitHub. Na abertura do app ([manual] falso) falha em silêncio:
     * a central costuma estar sem internet no carro.
     */
    fun checkUpdate(manual: Boolean) {
        val s = _update.value
        if (s is UpdateState.Checking || s is UpdateState.Downloading || s is UpdateState.Installing) return
        _update.value = UpdateState.Checking
        viewModelScope.launch {
            _update.value = try {
                val release = updater.fetchLatest()
                when {
                    release == null -> if (manual) UpdateState.Failed("A versão de teste não tem APK publicado", null) else UpdateState.Idle
                    UpdateCheck.isNewer(release, updater.installedBuild) -> UpdateState.Available(release).also {
                        log("Atualização disponível: build ${release.build} (instalado ${updater.installedBuild})")
                    }
                    else -> UpdateState.UpToDate(updater.installedBuild)
                }
            } catch (t: Throwable) {
                if (manual) UpdateState.Failed("Sem acesso ao GitHub: ${t.message ?: t.javaClass.simpleName}", null) else UpdateState.Idle
            }
        }
    }

    fun installUpdate() {
        val release = when (val s = _update.value) {
            is UpdateState.Available -> s.release
            is UpdateState.NeedsPermission -> s.release
            is UpdateState.Failed -> s.release ?: return
            else -> return
        }
        if (!updater.canInstall()) {
            _update.value = UpdateState.NeedsPermission(release)
            updater.openInstallPermission()
            return
        }
        viewModelScope.launch {
            try {
                val apk = downloaded?.takeIf { it.exists() && it.name.contains("-${release.build}.") }
                    ?: updater.download(release) { p -> _update.value = UpdateState.Downloading(release, p) }.also { downloaded = it }
                _update.value = UpdateState.Installing(release)
                InstallResultReceiver.listener = { msg ->
                    log("Instalação recusada: $msg")
                    _update.value = UpdateState.Failed(
                        if ("UPDATE_INCOMPATIBLE" in msg) {
                            "A versão instalada tem outra assinatura. Desinstale o Rota.OBD uma vez e instale de novo; depois disso as atualizações seguem por aqui."
                        } else {
                            "Instalação recusada: $msg"
                        },
                        release,
                    )
                }
                log("Instalando build ${release.build}")
                updater.install(apk)
            } catch (t: Throwable) {
                _update.value = UpdateState.Failed("Não deu para baixar: ${t.message ?: t.javaClass.simpleName}", release)
            }
        }
    }

    /** Ao voltar das configurações, segue a instalação se a permissão foi dada. */
    fun onResume() {
        val s = _update.value
        if (s is UpdateState.NeedsPermission && updater.canInstall()) installUpdate()
        // Voltou do instalador sem instalar (cancelou): oferece de novo.
        if (s is UpdateState.Installing) _update.value = UpdateState.Available(s.release)
    }

    fun dismissUpdate() {
        _update.value = UpdateState.Idle
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
            rec.record("session", mapOf("device" to name, "link" to detail, "app" to "rota-obd"))
            val e = Elm327(ElmExchanger(l), rec, ::log)
            elm = e
            val info = e.initialize()
            adapterInfo = info
            _voltage.value = SleepPolicy.parseVoltage(info.voltage)
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
        _sleepMode.value = SleepPolicy.Mode.ACTIVE
        watchdog = viewModelScope.launch {
            while (isActive) {
                delay(if (quiet) 60_000L else 30_000L)
                val volts = SleepPolicy.parseVoltage(e.raw("ATRV", 1500))
                if (volts != null) _voltage.value = volts
                val mode = policy.observe(System.currentTimeMillis(), volts)
                _sleepMode.value = mode
                when (mode) {
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
                        if (jobLabel == LABEL_LIVE || jobLabel?.startsWith(LABEL_LID) == true || guidedWatchRunning()) {
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
        _sleepMode.value = null
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
        discover(e, sweep21, "relatorio_${System.currentTimeMillis()}.md")
    }

    private suspend fun discover(e: Elm327, sweep21: Boolean, reportName: String) {
        _report.value = null
        _progress.value = 0f
        val reports = Discovery(e, recorder!!) { step, fraction ->
            _progress.value = fraction
            _step.value = step
            log(step)
        }.run(sweepService21 = sweep21)
        engineSupported = reports.firstOrNull { it.module == Modules.ENGINE }?.supportedPids
        engineSupported?.let { setLiveAvailable(it) }
        _lidsFound.value = reports.associate { r ->
            r.module to r.lids.filter { it.result is ObdResult.Ok }.map { it.lid }
        }.filterValues { it.isNotEmpty() }
        val text = Discovery.textReport(adapterInfo, reports)
        File(sessionsDir, reportName).writeText(text)
        _report.value = text
        _step.value = null
    }

    /** Marca ou desmarca um dado do Ao vivo; vale na hora, inclusive com a leitura em andamento. */
    fun setLiveSelected(id: Int, selected: Boolean) {
        val current = _liveSelection.value.toMutableSet()
        if (selected) current.add(id) else current.remove(id)
        val ordered = LiveSelection.order(current)
        _liveSelection.value = ordered
        prefs.edit().putString(KEY_LIVE_SELECTION, LiveSelection.encode(ordered)).apply()
    }

    private fun setLiveAvailable(supported: List<Int>) {
        val available = LiveSelection.available(supported)
        _liveAvailable.value = available
        prefs.edit().putString(KEY_LIVE_AVAILABLE, LiveSelection.encode(available)).apply()
        _liveSelection.value = LiveSelection.effective(LiveSelection.decode(prefs.getString(KEY_LIVE_SELECTION, null)), available)
    }

    fun startLive() = exclusive(LABEL_LIVE) { e ->
        val supported = engineSupported ?: e.supportedPids(Modules.ENGINE).also { engineSupported = it }
        setLiveAvailable(supported)
        if (_liveAvailable.value.isEmpty()) {
            log("O motor não informou PIDs padrão com fórmula conhecida")
            return@exclusive
        }
        val raw = HashMap<Int, Pair<ObdResult, Double?>>()
        fun status(r: ObdResult?, v: Double?): String? = when {
            v != null -> null
            r == null -> null
            r is ObdResult.Ok -> "não decodificada · ${r.hex}"
            else -> Discovery.describe(r)
        }
        fun publish(selected: List<Int>) {
            _live.value = selected.map { id ->
                val d = LiveSelection.derived[id]
                if (d != null) {
                    val value = if (id == LiveSelection.BOOST) LiveSelection.boostBar(raw[0x0B]?.second, raw[0x33]?.second) else null
                    val missing = d.needs.firstOrNull { raw[it]?.second == null }
                    LiveValue(id, d.name, d.unit, value, if (value == null && missing != null) status(raw[missing]?.first, null) else null)
                } else {
                    val (r, v) = raw[id] ?: (null to null)
                    LiveValue(id, LiveSelection.name(id), LiveSelection.unit(id), v, status(r, v))
                }
            }
        }
        // Rápidos a cada volta; lentos (temperaturas, nível) a cada 10 voltas.
        val slow = setOf(0x05, 0x0F, 0x1F, 0x21, 0x2F, 0x30, 0x31, 0x33, 0x46, 0x5C, 0x63)
        var round = 0
        while (kotlin.coroutines.coroutineContext.isActive) {
            val selected = _liveSelection.value
            // O nível do tanque é lido mesmo desmarcado: alimenta a detecção de abastecimento.
            val reads = LiveSelection.pidsToRead(selected) + (if (FUEL_LEVEL_PID in supported) listOf(FUEL_LEVEL_PID) else emptyList())
            publish(selected)
            for (p in reads.distinct()) {
                if (p in slow && round % 10 != 0 && raw.containsKey(p)) continue
                val (r, v) = e.readPid(Modules.ENGINE, p)
                raw[p] = r to v
                if (p == FUEL_LEVEL_PID && v != null) {
                    val stored = _garage.value.lastFuelLevelPct
                    if (stored == null || kotlin.math.abs(v - stored) >= 1.0) recordFuelLevel(v)
                }
                publish(_liveSelection.value)
            }
            if (reads.isEmpty()) delay(500)
            round++
        }
    }

    fun readDtcs() = exclusive("Leitura de falhas") { e ->
        _dtcs.value = e.readDtcs(Modules.ENGINE) + e.readDtcs(Modules.TRANSMISSION)
    }

    fun clearDtcs(module: Module) = exclusive("Limpeza de falhas") { e ->
        // Lê antes de apagar: depois da limpeza o módulo não guarda mais o que havia.
        val reads = e.readDtcs(module).filter { it.mode == "03" || it.mode == "07" }
        // Leitura sem resposta não é "sem códigos": não dá para saber o que foi apagado.
        val readFailed = reads.any { it.codes == null }
        val before = reads.flatMap { it.codes.orEmpty() }.distinct()
        val r = e.clearDtcs(module)
        val described = before.joinToString("; ") { "$it ${DtcCatalog.describe(it).summary()}" }
        val what = when {
            before.isNotEmpty() -> "apagados: $described" + if (readFailed) " (leitura incompleta)" else ""
            readFailed -> "não foi possível ler os códigos antes"
            else -> "não havia códigos"
        }
        log("Limpeza em ${module.name}: ${Discovery.describe(r)} · $what")
        _lastCleared.value = ClearedCodes(module, before, System.currentTimeMillis(), r is ObdResult.Ok, readFailed)
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

    // ---------- Coleta guiada ----------

    private fun guidedWatchRunning(): Boolean =
        jobLabel?.startsWith(LABEL_GUIDED) == true &&
            _guidedRun.value?.let { GuidedCollection.step(it.stepId)?.action } is GuidedCollection.Action.Watch

    fun setGuidedDone(stepId: String, done: Boolean) {
        val next = if (done) _guidedDone.value + stepId else _guidedDone.value - stepId
        _guidedDone.value = next
        prefs.edit().putStringSet(KEY_GUIDED_DONE, next).apply()
    }

    /** Começa uma coleta nova: desmarca os passos. O que já foi gravado continua nas sessões. */
    fun resetGuided() {
        _guidedDone.value = emptySet()
        prefs.edit().remove(KEY_GUIDED_DONE).apply()
    }

    /** Roda um passo, gravando um marcador `coleta` no início e outro no fim da sessão. */
    fun runGuidedStep(step: GuidedCollection.Step) {
        val action = step.action
        if (action is GuidedCollection.Action.Manual) {
            setGuidedDone(step.id, true)
            return
        }
        exclusive("$LABEL_GUIDED · ${step.title}") { e ->
            val rec = recorder
            val started = System.currentTimeMillis()
            _guidedRun.value = GuidedRun(step.id, started)
            runCatching {
                rec?.record(ExportLayout.MARKER_KIND, mapOf("event" to ExportLayout.EVENT_START, "step" to step.id, "title" to step.title))
            }
            log("Coleta guiada: ${step.title}")
            var status = "interrompido"
            try {
                when (action) {
                    GuidedCollection.Action.Discovery -> discover(e, true, ExportLayout.guidedReportName(started, step.id))
                    is GuidedCollection.Action.Watch -> watchReads(e, action)
                    GuidedCollection.Action.Manual -> Unit
                }
                status = "concluído"
                setGuidedDone(step.id, true)
            } finally {
                // A gravação da viagem não tem duração: termina quando para, e vale se gravou ao menos 1 minuto.
                val openEnded = action is GuidedCollection.Action.Watch && action.durationSec == null
                if (openEnded && System.currentTimeMillis() - started >= 60_000L) {
                    status = "gravado"
                    setGuidedDone(step.id, true)
                }
                runCatching {
                    rec?.record(ExportLayout.MARKER_KIND, mapOf("event" to ExportLayout.EVENT_END, "step" to step.id, "status" to status))
                }
                log("Coleta guiada: ${step.title} · $status")
                _guidedRun.value = null
            }
        }
    }

    /** Lê as leituras do passo em rodízio até o tempo acabar (ou até parar, se não houver tempo). */
    private suspend fun watchReads(e: Elm327, a: GuidedCollection.Action.Watch) {
        val start = System.currentTimeMillis()
        var samples = 0
        while (kotlin.coroutines.coroutineContext.isActive) {
            if (a.durationSec != null && System.currentTimeMillis() - start >= a.durationSec * 1000L) break
            for (r in a.reads) {
                if (r.service == 0x01) e.readPid(r.module, r.id) else e.request(r.module, r.hex, 1000, kind = "lid_watch")
                samples++
            }
            _guidedRun.value = _guidedRun.value?.copy(samples = samples)
            delay(50)
        }
    }

    /** Quantos arquivos estão gravados e quanto ocupam. Nada é apagado sozinho. */
    fun storedData(): Pair<Int, Long> {
        val files = sessionsDir.listFiles().orEmpty().filter { it.isFile }
        return files.size to files.sumOf { it.length() }
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

    /** Junta tudo num .zip organizado por categoria (veja [ExportLayout]) para compartilhar. */
    fun exportZip(): File {
        val out = File(getApplication<Application>().cacheDir, "rota-obd-sessoes.zip")
        val files = sessionsDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
        val markers = mutableListOf<ExportLayout.Marker>()
        ZipOutputStream(out.outputStream()).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            for (f in listOf(garageFile, deadlinesFile) + files) {
                if (!f.exists()) continue
                zip.putNextEntry(ZipEntry(ExportLayout.entryFor(f.name)))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            for (f in files.filter { it.name.endsWith(".ndjson") }) {
                markers += f.useLines { lines -> lines.mapNotNull(ExportLayout::marker).toList() }
                val parts = f.useLines { ExportLayout.splitByStep(it) }
                for ((step, lines) in parts) {
                    put("${ExportLayout.stepFolder(step)}/${f.name}", lines.joinToString("\n", postfix = "\n").toByteArray())
                }
            }
            put("LEIA-ME.txt", ExportLayout.readme(markers).toByteArray())
        }
        return out
    }

    companion object {
        const val FUEL_LEVEL_PID = 0x2F
        private const val LABEL_LIVE = "Ao vivo"
        private const val LABEL_LID = "Explorar 21"
        const val LABEL_GUIDED = "Coleta guiada"
        private const val KEY_GUIDED_DONE = "coleta_guiada_feitos"
        private const val KEY_ADDRESS = "ultimo_adaptador"
        private const val KEY_BLE = "ultimo_adaptador_ble"
        private const val KEY_NAME = "ultimo_adaptador_nome"
        private const val KEY_LIVE_SELECTION = "ao_vivo_selecao"
        private const val KEY_LIVE_AVAILABLE = "ao_vivo_disponiveis"
    }

    override fun onCleared() {
        scanner.stop()
        stopReconnect()
        watchdog?.cancel()
        job?.cancel()
        runCatching { recorder?.close() }
    }
}
