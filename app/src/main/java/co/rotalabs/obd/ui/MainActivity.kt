package co.rotalabs.obd.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import co.rotalabs.obd.R
import co.rotalabs.obd.core.Discovery
import co.rotalabs.obd.core.Formato
import co.rotalabs.obd.core.LiveMetrics
import co.rotalabs.obd.core.Module
import co.rotalabs.obd.core.Modules
import co.rotalabs.obd.core.SleepPolicy
import co.rotalabs.obd.ui.theme.Rl
import co.rotalabs.obd.ui.theme.RlIcons
import co.rotalabs.obd.ui.theme.RlType
import co.rotalabs.obd.ui.theme.RotaTheme

class MainActivity : ComponentActivity() {
    private val vm: ObdViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            RotaTheme {
                Surface(Modifier.fillMaxSize(), color = Rl.bg) {
                    App(vm, ::shareText, ::shareZip)
                }
            }
        }
    }

    private fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, "Compartilhar relatório"))
    }

    private fun shareZip() {
        val file = vm.exportZip()
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND).setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Exportar sessões"))
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    LIVE("Ao vivo", RlIcons.gauge),
    DTC("Falhas", RlIcons.alert),
    GARAGE("Garagem", RlIcons.car),
    DISCOVERY("Descoberta", RlIcons.radar),
    LID("Explorar 21", RlIcons.binary),
    LOG("Registro", RlIcons.log),
    CONNECT("Conexão", RlIcons.bluetooth),
}

@Composable
private fun App(vm: ObdViewModel, shareText: (String) -> Unit, shareZip: () -> Unit) {
    val conn by vm.conn.collectAsState()
    var tab by remember { mutableStateOf(if (vm.conn.value is ConnState.Idle) Tab.CONNECT else Tab.LIVE) }
    var wasConnected by remember { mutableStateOf(conn is ConnState.Connected) }
    // Ao conectar a partir da tela de conexão, leva direto para as leituras.
    LaunchedEffect(conn) {
        val now = conn is ConnState.Connected
        if (now && !wasConnected && tab == Tab.CONNECT) tab = Tab.LIVE
        wasConnected = now
    }
    RefuelDialog(vm)
    Row(Modifier.fillMaxSize()) {
        NavRail(vm, tab) { tab = it }
        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 20.dp, vertical = 16.dp)) {
            BusyStrip(vm, tab)
            when (tab) {
                Tab.CONNECT -> ConnectScreen(vm)
                Tab.GARAGE -> GarageScreen(vm)
                Tab.DISCOVERY -> DiscoveryScreen(vm, shareText, shareZip)
                Tab.LIVE -> LiveScreen(vm) { tab = Tab.CONNECT }
                Tab.DTC -> DtcScreen(vm)
                Tab.LID -> LidScreen(vm)
                Tab.LOG -> LogScreen(vm)
            }
        }
    }
}

/** Mostra operações de fundo que a tela atual não controla (ex.: identificação do carro). */
@Composable
private fun BusyStrip(vm: ObdViewModel, tab: Tab) {
    val busy by vm.busy.collectAsState()
    val b = busy ?: return
    val owned = (tab == Tab.LIVE && b == "Ao vivo") || (tab == Tab.DISCOVERY && b == "Descoberta") ||
        (tab == Tab.LID && b.startsWith("Explorar 21"))
    if (owned) return
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp)
            .background(Rl.raised, RoundedCornerShape(Rl.radiusMd))
            .border(1.dp, Rl.line, RoundedCornerShape(Rl.radiusMd))
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Dot(Rl.accent, 8.dp)
        Text("$b em andamento", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Rl.text2)
        GhostButton("Parar", vm::stop)
    }
}

// ---------- Menu lateral ----------

