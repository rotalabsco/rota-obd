package io.github.obdlivre.ui

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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import io.github.obdlivre.core.Module
import io.github.obdlivre.core.Modules
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val vm: ObdViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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

private enum class Tab(val label: String) {
    CONNECT("Conexão"), GARAGE("Garagem"), DISCOVERY("Descoberta"), LIVE("Ao vivo"), DTC("Falhas"), LID("Explorar 21"), LOG("Registro")
}

@Composable
private fun App(vm: ObdViewModel, shareText: (String) -> Unit, shareZip: () -> Unit) {
    var tab by remember { mutableStateOf(Tab.CONNECT) }
    val busy by vm.busy.collectAsState()
    RefuelDialog(vm)
    Row(Modifier.fillMaxSize()) {
        NavigationRail(Modifier.verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(8.dp))
            Tab.entries.forEach { t ->
                NavigationRailItem(
                    selected = tab == t,
                    onClick = { tab = t },
                    icon = { Text(t.label.take(1), fontWeight = FontWeight.Bold) },
                    label = { Text(t.label) },
                )
            }
        }
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            busy?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$it em andamento", Modifier.weight(1f))
                    OutlinedButton(onClick = vm::stop) { Text("Parar") }
                }
                Spacer(Modifier.height(8.dp))
            }
            when (tab) {
                Tab.CONNECT -> ConnectScreen(vm)
                Tab.GARAGE -> GarageScreen(vm)
                Tab.DISCOVERY -> DiscoveryScreen(vm, shareText, shareZip)
                Tab.LIVE -> LiveScreen(vm)
                Tab.DTC -> DtcScreen(vm)
                Tab.LID -> LidScreen(vm)
                Tab.LOG -> LogScreen(vm)
            }
        }
    }
}

@Composable
private fun RefuelDialog(vm: ObdViewModel) {
    val prompt by vm.refuel.collectAsState()
    val garage by vm.garage.collectAsState()
    prompt?.let { p ->
        FuelDialog(
            initial = null,
            suggestedOdometer = io.github.obdlivre.core.Garage.currentOdometer(garage),
            suggestedLiters = p.suggestedLiters,
            onDismiss = vm::dismissRefuel,
            onSave = { vm.saveFuel(it); vm.dismissRefuel() },
            onDelete = null,
            title = "Abastecimento detectado: %.0f%% → %.0f%%".format(Locale.ROOT, p.fromPct, p.toPct),
        )
    }
}

