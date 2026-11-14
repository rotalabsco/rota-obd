package co.rotalabs.obd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import co.rotalabs.obd.ui.theme.Rl
import co.rotalabs.obd.ui.theme.RlIcons
import co.rotalabs.obd.ui.theme.RlType
import co.rotalabs.obd.core.DueState
import co.rotalabs.obd.core.FuelEntry
import co.rotalabs.obd.core.Garage
import co.rotalabs.obd.core.GarageData
import co.rotalabs.obd.core.MaintenanceItem
import co.rotalabs.obd.core.MaintenanceRecord
import co.rotalabs.obd.core.MaintenanceStatus
import co.rotalabs.obd.core.Vehicle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ptBR = Locale("pt", "BR")
private fun dateText(millis: Long) = SimpleDateFormat("dd/MM/yyyy", ptBR).format(Date(millis))
private fun shortDate(millis: Long) = SimpleDateFormat("dd/MM", ptBR).format(Date(millis))
private fun parseDate(s: String): Long? = runCatching {
    SimpleDateFormat("dd/MM/yyyy", ptBR).apply { isLenient = false }.parse(s.trim())?.time
}.getOrNull()

private fun parseNumber(s: String): Double? = Garage.parseNumber(s)

private fun num(v: Double?, decimals: Int = 1): String =
    v?.let { String.format(ptBR, "%,.${decimals}f", it) } ?: "—"

private fun money(v: Double?): String = v?.let { String.format(ptBR, "R$ %,.2f", it) } ?: "—"

private enum class GarageTab(val label: String) { FUEL("Consumo"), MAINT("Manutenção"), DEADLINES("Vencimentos"), CAR("Carro") }

@Composable
fun GarageScreen(vm: ObdViewModel) {
    val data by vm.garage.collectAsState()
    var tab by remember { mutableStateOf(GarageTab.FUEL) }
    var creating by remember { mutableStateOf(false) }
    val v = data.vehicle
    val odo = Garage.currentOdometer(data)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenHeader(listOfNotNull("Garagem", v.plate.ifBlank { null }, v.fuel.ifBlank { null }).joinToString(" · "), v.name) {
            odo?.let {
                Column(horizontalAlignment = Alignment.End) {
                    Text("${num(it, 0)} km", style = RlType.metric, fontSize = 24.sp, color = Rl.text)
                    Text("hodômetro estimado", style = MaterialTheme.typography.bodySmall, color = Rl.text3)
                }
            }
            PrimaryButton("Abastecimento", { creating = true }, icon = RlIcons.fuel)
        }
        PillTabs(GarageTab.entries, tab, { it.label }, { tab = it })
        Box(Modifier.weight(1f)) {
            when (tab) {
                GarageTab.CAR -> VehicleForm(data.vehicle, vm::updateVehicle)
                GarageTab.FUEL -> FuelSection(vm, data)
                GarageTab.MAINT -> MaintenanceSection(vm, data)
                GarageTab.DEADLINES -> DeadlinesSection(vm)
            }
        }
    }
    if (creating) {
        FuelDialog(
            initial = null, suggestedOdometer = odo, suggestedLiters = null,
            onDismiss = { creating = false }, onSave = { vm.saveFuel(it); creating = false }, onDelete = null,
        )
    }
}