@Composable
private fun NavRail(vm: ObdViewModel, current: Tab, onSelect: (Tab) -> Unit) {
    val dtcs by vm.dtcs.collectAsState()
    val conn by vm.conn.collectAsState()
    val volts by vm.voltage.collectAsState()
    val stored = dtcs.orEmpty().filter { it.mode == "03" }.sumOf { it.codes?.size ?: 0 }
    Column(
        Modifier.width(104.dp).fillMaxHeight().background(Rl.sunken).padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(painterResource(R.drawable.rotalabs_mark), contentDescription = "Rota.OBD", modifier = Modifier.size(34.dp))
        Spacer(Modifier.height(10.dp))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Tab.entries.filter { it != Tab.CONNECT }.forEach { t ->
                RailItem(t.label, t.icon, current == t, badge = if (t == Tab.DTC && stored > 0) stored.toString() else null) { onSelect(t) }
            }
        }
        Spacer(Modifier.height(8.dp))
        val (dot, text) = when (val c = conn) {
            is ConnState.Connected -> Rl.success to (volts?.let { Formato.numero(it, 1) + " V" } ?: "Conectado")
            is ConnState.Connecting -> Rl.warning to (if (c.name.contains("aguardando")) "Espera" else "Ligando")
            is ConnState.Failed -> Rl.danger to "Falhou"
            ConnState.Idle -> Rl.text3 to "Conectar"
        }
        val on = current == Tab.CONNECT
        Column(
            Modifier.width(92.dp).defaultMinSize(minHeight = 80.dp)
                .background(if (on) Rl.accentSoft else Color.Transparent, RoundedCornerShape(Rl.radiusMd))
                .border(1.dp, if (on) Rl.accentLine else Rl.line, RoundedCornerShape(Rl.radiusMd))
                .clickable(role = Role.Tab) { onSelect(Tab.CONNECT) }
                .semantics { selected = on; contentDescription = "Conexão: $text" }
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(RlIcons.bluetooth, null, tint = if (on) Rl.accent else Rl.text2, modifier = Modifier.size(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Dot(dot, 8.dp)
                Text(text, style = RlType.mono, fontSize = 13.sp, color = Rl.text, maxLines = 1)
            }
        }
    }
}

@Composable
private fun RailItem(label: String, icon: ImageVector, selected: Boolean, badge: String?, onClick: () -> Unit) {
    Box {
        Column(
            Modifier.width(92.dp).defaultMinSize(minHeight = 72.dp)
                .background(if (selected) Rl.accentSoft else Color.Transparent, RoundedCornerShape(Rl.radiusMd))
                .border(1.dp, if (selected) Rl.accentLine else Color.Transparent, RoundedCornerShape(Rl.radiusMd))
                .clickable(role = Role.Tab) { onClick() }
                .semantics { this.selected = selected }
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, null, tint = if (selected) Rl.accent else Rl.text2, modifier = Modifier.size(26.dp))
            Text(
                label, style = MaterialTheme.typography.labelSmall, color = if (selected) Rl.accent else Rl.text2,
                maxLines = 1, textAlign = TextAlign.Center,
            )
        }
        badge?.let {
            Text(
                it, style = RlType.mono, fontSize = 12.sp, color = Rl.onAccent, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 14.dp)
                    .background(Rl.danger, RoundedCornerShape(50)).defaultMinSize(minWidth = 20.dp).padding(horizontal = 5.dp),
            )
        }
    }
}

// ---------- Abastecimento detectado ----------

@Composable
private fun RefuelDialog(vm: ObdViewModel) {
    val prompt by vm.refuel.collectAsState()
    val garage by vm.garage.collectAsState()
    prompt?.let { p ->
        FuelDialog(
            initial = null,
            suggestedOdometer = co.rotalabs.obd.core.Garage.currentOdometer(garage),
            suggestedLiters = p.suggestedLiters,
            onDismiss = vm::dismissRefuel,
            onSave = { vm.saveFuel(it); vm.dismissRefuel() },
            onDelete = null,
            title = "Abastecimento detectado: ${Formato.numero(p.fromPct, 0)}% → ${Formato.numero(p.toPct, 0)}%",
        )
    }
}

// ---------- Ao vivo ----------

