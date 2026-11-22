package co.rotalabs.obd.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import co.rotalabs.obd.android.BleLink
import co.rotalabs.obd.android.ClassicLink
import co.rotalabs.obd.android.DeviceScanner
import co.rotalabs.obd.android.FoundDevice
import co.rotalabs.obd.android.InstallResultReceiver
import co.rotalabs.obd.android.FloatingPanel
import co.rotalabs.obd.android.ObdService
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
import co.rotalabs.obd.core.FuelEstimate
import co.rotalabs.obd.core.Garage
import co.rotalabs.obd.core.GarageData
import co.rotalabs.obd.core.GarageStore
import co.rotalabs.obd.core.MaintenanceItem
import co.rotalabs.obd.core.MaintenanceRecord
import co.rotalabs.obd.core.Module
import co.rotalabs.obd.core.Modules
import co.rotalabs.obd.core.NdjsonRecorder
import co.rotalabs.obd.core.ObservingRecorder
import co.rotalabs.obd.core.PanelItems
import co.rotalabs.obd.core.PerfilMitsubishi
import co.rotalabs.obd.core.Formato
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.updateAndGet
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
data class LiveValue(
    val pid: Int,
    val name: String,
    val unit: String,
    val value: Double?,
    val status: String? = null,
    /** Valor em texto quando não é número (ex.: marcha "3ª", "P"). */
    val text: String? = null,
)

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
    /** Gravação automática da viagem ao ligar o motor (veja maybeAutoTrip). */
    private val _autoTrip = MutableStateFlow(prefs.getBoolean(KEY_AUTO_TRIP, true))
    val autoTrip: StateFlow<Boolean> = _autoTrip
    private var autoTripSuppressed = false
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

    // ---------- Painel flutuante ----------

    private val _pidReadings = MutableStateFlow<Map<Int, PanelItems.Reading>>(emptyMap())
    /** Últimas respostas do câmbio (21 08 e 21 02) com a hora, para o perfil da Pajero. */
    private val _transmission = MutableStateFlow<Map<String, Pair<IntArray, Long>>>(emptyMap())

    private fun profileActive(): Boolean = PerfilMitsubishi.active(_garage.value.vehicle.calibration)

    /** Resposta do câmbio se tiver menos de 60 s. */
    private fun transmission(req: String): IntArray? =
        _transmission.value[req]?.takeIf { System.currentTimeMillis() - it.second <= 60_000 }?.first

    /** Valores do perfil para o Ao vivo e o painel: temperatura do fluido e marcha. */
    private fun profileValue(id: Int): Pair<Double?, String?> = when (id) {
        PerfilMitsubishi.ATF -> PerfilMitsubishi.atf(transmission(PerfilMitsubishi.REQ_2108)) to null
        PerfilMitsubishi.GEAR -> null to PerfilMitsubishi.gearText(transmission(PerfilMitsubishi.REQ_2108), transmission(PerfilMitsubishi.REQ_2102))
        // Com a diferença para o painel informada na Garagem, mostra o mesmo número do painel.
        PerfilMitsubishi.ODO -> _transmission.value[PerfilMitsubishi.REQ_2103]?.first.let { PerfilMitsubishi.odometer(it) }
            ?.let { it + (_garage.value.vehicle.odometerOffsetKm ?: 0.0) } to null
        else -> null to null
    }

    private val trip = FuelEstimate.Trip()
    private val smoother = FuelEstimate.Smoother()

    /** Consumo instantâneo suavizado, já em texto ("11,8 km/L ▲", "∞ km/L"), para o painel. */
    private val _instantText = MutableStateFlow<String?>(null)
    private val _tripAverage = MutableStateFlow<Double?>(null)
    val tripAverage: StateFlow<Double?> = _tripAverage
    private val _appVisible = MutableStateFlow(true)
    private val _overlayAllowed = MutableStateFlow(Settings.canDrawOverlays(app))
    val overlayAllowed: StateFlow<Boolean> = _overlayAllowed
    private val _panelEnabled = MutableStateFlow(prefs.getBoolean(KEY_PANEL, false))
    val panelEnabled: StateFlow<Boolean> = _panelEnabled
    private val _panelItems = MutableStateFlow(PanelItems.decode(prefs.getString(KEY_PANEL_ITEMS, null)) ?: PanelItems.default)
    val panelItems: StateFlow<List<Int>> = _panelItems

    /** O ✕ do painel fecha até a próxima conexão (o interruptor do painel desliga de vez). */
    private val _panelDismissed = MutableStateFlow(false)

    private val panel = FloatingPanel(
        app,
        onMoved = { x, y -> prefs.edit().putInt(KEY_PANEL_X, x).putInt(KEY_PANEL_Y, y).apply() },
        onClose = {
            _panelDismissed.value = true
            log("Painel fechado até a próxima conexão")
        },
        onTap = {
            app.startActivity(
                Intent(app, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        },
    )

    init {
        keepRunningInBackground()
        driveFloatingPanel()
        autoConnectOnStart()
    }

    /** PIDs que o painel flutuante precisa, se ele estiver ligado. */
    private fun panelPids(): List<Int> = if (_panelEnabled.value) PanelItems.pidsToRead(_panelItems.value) else emptyList()

    fun setAppVisible(visible: Boolean) {
        _appVisible.value = visible
        if (visible) _overlayAllowed.value = Settings.canDrawOverlays(getApplication())
    }

    fun setPanelEnabled(enabled: Boolean) {
        _panelEnabled.value = enabled
        prefs.edit().putBoolean(KEY_PANEL, enabled).apply()
    }

    fun togglePanelItem(id: Int) {
        val next = PanelItems.toggle(_panelItems.value, id, PanelItems.options(_liveAvailable.value).map { it.id })
        _panelItems.value = next
        prefs.edit().putString(KEY_PANEL_ITEMS, PanelItems.encode(next)).apply()
    }

    /** Guarda o último valor de cada PID do motor, lido pelo Ao vivo ou pela Coleta guiada. */
    private fun observeReading(kind: String, fields: Map<String, Any?>) {
        // Câmbio da Pajero (serviço 21): guarda a última resposta do 21 08 e do 21 02, venha de onde vier.
        if ((kind == "lid_watch" || kind == "lid_sweep") && fields["status"] == "ok") {
            val req = fields["req"] as? String
            val mod = fields["mod"]
            val wanted = (mod == Modules.TRANSMISSION.id && (req == PerfilMitsubishi.REQ_2108 || req == PerfilMitsubishi.REQ_2102)) ||
                (mod == Modules.ENGINE.id && req == PerfilMitsubishi.REQ_2103)
            if (req != null && wanted) {
                PerfilMitsubishi.hex(fields["resp"] as? String)?.let { _transmission.value = _transmission.value + (req to (it to System.currentTimeMillis())) }
                if (req == PerfilMitsubishi.REQ_2103) rememberEcuOdometer()
            }
            return
        }
        if (kind != "pid" || fields["mod"] != Modules.ENGINE.id) return
        val pid = (fields["req"] as? String)?.takeIf { it.length == 4 }?.substring(2)?.toIntOrNull(16) ?: return
        val value = fields["value"] as? Double ?: return
        val now = System.currentTimeMillis()
        val readings = _pidReadings.value + (pid to PanelItems.Reading(value, now))
        _pidReadings.value = readings
        // (o resto da função trata os PIDs do motor)
        // A cada rotação lida, soma o consumo estimado da viagem com a carga e a velocidade mais recentes.
        if (pid == 0x0C) {
            val load = readings[0x04]?.takeIf { now - it.atMillis <= 10_000 }?.value
            val speed = readings[0x0D]?.takeIf { now - it.atMillis <= 10_000 }?.value
            if (load != null && speed != null) {
                trip.add(load, value, speed, now)
                _tripAverage.value = trip.average()
                smoother.add(load, value, speed, now)
                _instantText.value = FuelEstimate.instantText(smoother.value(), smoother.fuelCut(), trip.average())
            }
        }
    }


    /** Mostra o painel quando o app sai da tela (Maps por cima) e há conexão; some quando o app volta. */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun driveFloatingPanel() {
        data class Live(val conn: ConnState, val busy: String?, val readings: Map<Int, PanelItems.Reading>, val volts: Double?, val visible: Boolean)
        viewModelScope.launch {
            combine(
                combine(_conn, _busy, _pidReadings, _voltage, _appVisible) { c, b, r, v, vis -> Live(c, b, r, v, vis) },
                _panelEnabled, _panelItems, _overlayAllowed, _panelDismissed,
            ) { live, enabled, items, allowed, dismissed -> Triple(live, enabled && allowed && !dismissed, items) }
                // Atualizar o painel a cada leitura redesenhava a janela dezenas de vezes por segundo.
                .sample(1000)
                .collect { (live, on, items) ->
                val connected = live.conn is ConnState.Connected || live.conn is ConnState.Connecting
                if (!on || live.visible || !connected) {
                    panel.hide()
                    return@collect
                }
                if (!panel.visible) panel.show(prefs.getInt(KEY_PANEL_X, 24), prefs.getInt(KEY_PANEL_Y, 120))
                val recording = live.busy?.startsWith(LABEL_GUIDED) == true
                val status = when {
                    live.conn is ConnState.Connecting -> "● Aguardando o carro"
                    recording -> "● Gravando · " + live.busy!!.removePrefix("$LABEL_GUIDED · ")
                    live.busy == LABEL_LIVE -> "● Ao vivo"
                    live.busy != null -> "● ${live.busy}"
                    else -> "● Conectado · sem leitura"
                }
                val readings = if (live.busy == null) emptyMap() else live.readings
                val profile = PerfilMitsubishi.ids.filter { it in items }.associateWith { id ->
                    val (v, t) = profileValue(id)
                    t ?: v?.let { Formato.leitura(it, PerfilMitsubishi.unit(id)) + " " + PerfilMitsubishi.unit(id) } ?: "—"
                }.toMutableMap()
                // Consumo instantâneo suavizado, com ▲/▼ contra a média e ∞ no corte de injeção.
                if (LiveSelection.CONSUMPTION in items && live.busy != null) _instantText.value?.let { profile[LiveSelection.CONSUMPTION] = it }
                panel.update(status, recording, PanelItems.lines(items, readings, live.volts, System.currentTimeMillis(), _tripAverage.value, profile))
            }
        }
    }

    /**
     * Serviço em primeiro plano enquanto há adaptador conectado ou o app aguarda o carro: assim a leitura
     * e a gravação continuam com o Maps ou outro app na tela.
     */
    private fun keepRunningInBackground() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            combine(_conn, _busy) { c, b ->
                when (c) {
                    is ConnState.Connected -> "Conectado a ${c.name}" + (b?.let { " · $it" } ?: "")
                    is ConnState.Connecting -> if (c.name.contains("aguardando")) "Aguardando o carro ligar" else "Conectando ao adaptador"
                    else -> null
                }
            }.distinctUntilChanged().collect { text ->
                if (text == null) ObdService.hide(app) else ObdService.show(app, text)
            }
        }
    }

    // ---------- Atualização ----------

    private val updater = Updater(app)
    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update: StateFlow<UpdateState> = _update
    val installedVersion: String get() = updater.installedName

    /** Versão do app e do aparelho, gravada em toda sessão, relatório e exportação: o que mudou pode ser só a versão. */
    private fun versionFields(): Map<String, Any?> = mapOf(
        "app_version" to updater.installedName,
        "app_build" to updater.installedBuild,
        "android" to Build.VERSION.SDK_INT,
        "aparelho" to "${Build.MANUFACTURER} ${Build.MODEL}",
    )

    private fun versionLabel(): String = "versão ${updater.installedName} (build ${updater.installedBuild})"
    private var downloaded: File? = null

    /** Última versão vista no GitHub (para a aba Versão mostrar o que mudou) e quando se procurou. */
    private val _latestRelease = MutableStateFlow<UpdateCheck.Release?>(null)
    val latestRelease: StateFlow<UpdateCheck.Release?> = _latestRelease
    private val _lastCheckAt = MutableStateFlow<Long?>(null)
    val lastCheckAt: StateFlow<Long?> = _lastCheckAt
    private var lastCheckFailed = false
    val installedBuild: Long get() = updater.installedBuild
    val installedAt: Long get() = updater.installedAt

    init {
        // Depois das declarações acima: o Kotlin inicializa na ordem do arquivo.
        checkUpdate(manual = false)
        // O app pode ficar horas vivo (gravação em segundo plano): procura de novo de hora em hora.
        viewModelScope.launch {
            while (true) {
                delay(60 * 60_000L)
                checkUpdate(manual = false)
            }
        }
    }

    /**
     * Ao voltar para a tela: procura se a última busca tem mais de 15 min, ou mais de 2 min se falhou
     * (a central costuma ligar sem internet e ganhar internet depois).
     */
    private fun checkUpdateIfStale() {
        // Não mexe numa atualização em andamento (download, permissão, instalação) nem numa já encontrada.
        val s = _update.value
        if (s !is UpdateState.Idle && s !is UpdateState.UpToDate && s !is UpdateState.Failed) return
        val last = _lastCheckAt.value
        val wait = if (lastCheckFailed) 2 * 60_000L else 15 * 60_000L
        if (last == null || System.currentTimeMillis() - last >= wait) checkUpdate(manual = false)
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
                lastCheckFailed = false
                _lastCheckAt.value = System.currentTimeMillis()
                _latestRelease.value = release
                when {
                    release == null -> if (manual) UpdateState.Failed("A versão de teste não tem APK publicado", null) else UpdateState.Idle
                    UpdateCheck.isNewer(release, updater.installedBuild) -> UpdateState.Available(release).also {
                        log("Atualização disponível: build ${release.build} (instalado ${updater.installedBuild})")
                    }
                    else -> UpdateState.UpToDate(updater.installedBuild)
                }
            } catch (t: Throwable) {
                lastCheckFailed = true
                _lastCheckAt.value = System.currentTimeMillis()
                if (manual) UpdateState.Failed("Sem acesso ao GitHub: ${t.message ?: t.javaClass.simpleName}", null) else UpdateState.Idle
            }
        }
    }

    /** Instala a versão mais nova vista no GitHub, mesmo depois de "Depois" no aviso (aba Versão). */
    fun installLatest() {
        val latest = _latestRelease.value ?: return
        if (!UpdateCheck.isNewer(latest, updater.installedBuild)) return
        val s = _update.value
        if (s is UpdateState.Downloading || s is UpdateState.Installing) return
        _update.value = UpdateState.Available(latest)
        installUpdate()
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
        checkUpdateIfStale()
        val s = _update.value
        if (s is UpdateState.NeedsPermission && updater.canInstall()) installUpdate()
        // Voltou do instalador sem instalar (cancelou): oferece de novo.
        if (s is UpdateState.Installing) _update.value = UpdateState.Available(s.release)
    }

    fun dismissUpdate() {
        _update.value = UpdateState.Idle
    }

    // O log recebe uma linha por troca com o adaptador: guarda num buffer e publica no máximo 3x por segundo.
    private val logBuffer = ArrayDeque<String>()
    private var logPublishPending = false

    /** HH:mm:ss.SSS da hora local, sem SimpleDateFormat (caro demais para uma linha por leitura). */
    private fun clock(): String {
        val now = System.currentTimeMillis()
        val local = now + java.util.TimeZone.getDefault().getOffset(now)
        val ms = (local % 1000).toInt()
        val s = (local / 1000 % 60).toInt()
        val m = (local / 60_000 % 60).toInt()
        val h = (local / 3_600_000 % 24).toInt()
        val sb = StringBuilder(12)
        fun pad(v: Int, n: Int) { var d = 10; repeat(n - 1) { if (v < d) sb.append('0'); d *= 10 }; sb.append(v) }
        pad(h, 2); sb.append(':'); pad(m, 2); sb.append(':'); pad(s, 2); sb.append('.'); pad(ms, 3)
        return sb.toString()
    }

    private fun log(line: String) {
        synchronized(logBuffer) {
            logBuffer.addLast(clock() + " " + line)
            while (logBuffer.size > 400) logBuffer.removeFirst()
            if (logPublishPending) return
            logPublishPending = true
        }
        viewModelScope.launch {
            delay(300)
            synchronized(logBuffer) {
                logPublishPending = false
                _log.value = logBuffer.toList()
            }
        }
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
        autoTripSuppressed = false
        _panelDismissed.value = false
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
            rec.record("session", mapOf("device" to name, "link" to detail, "app" to "rota-obd") + versionFields())
            val e = Elm327(ElmExchanger(l), ObservingRecorder(rec, ::observeReading), ::log)
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
                } else {
                    viewModelScope.launch {
                        delay(500)
                        maybeAutoTrip(_voltage.value)
                    }
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
                // Ligou o motor depois de conectar (ex.: conectou só com a ignição): começa a gravar a viagem.
                if (mode == SleepPolicy.Mode.ACTIVE) maybeAutoTrip(volts)
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
        // Parou de propósito: a gravação automática só volta na próxima conexão.
        autoTripSuppressed = true
        job?.cancel()
    }

    // ---------- Gravação automática da viagem ----------


    fun setAutoTrip(on: Boolean) {
        _autoTrip.value = on
        prefs.edit().putBoolean(KEY_AUTO_TRIP, on).apply()
        if (on) maybeAutoTrip(_voltage.value)
    }

    /**
     * Com o adaptador conectado, o motor ligado (alternador carregando) e nada rodando, começa sozinho a
     * gravação da viagem da Coleta guiada. Assim toda viagem vira dado, sem tocar na central.
     */
    private fun maybeAutoTrip(volts: Double?) {
        if (!_autoTrip.value || autoTripSuppressed) return
        if (_conn.value !is ConnState.Connected || _busy.value != null || job?.isActive == true) return
        if (volts == null || volts < AUTO_TRIP_VOLTS) return
        val step = GuidedCollection.step("viagem") ?: return
        log("Gravação automática da viagem (motor ligado, %.1f V)".format(Locale.ROOT, volts))
        runGuidedStep(step)
    }

    // ---------- Leituras ----------

    fun runDiscovery(sweep21: Boolean) = exclusive("Descoberta") { e ->
        discover(e, sweep21, "relatorio_${System.currentTimeMillis()}.md")
    }

    /** LIDs do serviço 21 que responderam na última Descoberta completa (guardados entre sessões). */
    private fun knownLids(): Map<Module, List<Int>>? {
        val raw = prefs.getString(KEY_KNOWN_LIDS, null) ?: return null
        val byId = listOf(Modules.ENGINE, Modules.TRANSMISSION).associateBy { it.id }
        val map = raw.split(';').mapNotNull { part ->
            val (id, list) = part.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            val m = byId[id] ?: return@mapNotNull null
            m to list.split(',').mapNotNull { it.toIntOrNull(16) }
        }.toMap()
        return map.takeIf { it.isNotEmpty() }
    }

    private suspend fun discover(e: Elm327, sweep21: Boolean, reportName: String, fast: Boolean = false) {
        _report.value = null
        _progress.value = 0f
        val reports = Discovery(e, recorder!!) { step, fraction ->
            _progress.value = fraction
            _step.value = step
            log(step)
        }.run(sweepService21 = sweep21, knownLids = if (fast) knownLids() else null)
        engineSupported = reports.firstOrNull { it.module == Modules.ENGINE }?.supportedPids
        engineSupported?.let { setLiveAvailable(it) }
        _lidsFound.value = reports.associate { r ->
            r.module to r.lids.filter { it.result is ObdResult.Ok }.map { it.lid }
        }.filterValues { it.isNotEmpty() }
        if (sweep21 && !fast) {
            val found = _lidsFound.value
            if (found.isNotEmpty()) {
                prefs.edit().putString(KEY_KNOWN_LIDS, found.entries.joinToString(";") { (m, l) -> m.id + ":" + l.joinToString(",") { "%02X".format(it) } }).apply()
            }
        }
        val text = Discovery.textReport(adapterInfo, reports, versionLabel())
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
        val available = LiveSelection.order(LiveSelection.available(supported) + (if (profileActive()) PerfilMitsubishi.ids else emptyList()))
        // Na primeira vez que o perfil da Pajero aparece, marca câmbio e marcha mesmo em quem já tinha escolhido os dados.
        if (profileActive() && !prefs.getBoolean(KEY_PROFILE_OFFERED, false)) {
            LiveSelection.decode(prefs.getString(KEY_LIVE_SELECTION, null))?.let { saved ->
                prefs.edit().putString(KEY_LIVE_SELECTION, LiveSelection.encode(saved + PerfilMitsubishi.autoSelect)).apply()
            }
            prefs.edit().putBoolean(KEY_PROFILE_OFFERED, true).apply()
        }
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
                    val (value, unit) = when (id) {
                        LiveSelection.BOOST -> LiveSelection.boostBar(raw[0x0B]?.second, raw[0x33]?.second) to d.unit
                        LiveSelection.CONSUMPTION -> smoother.value() ?: (null to d.unit)
                        LiveSelection.TRIP_AVG -> _tripAverage.value to d.unit
                        else -> null to d.unit
                    }
                    val missing = d.needs.firstOrNull { raw[it]?.second == null }
                    val cut = id == LiveSelection.CONSUMPTION && smoother.fuelCut()
                    LiveValue(
                        id, d.name, unit, value, if (value == null && missing != null) status(raw[missing]?.first, null) else null,
                        text = if (cut) "∞" else null,
                    )
                } else if (id in PerfilMitsubishi.ids) {
                    val (value, text) = profileValue(id)
                    LiveValue(id, LiveSelection.name(id), LiveSelection.unit(id), value, text = text)
                } else {
                    val (r, v) = raw[id] ?: (null to null)
                    LiveValue(id, LiveSelection.name(id), LiveSelection.unit(id), LiveSelection.shown(id, v), status(r, v))
                }
            }
        }
        // Rápidos a cada volta; lentos (temperaturas, nível) a cada 10 voltas.
        val slow = setOf(0x05, 0x0F, 0x1F, 0x21, 0x2F, 0x30, 0x31, 0x33, 0x46, 0x5C, 0x63)
        var round = 0
        while (kotlin.coroutines.coroutineContext.isActive) {
            val selected = _liveSelection.value
            // O nível do tanque é lido mesmo desmarcado: alimenta a detecção de abastecimento.
            val reads = LiveSelection.pidsToRead(selected) + panelPids().filter { it in supported } +
                (if (FUEL_LEVEL_PID in supported) listOf(FUEL_LEVEL_PID) else emptyList())
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
            // Câmbio da Pajero: a cada 3 voltas lê o 21 08 (fluido, alavanca) e o 21 02 (marcha), se estiverem
            // no Ao vivo ou no painel. Trocar de módulo custa dois comandos, por isso não vai a cada volta.
            val wantProfile = (selected + (if (_panelEnabled.value) _panelItems.value else emptyList())).any { it in PerfilMitsubishi.ids }
            val wanted = selected + (if (_panelEnabled.value) _panelItems.value else emptyList())
            if (wantProfile && profileActive() && round % 3 == 0 && (PerfilMitsubishi.ATF in wanted || PerfilMitsubishi.GEAR in wanted)) {
                e.request(Modules.TRANSMISSION, PerfilMitsubishi.REQ_2108, 1000, kind = "lid_watch")
                e.request(Modules.TRANSMISSION, PerfilMitsubishi.REQ_2102, 1000, kind = "lid_watch")
                publish(_liveSelection.value)
            }
            if (PerfilMitsubishi.ODO in wanted && profileActive() && round % 20 == 0) {
                e.request(Modules.ENGINE, PerfilMitsubishi.REQ_2103, 1000, kind = "lid_watch")
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
        // O Ao vivo não volta sozinho por cima de um passo (ex.: motor liga entre o 1º e o 2º passo).
        resumeLive = false
        exclusive("$LABEL_GUIDED · ${step.title}") { e ->
            val rec = recorder
            val started = System.currentTimeMillis()
            _guidedRun.value = GuidedRun(step.id, started)
            runCatching {
                rec?.record(ExportLayout.MARKER_KIND, mapOf("event" to ExportLayout.EVENT_START, "step" to step.id, "title" to step.title) + versionFields())
            }
            log("Coleta guiada: ${step.title}")
            var status = "interrompido"
            try {
                when (action) {
                    // Depois da primeira Descoberta completa, as da coleta leem só os LIDs que já responderam.
                    is GuidedCollection.Action.Discovery ->
                        discover(e, true, ExportLayout.guidedReportName(started, step.id), fast = !action.full && knownLids() != null)
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
                if (openEnded) _live.value = emptyList()
                log("Coleta guiada: ${step.title} · $status")
                _guidedRun.value = null
            }
        }
    }

    /** Monta o Ao vivo a partir das leituras da gravação da viagem: só o que ela está lendo aparece. */
    private fun publishTripView() {
        val now = System.currentTimeMillis()
        val r = _pidReadings.value
        fun fresh(p: Int) = r[p]?.takeIf { now - it.atMillis <= PanelItems.STALE_MS }?.value
        _live.value = _liveSelection.value.mapNotNull { id ->
            val name = LiveSelection.name(id)
            when (id) {
                LiveSelection.BOOST -> LiveSelection.boostBar(fresh(0x0B), fresh(0x33))?.let { LiveValue(id, name, "bar", it) }
                LiveSelection.CONSUMPTION -> smoother.value()?.let { (v, u) -> LiveValue(id, name, u, v, text = if (smoother.fuelCut()) "∞" else null) }
                LiveSelection.TRIP_AVG -> LiveValue(id, name, "km/L", _tripAverage.value)
                in PerfilMitsubishi.ids -> profileValue(id).let { (v, t) -> if (v == null && t == null) null else LiveValue(id, name, LiveSelection.unit(id), v, text = t) }
                else -> fresh(id)?.let { LiveValue(id, name, LiveSelection.unit(id), LiveSelection.shown(id, it)) }
            }
        }
    }

    /** Lê as leituras do passo em rodízio até o tempo acabar (ou até parar, se não houver tempo). */
    private suspend fun watchReads(e: Elm327, a: GuidedCollection.Action.Watch) {
        val start = System.currentTimeMillis()
        var samples = 0
        // Rodízio: todos os LIDs que responderam na última Descoberta completa, fora os fixos do passo.
        val fixed = a.reads.toSet()
        val rotation = if (a.rotateKnown <= 0) emptyList() else
            knownLids().orEmpty().flatMap { (m, lids) -> lids.map { GuidedCollection.Read(m, 0x21, it) } }.filter { it !in fixed }
        var rotIdx = 0
        while (kotlin.coroutines.coroutineContext.isActive) {
            if (a.durationSec != null && System.currentTimeMillis() - start >= a.durationSec * 1000L) break
            // Na viagem, o que está marcado no painel flutuante também é lido, para ele não mostrar "—".
            val extra = if (a.durationSec != null) emptyList() else {
                val have = a.reads.filter { it.service == 0x01 && it.module == Modules.ENGINE }.map { it.id }.toSet()
                val supported = engineSupported
                panelPids().filter { it !in have && (supported == null || it in supported) }
                    .map { GuidedCollection.Read(Modules.ENGINE, 0x01, it) }
            }
            val rotated = if (rotation.isEmpty()) emptyList() else
                List(minOf(a.rotateKnown, rotation.size)) { rotation[(rotIdx + it) % rotation.size] }.also { rotIdx = (rotIdx + it.size) % rotation.size }
            // Na viagem, carga, rotação e velocidade entram a cada 3 leituras: o consumo instantâneo fica com
            // cerca de 1 s de atraso em vez de uma volta inteira (~3 s).
            val all = a.reads + extra + rotated
            val core = if (a.durationSec == null) all.filter { it.service == 0x01 && it.id in FAST_PIDS } else emptyList()
            val seq = if (core.isEmpty()) all else (all - core.toSet()).chunked(3).flatMap { core + it }.ifEmpty { core }
            for ((i, r) in seq.withIndex()) {
                if (r.service == 0x01) e.readPid(r.module, r.id) else e.request(r.module, r.hex, 1000, kind = "lid_watch")
                samples++
                // Na viagem, o Ao vivo mostra o que a gravação está lendo (cerca de 1x por segundo).
                if (a.durationSec == null && core.isNotEmpty() && i % (core.size + 3) == core.size - 1) publishTripView()
            }
            if (a.durationSec == null) publishTripView()
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
        // Atômico: a leitura do carro (contador de km, nível do tanque) também altera a Garagem, fora da tela.
        val next = _garage.updateAndGet(change)
        viewModelScope.launch(Dispatchers.IO) { garageStore.save(next) }
    }

    /** Salva o formulário do carro sem desfazer o contador da central lido enquanto ele estava aberto. */
    fun updateVehicle(v: Vehicle) = updateGarage { it.copy(vehicle = v.copy(ecuOdometerKm = it.vehicle.ecuOdometerKm)) }
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
        // Pajero: lê o contador de km da central a cada conexão, para o hodômetro da Garagem andar sozinho.
        if (PerfilMitsubishi.active(cal)) e.request(Modules.ENGINE, PerfilMitsubishi.REQ_2103, 1000, kind = "lid_watch")
        val (_, level) = e.readPid(Modules.ENGINE, FUEL_LEVEL_PID)
        if (level != null) recordFuelLevel(level) else log("A ECU não informa o nível do tanque (PID 2F)")
    }

    /** Guarda o contador de km da central na Garagem (grava o arquivo só quando muda, ~1 vez por km). */
    private fun rememberEcuOdometer() {
        if (!profileActive()) return
        val km = PerfilMitsubishi.odometer(_transmission.value[PerfilMitsubishi.REQ_2103]?.first) ?: return
        if (km == _garage.value.vehicle.ecuOdometerKm) return
        updateGarage { it.copy(vehicle = it.vehicle.copy(ecuOdometerKm = km)) }
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
            put("LEIA-ME.txt", ExportLayout.readme(markers, versionLabel()).toByteArray())
        }
        return out
    }

    companion object {
        const val FUEL_LEVEL_PID = 0x2F
        private const val LABEL_LIVE = "Ao vivo"
        private const val LABEL_LID = "Explorar 21"
        const val LABEL_GUIDED = "Coleta guiada"
        private const val KEY_PANEL = "painel_flutuante"
        private const val KEY_KNOWN_LIDS = "lids_conhecidos"
        private const val KEY_PROFILE_OFFERED = "perfil_mitsubishi_oferecido"
        private const val KEY_AUTO_TRIP = "gravar_viagem_sozinho"
        private val FAST_PIDS = setOf(0x04, 0x0C, 0x0D)
        private const val AUTO_TRIP_VOLTS = 13.0
        private const val KEY_PANEL_ITEMS = "painel_flutuante_itens"
        private const val KEY_PANEL_X = "painel_flutuante_x"
        private const val KEY_PANEL_Y = "painel_flutuante_y"
        private const val KEY_GUIDED_DONE = "coleta_guiada_feitos"
        private const val KEY_ADDRESS = "ultimo_adaptador"
        private const val KEY_BLE = "ultimo_adaptador_ble"
        private const val KEY_NAME = "ultimo_adaptador_nome"
        private const val KEY_LIVE_SELECTION = "ao_vivo_selecao"
        private const val KEY_LIVE_AVAILABLE = "ao_vivo_disponiveis"
    }

    override fun onCleared() {
        ObdService.hide(getApplication())
        panel.hide()
        scanner.stop()
        stopReconnect()
        watchdog?.cancel()
        job?.cancel()
        runCatching { recorder?.close() }
    }
}