@Composable
private fun VehicleForm(v: Vehicle, onSave: (Vehicle) -> Unit) {
    var name by remember(v) { mutableStateOf(v.name) }
    var plate by remember(v) { mutableStateOf(v.plate) }
    var fuel by remember(v) { mutableStateOf(v.fuel) }
    var tank by remember(v) { mutableStateOf(v.tankLiters?.let { num(it, 0) } ?: "") }
    var odo by remember(v) { mutableStateOf(v.initialOdometerKm?.let { num(it, 0) } ?: "") }
    var vin by remember(v) { mutableStateOf(v.vin) }
    var cal by remember(v) { mutableStateOf(v.calibration) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Nome") }, modifier = Modifier.weight(1f))
            OutlinedTextField(plate, { plate = it.uppercase() }, label = { Text("Placa") }, modifier = Modifier.width(160.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(fuel, { fuel = it }, label = { Text("Combustível") }, modifier = Modifier.weight(1f))
            NumberField(tank, { tank = it }, "Tanque (L)", Modifier.weight(1f))
            NumberField(odo, { odo = it }, "Hodômetro inicial (km)", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(vin, { vin = it.uppercase() }, label = { Text("Chassi (VIN)") }, modifier = Modifier.weight(1f))
            OutlinedTextField(cal, { cal = it }, label = { Text("Calibração da ECU") }, modifier = Modifier.weight(1f))
        }
        Text(
            "Chassi e calibração são lidos do carro automaticamente ao conectar.",
            style = MaterialTheme.typography.bodySmall,
        )
        PrimaryButton("Salvar", {
            onSave(
                v.copy(
                    name = name.ifBlank { "Meu carro" }, plate = plate.trim(), fuel = fuel.trim(),
                    tankLiters = parseNumber(tank), initialOdometerKm = parseNumber(odo),
                    vin = vin.trim(), calibration = cal.trim(),
                ),
            )
        })
    }
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, modifier = modifier, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    RlCard(modifier, padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Rl.text2)
        Spacer(Modifier.height(6.dp))
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Encolhe para caber na largura (Geist Mono: ~0,62 em por caractere).
            val size = minOf(30f, maxWidth.value / (value.length * 0.62f + 0.4f)).sp
            Text(value, style = RlType.metric, fontSize = size, color = Rl.text, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun FuelSection(vm: ObdViewModel, data: GarageData) {
    val summary = remember(data.fuel) { Garage.fuelSummary(data.fuel) }
    var editing by remember { mutableStateOf<FuelEntry?>(null) }
    val entries = data.fuel.sortedByDescending { it.dateMillis }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat("Média", summary.averageKmPerLiter?.let { "${num(it)} km/L" } ?: "—", Modifier.weight(1f))
                Stat("Último tanque", summary.lastKmPerLiter?.let { "${num(it)} km/L" } ?: "—", Modifier.weight(1f))
                Stat("Custo por km", money(summary.averageCostPerKm), Modifier.weight(1f))
                Stat("Total gasto", money(summary.totalSpent), Modifier.weight(1f))
            }
        }
        if (summary.segments.isNotEmpty()) item { ConsumptionChart(summary.segments.takeLast(8), summary.averageKmPerLiter) }
        if (entries.isEmpty()) {
            item {
                EmptyState(
                    "Nenhum abastecimento",
                    "O consumo é medido de tanque cheio a tanque cheio. Registre ao abastecer, ou confirme quando o app " +
                        "perceber que o tanque subiu.",
                )
            }
        }
        items(entries, key = { it.id }) { e ->
            Row(
                Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp)
                    .background(Rl.raised, RoundedCornerShape(Rl.radiusMd))
                    .border(1.dp, Rl.line, RoundedCornerShape(Rl.radiusMd))
                    .clickable { editing = e }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(shortDate(e.dateMillis), style = RlType.mono, color = Rl.text3)
                Column(Modifier.weight(1f)) {
                    Text(
                        e.station.ifBlank { "Abastecimento" } + if (e.fullTank) " · tanque cheio" else " · parcial",
                        style = MaterialTheme.typography.bodyLarge, color = Rl.text, maxLines = 1,
                    )
                    Text("${num(e.odometerKm, 0)} km" + (e.pricePerLiter?.let { " · ${money(it)}/L" } ?: ""), style = RlType.mono, fontSize = 13.sp, color = Rl.text3)
                }
                Text("${num(e.liters, 2)} L", style = RlType.mono, fontSize = 16.sp, color = Rl.text2)
                Text(money(e.totalPrice), style = RlType.mono, fontSize = 16.sp, color = Rl.text)
            }
        }
    }
    editing?.let { e ->
        FuelDialog(
            initial = e, suggestedOdometer = null, suggestedLiters = null,
            onDismiss = { editing = null },
            onSave = { vm.saveFuel(it); editing = null },
            onDelete = { vm.deleteFuel(e.id); editing = null },
        )
    }
}