@Composable
private fun LiveScreen(vm: ObdViewModel, goConnect: () -> Unit) {
    val values by vm.live.collectAsState()
    val busy by vm.busy.collectAsState()
    val conn by vm.conn.collectAsState()
    val garage by vm.garage.collectAsState()
    val running = busy == "Ao vivo"
    val connected = conn is ConnState.Connected
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenHeader("Ao vivo · motor 7E0", garage.vehicle.name) {
            if (connected) Badge(if (running) "Gravando sessão" else "Conectado")
            if (running) {
                SecondaryButton("Parar", vm::stop, icon = RlIcons.stop)
            } else {
                PrimaryButton("Começar leitura", vm::startLive, icon = RlIcons.play, enabled = connected && busy == null)
            }
        }
        if (values.isEmpty()) {
            if (!connected) {
                EmptyState(
                    "Nenhum adaptador conectado",
                    "Conecte o adaptador OBD-II ou use o modo demonstração para ver as leituras.",
                ) { PrimaryButton("Ir para a conexão", goConnect, icon = RlIcons.bluetooth) }
            } else {
                EmptyState(
                    "Pronto para ler",
                    "Toque em Começar leitura. O app lê só os PIDs que o motor diz suportar e grava tudo na sessão.",
                )
            }
            return
        }
        val byPid = values.associateBy { it.pid }
        val speed = byPid[LiveMetrics.PID_SPEED]
        val rate = byPid[LiveMetrics.PID_FUEL_RATE]
        val heroPids = buildList {
            if (speed != null) add(LiveMetrics.PID_SPEED)
            if (byPid.containsKey(LiveMetrics.PID_RPM)) add(LiveMetrics.PID_RPM)
            if (rate == null && byPid.containsKey(LiveMetrics.PID_COOLANT)) add(LiveMetrics.PID_COOLANT)
        }
        Row(Modifier.fillMaxWidth().weight(0.46f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            heroPids.forEach { pid -> LiveTile(byPid.getValue(pid), Modifier.weight(1f).fillMaxHeight(), hero = true) }
            if (rate != null) ConsumptionTile(speed, rate, Modifier.weight(1f).fillMaxHeight())
        }
        val rest = values.filter { it.pid !in heroPids && it.pid != LiveMetrics.PID_FUEL_RATE }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(250.dp),
            modifier = Modifier.weight(0.54f),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(rest, key = { it.pid }) { v -> LiveTile(v, Modifier.height(124.dp)) }
        }
    }
}

@Composable
private fun LiveTile(v: LiveValue, modifier: Modifier, hero: Boolean = false) {
    val level = LiveMetrics.level(v.pid, v.value)
    val limit = LiveMetrics.limits[v.pid]
    val caption = when {
        v.value == null -> v.status?.let { if (it.startsWith("não")) "Resposta $it" else "Sem resposta · $it" } ?: "Aguardando leitura"
        level == LiveMetrics.Level.DANGER && limit != null -> "Acima de ${Formato.leitura(limit.second, v.unit)} ${v.unit}"
        level == LiveMetrics.Level.WARNING && limit != null -> "Acima de ${Formato.leitura(limit.first, v.unit)} ${v.unit}"
        else -> "PID %02X".format(v.pid)
    }
    MetricTile(
        label = v.name,
        value = v.value?.let { Formato.leitura(it, v.unit) },
        unit = v.unit,
        modifier = modifier,
        caption = caption,
        tone = level.tone(),
        maxNumber = if (hero) 112.sp else 44.sp,
    )
}

/** km/L a partir da velocidade e da vazão (PID 5E). Parado, mostra a vazão em L/h. */
@Composable
private fun ConsumptionTile(speed: LiveValue?, rate: LiveValue, modifier: Modifier) {
    val kmL = LiveMetrics.kmPerLiter(speed?.value, rate.value)
    val lph = rate.value
    when {
        kmL != null && lph != null -> MetricTile(
            "Consumo agora", Formato.numero(kmL, 1), "km/L", modifier,
            caption = "${Formato.numero(lph, 1)} L/h", badge = "PID 5E", maxNumber = 112.sp,
        )
        lph != null -> MetricTile(
            "Consumo agora", Formato.numero(lph, 1), "L/h", modifier,
            caption = "Parado: vazão em litros por hora", badge = "PID 5E", maxNumber = 112.sp,
        )
        else -> MetricTile(
            "Consumo agora", null, "", modifier,
            caption = "Sem resposta · " + (rate.status ?: "aguardando"), badge = "PID 5E", maxNumber = 112.sp,
        )
    }
}