@Composable
private fun ConnectScreen(vm: ObdViewModel) {
    val conn by vm.conn.collectAsState()
    val devices by vm.scanner.devices.collectAsState()
    val permissions = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) vm.scanner.start()
    }

    Column {
        Text(
            when (val c = conn) {
                is ConnState.Idle -> "Desconectado"
                is ConnState.Connecting -> "Conectando a ${c.name}..."
                is ConnState.Connected -> "Conectado: ${c.name}\n${c.detail}"
                is ConnState.Failed -> "Falha: ${c.message}"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { launcher.launch(permissions) }) { Text("Procurar adaptadores") }
            OutlinedButton(onClick = { vm.connect(null) }) { Text("Modo demonstração") }
            if (conn is ConnState.Connected) OutlinedButton(onClick = vm::disconnect) { Text("Desconectar") }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Desconecte o adaptador de outros apps (Car Scanner, Torque) antes: ele só aceita uma conexão.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn {
            items(devices, key = { it.address + it.ble }) { d ->
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { vm.connect(d) },
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.name, fontWeight = if (d.looksLikeObd) FontWeight.Bold else FontWeight.Normal)
                            Text(
                                "${d.address} · ${if (d.ble) "BLE" else "Clássico (pareado)"}" +
                                    (d.rssi?.let { " · $it dBm" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text("Conectar")
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoveryScreen(vm: ObdViewModel, shareText: (String) -> Unit, shareZip: () -> Unit) {
    val report by vm.report.collectAsState()
    val progress by vm.progress.collectAsState()
    val busy by vm.busy.collectAsState()
    var sweep by remember { mutableStateOf(true) }
    Column {
        Text(
            "Lê, sem alterar nada no carro: módulos presentes, PIDs padrão, VIN e calibração, códigos de falha " +
                "e a varredura do serviço 21 (dados da Mitsubishi). Leva de 1 a 3 minutos com a ignição ligada.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = sweep, onCheckedChange = { sweep = it })
            Text("Varrer serviço 21 (00 a FF) no motor e no câmbio")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.runDiscovery(sweep) }, enabled = busy == null) { Text("Iniciar descoberta") }
            report?.let { r -> OutlinedButton(onClick = { shareText(r) }) { Text("Compartilhar relatório") } }
            OutlinedButton(onClick = shareZip) { Text("Exportar sessões (.zip)") }
        }
        if (busy == "Descoberta") {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(12.dp))
        report?.let {
            SelectionContainer {
                Text(
                    it, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

@Composable
private fun LiveScreen(vm: ObdViewModel) {
    val values by vm.live.collectAsState()
    val busy by vm.busy.collectAsState()
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::startLive, enabled = busy == null) { Text("Começar leitura") }
        }
        Spacer(Modifier.height(12.dp))
        LazyVerticalGrid(columns = GridCells.Adaptive(200.dp)) {
            items(values, key = { it.pid }) { v ->
                Card(Modifier.padding(6.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(v.name, style = MaterialTheme.typography.bodySmall)
                        Text(
                            v.value?.let { formatValue(it) } ?: "—",
                            fontSize = 34.sp, fontWeight = FontWeight.Bold,
                        )
                        Text(v.unit, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private fun formatValue(v: Double): String =
    if (kotlin.math.abs(v) >= 100) "%.0f".format(Locale.ROOT, v) else "%.1f".format(Locale.ROOT, v)

@Composable
private fun DtcScreen(vm: ObdViewModel) {
    val dtcs by vm.dtcs.collectAsState()
    val busy by vm.busy.collectAsState()
    var confirm by remember { mutableStateOf<Module?>(null) }
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::readDtcs, enabled = busy == null) { Text("Ler códigos de falha") }
            OutlinedButton(onClick = { confirm = Modules.ENGINE }, enabled = busy == null) { Text("Apagar do motor") }
            OutlinedButton(onClick = { confirm = Modules.TRANSMISSION }, enabled = busy == null) { Text("Apagar do câmbio") }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn {
            items(dtcs.orEmpty()) { d ->
                val label = mapOf("03" to "Armazenados", "07" to "Pendentes", "0A" to "Permanentes")[d.mode]
                Text(
                    "${d.module.name} · $label: " + when {
                        d.codes == null -> "sem resposta"
                        d.codes.isEmpty() -> "nenhum"
                        else -> d.codes.joinToString(", ")
                    },
                    Modifier.padding(vertical = 4.dp),
                )
            }
        }
    }
    confirm?.let { m ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Apagar códigos do ${m.name.lowercase()}?") },
            text = {
                Text(
                    "Faça com o motor desligado e a ignição ligada. Isso também zera os monitores de prontidão " +
                        "e os dados de congelamento. Se a causa continuar, a luz volta. Leia e anote os códigos antes.",
                )
            },
            confirmButton = { TextButton(onClick = { vm.clearDtcs(m); confirm = null }) { Text("Apagar") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun LidScreen(vm: ObdViewModel) {
    val found by vm.lidsFound.collectAsState()
    val watch by vm.watch.collectAsState()
    var module by remember { mutableStateOf(Modules.ENGINE) }
    var lidText by remember { mutableStateOf("01") }
    Column {
        Text(
            "Lê um identificador do serviço 21 sem parar e mostra quais bytes mudam. Mexa no acelerador, " +
                "ligue o ar ou deixe esquentar e veja quais bytes acompanham: é assim que se descobre o volume " +
                "injetado, a temperatura do câmbio e outros dados do fabricante.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Modules.ENGINE, Modules.TRANSMISSION).forEach { m ->
                if (module == m) Button(onClick = {}) { Text(m.name) } else OutlinedButton(onClick = { module = m }) { Text(m.name) }
            }
            OutlinedTextField(lidText, { lidText = it.uppercase().take(2) }, label = { Text("LID (hex)") }, modifier = Modifier.width(120.dp))
            Button(onClick = { lidText.toIntOrNull(16)?.let { vm.watchLid(module, it) } }) { Text("Observar") }
        }
        found[module]?.let { lids ->
            Text("Responderam na descoberta: " + lids.joinToString(" ") { "%02X".format(it) }, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(12.dp))
        watch?.let { w ->
            Text("${w.module.name} · 21%02X · %d leituras · %s".format(w.lid, w.samples, w.last))
            Spacer(Modifier.height(8.dp))
            LazyVerticalGrid(columns = GridCells.Adaptive(92.dp)) {
                items(w.bytes.indices.toList()) { i ->
                    val changed = w.changeCount.getOrElse(i) { 0 } > 0
                    Column(
                        Modifier.padding(3.dp)
                            .background(if (changed) Color(0xFF2E5E3A) else Color(0xFF2A2A2A), RoundedCornerShape(6.dp))
                            .padding(6.dp),
                    ) {
                        Text("byte $i", fontSize = 10.sp)
                        Text("%02X  %d".format(w.bytes[i], w.bytes[i]), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        Text("%d–%d · Δ%d".format(w.min[i], w.max[i], w.changeCount[i]), fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogScreen(vm: ObdViewModel) {
    val log by vm.log.collectAsState()
    val state = rememberLazyListState()
    LaunchedEffect(log.size) { if (log.isNotEmpty()) state.scrollToItem(log.size - 1) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = state) {
            items(log) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
        }
    }
}

@Suppress("unused")
@Composable
private fun Dot(color: Color) = Box(Modifier.size(8.dp).background(color, RoundedCornerShape(4.dp)))