/** Barras de km/L por tanque; a última em laranja, linha tracejada na média. */
@Composable
private fun ConsumptionChart(segments: List<co.rotalabs.obd.core.ConsumptionSegment>, average: Double?) {
    val values = segments.map { it.kmPerLiter }
    val top = (values.maxOrNull() ?: 1.0) * 1.08
    val floor = (values.minOrNull() ?: 0.0) * 0.8
    val chartH = 150.dp
    fun h(v: Double) = (((v - floor) / (top - floor)).coerceIn(0.05, 1.0) * chartH.value).dp
    RlCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Consumo tanque a tanque", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = Rl.text)
            average?.let { Text("média ${num(it)} km/L", style = RlType.mono, fontSize = 13.sp, color = Rl.text3) }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().height(chartH + 24.dp).border(width = 0.dp, color = Color.Transparent),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            segments.forEachIndexed { i, seg ->
                val last = i == segments.lastIndex
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(num(seg.kmPerLiter), style = RlType.mono, fontSize = 13.sp, color = if (last) Rl.accent else Rl.text2)
                    Box(
                        Modifier.fillMaxWidth().height(h(seg.kmPerLiter))
                            .background(if (last) Rl.orange else Rl.lineStrong, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)),
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Rl.lineStrong))
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            segments.forEach { seg ->
                Text(shortDate(seg.toMillis), Modifier.weight(1f), style = RlType.mono, fontSize = 12.sp, color = Rl.text3, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
fun FuelDialog(
    initial: FuelEntry?,
    suggestedOdometer: Double?,
    suggestedLiters: Double?,
    onDismiss: () -> Unit,
    onSave: (FuelEntry) -> Unit,
    onDelete: (() -> Unit)?,
    title: String = if (initial == null) "Novo abastecimento" else "Editar abastecimento",
) {
    var date by remember { mutableStateOf(dateText(initial?.dateMillis ?: System.currentTimeMillis())) }
    var odo by remember { mutableStateOf((initial?.odometerKm ?: suggestedOdometer)?.let { num(it, 0) } ?: "") }
    var liters by remember { mutableStateOf((initial?.liters ?: suggestedLiters)?.let { num(it, 2) } ?: "") }
    var total by remember { mutableStateOf(initial?.totalPrice?.let { num(it, 2) } ?: "") }
    var full by remember { mutableStateOf(initial?.fullTank ?: true) }
    var station by remember { mutableStateOf(initial?.station ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(date, { date = it }, label = { Text("Data") }, modifier = Modifier.weight(1f), singleLine = true)
                    NumberField(odo, { odo = it }, "Hodômetro (km)", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(liters, { liters = it }, "Litros", Modifier.weight(1f))
                    NumberField(total, { total = it }, "Valor total (R$)", Modifier.weight(1f))
                }
                OutlinedTextField(station, { station = it }, label = { Text("Posto (opcional)") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = full, onCheckedChange = { full = it })
                    Text("Completei o tanque")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val d = parseDate(date)
                val o = parseNumber(odo)
                val l = parseNumber(liters)
                error = when {
                    d == null -> "Data no formato dd/mm/aaaa"
                    o == null -> "Informe o hodômetro"
                    l == null || l <= 0 -> "Informe os litros"
                    else -> null
                }
                if (error == null) {
                    onSave(
                        FuelEntry(
                            id = initial?.id ?: co.rotalabs.obd.core.newId(), dateMillis = d!!, odometerKm = o!!,
                            liters = l!!, totalPrice = parseNumber(total), fullTank = full, station = station.trim(),
                        ),
                    )
                }
            }) { Text("Salvar") }
        },
        dismissButton = {
            Row {
                onDelete?.let { TextButton(onClick = it) { Text("Excluir") } }
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        },
    )
}

@Composable
private fun MaintenanceSection(vm: ObdViewModel, data: GarageData) {
    val statuses = remember(data) { Garage.maintenanceStatus(data) }
    var editItem by remember { mutableStateOf<MaintenanceItem?>(null) }
    var newItem by remember { mutableStateOf(false) }
    var doneFor by remember { mutableStateOf<MaintenanceItem?>(null) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton("Novo item", { newItem = true }, icon = RlIcons.plus)
            Spacer(Modifier.width(12.dp))
            Text(
                "Defina os intervalos conforme o manual do seu carro.",
                style = MaterialTheme.typography.bodySmall, color = Rl.text3,
            )
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(statuses.sortedBy { it.state.ordinal.let { o -> listOf(2, 1, 0, 3, 4)[o] } }, key = { it.item.id }) { s ->
                MaintenanceRow(s, onEdit = { editItem = s.item }, onDone = { doneFor = s.item })
            }
            val history = data.records.sortedByDescending { it.dateMillis }
            if (history.isNotEmpty()) {
                item { Overline("Histórico", modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)) }
                items(history, key = { "r" + it.id }) { r ->
                    val name = data.items.firstOrNull { it.id == r.itemId }?.name ?: "Outro"
                    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${dateText(r.dateMillis)} · $name · ${num(r.odometerKm, 0)} km · ${money(r.cost)}" +
                                (if (r.description.isNotBlank()) " · ${r.description}" else ""),
                            Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Rl.text2,
                        )
                        GhostButton("Excluir", { vm.deleteRecord(r.id) })
                    }
                }
            }
        }
    }
    if (newItem) ItemDialog(null, { newItem = false }, { vm.saveItem(it); newItem = false }, null)
    editItem?.let { it0 ->
        ItemDialog(it0, { editItem = null }, { vm.saveItem(it); editItem = null }, { vm.deleteItem(it0.id); editItem = null })
    }
    doneFor?.let { item ->
        RecordDialog(item, Garage.currentOdometer(data), { doneFor = null }, { vm.addRecord(it); doneFor = null })
    }
}