// ---------- Falhas ----------

private val dtcModes = listOf("03", "07", "0A")
private fun modeLabel(mode: String) = when (mode) {
    "03" -> "Armazenados"
    "07" -> "Pendentes"
    else -> "Permanentes"
}

@Composable
private fun DtcScreen(vm: ObdViewModel) {
    val dtcs by vm.dtcs.collectAsState()
    val busy by vm.busy.collectAsState()
    val conn by vm.conn.collectAsState()
    var mode by remember { mutableStateOf("03") }
    var confirm by remember { mutableStateOf<Module?>(null) }
    val canAct = busy == null && conn is ConnState.Connected
    val list = dtcs
    val total = list.orEmpty().sumOf { it.codes?.size ?: 0 }
    val title = when {
        list == null -> "Códigos ainda não lidos"
        total == 0 -> "Nenhum código de falha"
        total == 1 -> "1 código de falha"
        else -> "$total códigos de falha"
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenHeader("Códigos de falha · por módulo", title) {
            PrimaryButton(if (list == null) "Ler códigos" else "Ler de novo", vm::readDtcs, icon = RlIcons.refresh, enabled = canAct)
        }
        PillTabs(
            dtcModes, mode,
            { m -> modeLabel(m) + (list?.let { l -> " · " + l.filter { it.mode == m }.sumOf { it.codes?.size ?: 0 } } ?: "") },
            { mode = it },
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            listOf(Modules.ENGINE, Modules.TRANSMISSION).forEach { m ->
                RlCard(Modifier.fillMaxWidth(), padding = PaddingValues(0.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(m.id, style = RlType.mono, color = Rl.text3)
                        Text(m.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = Rl.text)
                        DangerButton("Apagar do ${m.name.lowercase()}", { confirm = m }, enabled = canAct)
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Rl.line))
                    val read = list?.firstOrNull { it.module == m && it.mode == mode }
                    val codes = read?.codes
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        when {
                            list == null -> Text("Toque em Ler códigos com a ignição ligada.", style = MaterialTheme.typography.bodyMedium, color = Rl.text2)
                            read == null -> Text("Sem leitura deste tipo.", style = MaterialTheme.typography.bodyMedium, color = Rl.text2)
                            codes == null -> Text(
                                "Sem resposta do módulo · " + Discovery.describe(read.result),
                                style = MaterialTheme.typography.bodyMedium, color = Rl.text2,
                            )
                            codes.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Dot(Rl.success, 8.dp)
                                Text("Nenhum código ${modeLabel(mode).dropLast(1).lowercase()}", style = MaterialTheme.typography.bodyMedium, color = Rl.text2)
                            }
                            else -> codes.forEach { code ->
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text(code, style = RlType.metric, fontSize = 28.sp, color = Rl.danger, modifier = Modifier.width(120.dp))
                                    Spacer(Modifier.weight(1f))
                                    Badge(modeLabel(mode).dropLast(1), tone = Tone.DANGER)
                                }
                            }
                        }
                    }
                }
            }
            Text(
                "Cada pedido vai endereçado ao módulo (ATSH + ATCRA): um código só aparece no módulo que respondeu.",
                style = MaterialTheme.typography.bodySmall, color = Rl.text3,
            )
        }
    }
    confirm?.let { m ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = Rl.raised,
            title = { Text("Apagar os códigos do ${m.name.lowercase()}?", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Faça com o motor desligado e a ignição ligada. Isso também zera os monitores de prontidão " +
                            "e os dados de congelamento. Se a causa continuar, a luz volta. Leia e anote os códigos antes.",
                        style = MaterialTheme.typography.bodyMedium, color = Rl.text2,
                    )
                    Text(
                        "Envia só o modo 04 ao ${m.id}.", style = RlType.mono, color = Rl.text2,
                        modifier = Modifier.fillMaxWidth().background(Rl.sunken, RoundedCornerShape(Rl.radiusMd)).padding(12.dp),
                    )
                }
            },
            confirmButton = { DangerButton("Apagar do ${m.name.lowercase()}", { vm.clearDtcs(m); confirm = null }, filled = true) },
            dismissButton = { SecondaryButton("Cancelar", { confirm = null }) },
        )
    }
}