@Composable
private fun MaintenanceRow(s: MaintenanceStatus, onEdit: () -> Unit, onDone: () -> Unit) {
    val (tone, text) = when (s.state) {
        DueState.OVERDUE -> Tone.DANGER to "Vencida"
        DueState.SOON -> Tone.WARNING to "Em breve"
        DueState.OK -> Tone.NORMAL to "Em dia"
        DueState.NEVER_DONE -> Tone.MISSING to "Sem registro"
        DueState.NO_INTERVAL -> Tone.MISSING to "Sem intervalo"
    }
    val detail = buildList {
        s.remainingKm?.let { add(if (it >= 0) "faltam ${num(it, 0)} km" else "passou ${num(-it, 0)} km") }
        s.remainingDays?.let { add(if (it >= 0) "faltam $it dias" else "passou ${-it} dias") }
        if (s.item.intervalKm != null || s.item.intervalMonths != null) {
            add(
                "a cada " + listOfNotNull(
                    s.item.intervalKm?.let { "${num(it, 0)} km" },
                    s.item.intervalMonths?.let { "$it meses" },
                ).joinToString(" ou "),
            )
        }
        s.lastRecord?.let { add("última: ${dateText(it.dateMillis)}") }
    }.joinToString(" · ")
    StatusRow(s.item.name, text, tone, detail) {
        GhostButton("Editar", onEdit)
        SecondaryButton("Feito", onDone, icon = RlIcons.check)
    }
}

/** Linha de item com prazo (manutenção, vencimento): cor de estado com rótulo, nunca só a cor. */
@Composable
fun StatusRow(name: String, state: String, tone: Tone, detail: String, actions: @Composable RowScope.() -> Unit) {
    val (bg, border) = when (tone) {
        Tone.DANGER -> Rl.dangerSoft to Rl.dangerLine
        Tone.WARNING -> Rl.warningSoft to Rl.warningLine
        else -> Rl.raised to Rl.line
    }
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 72.dp)
            .background(bg, RoundedCornerShape(Rl.radiusMd))
            .border(1.dp, border, RoundedCornerShape(Rl.radiusMd))
            .padding(start = 18.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium, color = Rl.text)
                Badge(state, tone = tone)
            }
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = Rl.text2)
        }
        actions()
    }
}

@Composable
private fun ItemDialog(initial: MaintenanceItem?, onDismiss: () -> Unit, onSave: (MaintenanceItem) -> Unit, onDelete: (() -> Unit)?) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var km by remember { mutableStateOf(initial?.intervalKm?.let { num(it, 0) } ?: "") }
    var months by remember { mutableStateOf(initial?.intervalMonths?.toString() ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Novo item" else "Editar item") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(km, { km = it }, "A cada (km)", Modifier.weight(1f))
                    NumberField(months, { months = it }, "Ou a cada (meses)", Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) {
                    onSave(
                        (initial ?: MaintenanceItem(name = name)).copy(
                            name = name.trim(), intervalKm = parseNumber(km), intervalMonths = parseNumber(months)?.toInt(),
                        ),
                    )
                }
            }) { Text("Salvar") }
        },
        dismissButton = {
            Row {
                onDelete?.let { TextButton(onClick = it) { Text("Excluir") } }
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        },
    )
}

@Composable
private fun RecordDialog(item: MaintenanceItem, odometer: Double?, onDismiss: () -> Unit, onSave: (MaintenanceRecord) -> Unit) {
    var date by remember { mutableStateOf(dateText(System.currentTimeMillis())) }
    var odo by remember { mutableStateOf(odometer?.let { num(it, 0) } ?: "") }
    var cost by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Registrar: ${item.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(date, { date = it }, label = { Text("Data") }, modifier = Modifier.weight(1f), singleLine = true)
                    NumberField(odo, { odo = it }, "Hodômetro (km)", Modifier.weight(1f))
                }
                NumberField(cost, { cost = it }, "Custo (R$, opcional)")
                OutlinedTextField(desc, { desc = it }, label = { Text("Observação (oficina, peça, marca)") })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val d = parseDate(date)
                if (d == null) {
                    error = "Data no formato dd/mm/aaaa"
                } else {
                    onSave(MaintenanceRecord(itemId = item.id, dateMillis = d, odometerKm = parseNumber(odo), cost = parseNumber(cost), description = desc.trim()))
                }
            }) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