// ---------- Conexão ----------

@Composable
private fun ConnectScreen(vm: ObdViewModel) {
    val conn by vm.conn.collectAsState()
    val devices by vm.scanner.devices.collectAsState()
    val volts by vm.voltage.collectAsState()
    val mode by vm.sleepMode.collectAsState()
    val permissions = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) vm.scanner.start()
    }
    val title = when (val c = conn) {
        ConnState.Idle -> "Nenhum adaptador conectado"
        is ConnState.Connecting -> if (c.name.contains("aguardando")) "Aguardando o carro ligar" else "Conectando…"
        is ConnState.Connected -> "Adaptador conectado"
        is ConnState.Failed -> "Não conectou"
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenHeader("Conexão", title)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1.6f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                AdapterCard(conn, vm::disconnect)
                SleepCard(conn, mode, volts)
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Overline("Encontrados por perto")
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(devices, key = { it.address + it.ble }) { d ->
                        Row(
                            Modifier.fillMaxWidth().defaultMinSize(minHeight = 68.dp)
                                .background(Rl.raised, RoundedCornerShape(Rl.radiusMd))
                                .border(1.dp, if (d.looksLikeObd) Rl.lineStrong else Rl.line, RoundedCornerShape(Rl.radiusMd))
                                .clickable { vm.connect(d) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(d.name, style = MaterialTheme.typography.titleSmall, color = Rl.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (d.ble) "BLE" else "Clássico (SPP) · pareado", style = RlType.mono, fontSize = 13.sp, color = Rl.text3)
                            }
                            d.rssi?.let { Text("$it dBm", style = RlType.mono, fontSize = 13.sp, color = Rl.text2) }
                        }
                    }
                    if (devices.isEmpty()) {
                        item {
                            Text("Nenhum adaptador na lista. Toque em Procurar.", style = MaterialTheme.typography.bodyMedium, color = Rl.text3)
                        }
                    }
                }
                Text(
                    "O adaptador aceita um app por vez. Feche o Car Scanner ou o Torque antes de conectar.",
                    style = MaterialTheme.typography.bodySmall, color = Rl.text3,
                )
                PrimaryButton("Procurar adaptadores", { launcher.launch(permissions) }, Modifier.fillMaxWidth(), icon = RlIcons.radar)
                SecondaryButton("Modo demonstração", { vm.connect(null) }, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun AdapterCard(conn: ConnState, onDisconnect: () -> Unit) {
    val (tint, name, detail) = when (conn) {
        ConnState.Idle -> Triple(Rl.text3, "Desconectado", "Procure o adaptador na lista ao lado.")
        is ConnState.Connecting -> Triple(Rl.warning, conn.name, "Conectando…")
        is ConnState.Connected -> Triple(Rl.success, conn.name, conn.detail)
        is ConnState.Failed -> Triple(Rl.danger, "Falha na conexão", conn.message)
    }
    RlCard(
        Modifier.fillMaxWidth(),
        background = if (conn is ConnState.Failed) Rl.dangerSoft else Rl.raised,
        border = if (conn is ConnState.Failed) Rl.dangerLine else Rl.line,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(
                Modifier.size(64.dp).background(Rl.sunken, RoundedCornerShape(Rl.radiusLg)).border(1.dp, Rl.line, RoundedCornerShape(Rl.radiusLg)),
                contentAlignment = Alignment.Center,
            ) { Icon(RlIcons.bluetooth, null, tint = tint, modifier = Modifier.size(30.dp)) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, style = MaterialTheme.typography.titleLarge, color = Rl.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, style = RlType.mono, fontSize = 13.sp, color = Rl.text2)
            }
            if (conn is ConnState.Connected || conn is ConnState.Connecting) SecondaryButton("Desconectar", onDisconnect)
        }
    }
}

/** As quatro fases do modo de espera, com a fase atual em destaque, e a tensão lida pelo ATRV. */
@Composable
private fun SleepCard(conn: ConnState, mode: SleepPolicy.Mode?, volts: Double?) {
    val waiting = conn is ConnState.Connecting && conn.name.contains("aguardando")
    val current = when {
        conn is ConnState.Connected && mode == SleepPolicy.Mode.QUIET -> 1
        conn is ConnState.Connected && mode == SleepPolicy.Mode.ACTIVE -> 0
        waiting -> 3
        else -> -1
    }
    val steps = listOf(
        "Lendo" to "Motor ligado: consulta o carro normalmente.",
        "Pausa" to "Motor desligado: para de consultar o carro.",
        "Solta" to "Fecha a conexão para o adaptador dormir.",
        "Reconecta" to "Carro ligou de novo: volta sozinho.",
    )
    RlCard(Modifier.fillMaxWidth()) {
        Text("Modo de espera do adaptador", style = MaterialTheme.typography.titleMedium, color = Rl.text)
        Text("Tensão lida pelo ATRV, sem tráfego no carro.", style = MaterialTheme.typography.bodySmall, color = Rl.text3)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            steps.forEachIndexed { i, (name, text) ->
                val on = i == current
                Column(
                    Modifier.weight(1f).defaultMinSize(minHeight = 120.dp)
                        .background(if (on) Rl.accentSoft else Rl.sunken, RoundedCornerShape(12.dp))
                        .border(1.dp, if (on) Rl.accentLine else Rl.line, RoundedCornerShape(12.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Overline("0${i + 1}" + if (on) " · agora" else "", color = if (on) Rl.accent else Rl.text3)
                    Text(name, style = MaterialTheme.typography.titleMedium, color = Rl.text)
                    Text(text, style = MaterialTheme.typography.bodySmall, color = Rl.text2)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(volts?.let { Formato.numero(it, 1) } ?: "—", style = RlType.metric, fontSize = 44.sp, color = if (volts == null) Rl.text3 else Rl.text)
                if (volts != null) {
                    Spacer(Modifier.width(6.dp))
                    Text("V", style = RlType.metric, fontSize = 18.sp, color = Rl.text2, modifier = Modifier.padding(bottom = 6.dp))
                }
            }
            VoltageBar(volts, Modifier.weight(1f))
        }
    }
}

@Composable
private fun VoltageBar(volts: Double?, modifier: Modifier) {
    val min = 11.5
    val max = 14.8
    val limit = 13.0
    fun frac(v: Double) = ((v - min) / (max - min)).coerceIn(0.0, 1.0).toFloat()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(22.dp), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.fillMaxWidth().height(10.dp).background(Rl.hover, RoundedCornerShape(50)))
            volts?.let {
                Box(
                    Modifier.fillMaxWidth(frac(it)).height(10.dp)
                        .background(if (it >= limit) Rl.success else Rl.warning, RoundedCornerShape(50)),
                )
            }
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(frac(limit)))
                Box(Modifier.width(2.dp).height(22.dp).background(Rl.text))
                Spacer(Modifier.weight(1f - frac(limit)))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text("${Formato.numero(min, 1)} V", style = RlType.mono, fontSize = 12.sp, color = Rl.text3)
            Spacer(Modifier.weight(1f))
            Text("ligado ≥ ${Formato.numero(limit, 1)} V", style = RlType.mono, fontSize = 12.sp, color = Rl.text3)
            Spacer(Modifier.weight(1f))
            Text("${Formato.numero(max, 1)} V", style = RlType.mono, fontSize = 12.sp, color = Rl.text3)
        }
    }
}

// ---------- Descoberta ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiscoveryScreen(vm: ObdViewModel, shareText: (String) -> Unit, shareZip: () -> Unit) {
    val report by vm.report.collectAsState()
    val progress by vm.progress.collectAsState()
    val busy by vm.busy.collectAsState()
    val step by vm.step.collectAsState()
    val conn by vm.conn.collectAsState()
    val lids by vm.lidsFound.collectAsState()
    var sweep by remember { mutableStateOf(true) }
    val running = busy == "Descoberta"
    val title = when {
        running -> step ?: "Descobrindo…"
        report != null -> "Descoberta concluída"
        else -> "Mapear o que o carro expõe"
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenHeader("Descoberta · somente leitura", title) {
            if (running) {
                SecondaryButton("Parar", vm::stop, icon = RlIcons.stop)
            } else {
                PrimaryButton("Iniciar descoberta", { vm.runDiscovery(sweep) }, icon = RlIcons.radar, enabled = busy == null && conn is ConnState.Connected)
            }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                RlCard(Modifier.fillMaxWidth()) {
                    Text(
                        "Lê, sem alterar nada no carro: módulos presentes, PIDs padrão, chassi e calibração, códigos de " +
                            "falha e a varredura do serviço 21 (dados da Mitsubishi). Leva de 1 a 3 minutos com o motor ligado.",
                        style = MaterialTheme.typography.bodyMedium, color = Rl.text2,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth().defaultMinSize(minHeight = Rl.touch)
                            .clickable(enabled = !running, role = Role.Checkbox) { sweep = !sweep },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            sweep, null, enabled = !running,
                            colors = CheckboxDefaults.colors(checkedColor = Rl.orange, checkmarkColor = Rl.onAccent, uncheckedColor = Rl.lineStrong),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Varrer o serviço 21 (00 a FF) no motor e no câmbio", style = MaterialTheme.typography.bodyMedium, color = Rl.text)
                    }
                }
                if (running || progress > 0f) {
                    RlCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("${(progress * 100).toInt()}%", style = RlType.metric, fontSize = 40.sp, color = Rl.text)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                if (running) (step ?: "") else "última execução", style = RlType.mono, color = Rl.text2,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 6.dp),
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { progress }, modifier = Modifier.fillMaxWidth().height(10.dp),
                            color = Rl.orange, trackColor = Rl.hover, drawStopIndicator = {},
                        )
                    }
                }
                if (lids.isNotEmpty()) {
                    RlCard(Modifier.fillMaxWidth()) {
                        Text("Serviço 21: identificadores que responderam", style = MaterialTheme.typography.titleSmall, color = Rl.text)
                        lids.forEach { (m, list) ->
                            Spacer(Modifier.height(10.dp))
                            Overline("${m.name} · ${m.id}")
                            Spacer(Modifier.height(6.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                list.forEach { Badge("21 %02X".format(it), accent = true) }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("Compartilhar relatório", { report?.let(shareText) }, Modifier.weight(1f), icon = RlIcons.share, enabled = report != null)
                    SecondaryButton("Exportar sessões (.zip)", shareZip, Modifier.weight(1f))
                }
            }
            RlCard(Modifier.weight(1f).fillMaxHeight(), background = Rl.sunken) {
                Overline("Relatório")
                Spacer(Modifier.height(8.dp))
                val r = report
                if (r == null) {
                    Text("O relatório aparece aqui ao fim da descoberta.", style = MaterialTheme.typography.bodyMedium, color = Rl.text3)
                } else {
                    SelectionContainer(Modifier.weight(1f)) {
                        Text(
                            r, style = RlType.mono, fontSize = 12.sp, color = Rl.text2,
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        }
    }
}

// ---------- Explorar 21 ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LidScreen(vm: ObdViewModel) {
    val found by vm.lidsFound.collectAsState()
    val watch by vm.watch.collectAsState()
    val busy by vm.busy.collectAsState()
    val conn by vm.conn.collectAsState()
    var module by remember { mutableStateOf(Modules.ENGINE) }
    var lidText by remember { mutableStateOf("01") }
    val running = busy?.startsWith("Explorar 21") == true
    val w = watch
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenHeader(
            "Explorar 21" + (w?.let { " · ${it.samples} leituras · ${it.last}" } ?: ""),
            w?.let { "${it.module.name} · 21 %02X".format(it.lid) } ?: "Escolha um identificador",
        ) {
            if (running) SecondaryButton("Pausar", vm::stop, icon = RlIcons.stop)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillTabs(listOf(Modules.ENGINE, Modules.TRANSMISSION), module, { "${it.name} ${it.id}" }, { module = it })
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                lidText, { lidText = it.uppercase().filter { c -> c.isDigit() || c in 'A'..'F' }.take(2) },
                label = { Text("LID (hex)") }, singleLine = true, modifier = Modifier.width(130.dp),
                textStyle = RlType.mono.copy(fontSize = 18.sp, color = Rl.text),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            )
            PrimaryButton(
                "Observar", { lidText.toIntOrNull(16)?.let { vm.watchLid(module, it) } },
                icon = RlIcons.play, enabled = conn is ConnState.Connected && (busy == null || running),
            )
        }
        found[module]?.let { lids ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                lids.forEach { lid ->
                    val on = w != null && w.module == module && w.lid == lid
                    Box(
                        Modifier.defaultMinSize(minWidth = 64.dp, minHeight = 48.dp)
                            .background(if (on) Rl.accentSoft else Color.Transparent, RoundedCornerShape(Rl.radiusMd))
                            .border(1.dp, if (on) Rl.accentLine else Rl.line, RoundedCornerShape(Rl.radiusMd))
                            .clickable(enabled = conn is ConnState.Connected) { lidText = "%02X".format(lid); vm.watchLid(module, lid) }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("%02X".format(lid), style = RlType.mono, fontSize = 16.sp, color = if (on) Rl.accent else Rl.text2) }
                }
            }
        }
        if (w == null || w.bytes.isEmpty()) {
            EmptyState(
                "Descubra o que cada byte significa",
                "Lê um identificador do serviço 21 sem parar e destaca os bytes que mudam. Acelere, ligue o ar ou " +
                    "deixe o motor esquentar e veja quais bytes acompanham. Rode a Descoberta antes para saber quais respondem.",
            )
        } else {
            Text(
                "${w.bytes.size} bytes · destaque nos que mudam · hex, decimal, mín–máx e mudanças",
                style = MaterialTheme.typography.bodyMedium, color = Rl.text2,
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(118.dp),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(w.bytes.indices.toList()) { i ->
                    val changes = w.changeCount.getOrElse(i) { 0 }
                    val hot = w.samples > 0 && changes * 5 >= w.samples
                    Column(
                        Modifier
                            .background(if (changes > 0) Rl.hover else Rl.bg, RoundedCornerShape(Rl.radiusMd))
                            .border(1.dp, if (hot) Rl.accentLine else Rl.line, RoundedCornerShape(Rl.radiusMd))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Row {
                            Text("byte $i", style = RlType.mono, fontSize = 12.sp, color = Rl.text3, modifier = Modifier.weight(1f))
                            Text("Δ$changes", style = RlType.mono, fontSize = 12.sp, color = if (hot) Rl.accent else Rl.text3)
                        }
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("%02X".format(w.bytes[i]), style = RlType.metric, fontSize = 22.sp, color = if (changes > 0) Rl.text else Rl.text3)
                            Spacer(Modifier.width(6.dp))
                            Text(w.bytes[i].toString(), style = RlType.mono, color = Rl.text2, modifier = Modifier.padding(bottom = 2.dp))
                        }
                        Text("${w.min[i]}–${w.max[i]}", style = RlType.mono, fontSize = 12.sp, color = Rl.text2)
                    }
                }
            }
        }
    }
}

// ---------- Registro ----------

@Composable
private fun LogScreen(vm: ObdViewModel) {
    val log by vm.log.collectAsState()
    val state = rememberLazyListState()
    LaunchedEffect(log.size) { if (log.isNotEmpty()) state.scrollToItem(log.size - 1) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenHeader("Registro · ${log.size} linhas", "O que o app está fazendo")
        RlCard(Modifier.fillMaxWidth().weight(1f), background = Rl.sunken, padding = PaddingValues(16.dp)) {
            if (log.isEmpty()) {
                Text("Nada registrado ainda.", style = MaterialTheme.typography.bodyMedium, color = Rl.text3)
            } else {
                SelectionContainer {
                    LazyColumn(state = state) {
                        items(log) { Text(it, style = RlType.mono, fontSize = 12.sp, color = Rl.text2) }
                    }
                }
            }
        }
    }
}
