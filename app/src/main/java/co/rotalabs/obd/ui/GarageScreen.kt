package co.rotalabs.obd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
private fun parseDate(s: String): Long? = runCatching {
    SimpleDateFormat("dd/MM/yyyy", ptBR).apply { isLenient = false }.parse(s.trim())?.time
}.getOrNull()

private fun parseNumber(s: String): Double? = Garage.parseNumber(s)

private fun num(v: Double?, decimals: Int = 1): String =
    v?.let { String.format(ptBR, "%,.${decimals}f", it) } ?: "—"

private fun money(v: Double?): String = v?.let { String.format(ptBR, "R$ %,.2f", it) } ?: "—"

private enum class GarageTab(val label: String) { CAR("Carro"), FUEL("Abastecimentos"), MAINT("Manutenção"), DEADLINES("Vencimentos") }

@Composable
fun GarageScreen(vm: ObdViewModel) {
    val data by vm.garage.collectAsState()
    var tab by remember { mutableStateOf(GarageTab.FUEL) }
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GarageTab.entries.forEach { t ->
                if (t == tab) Button(onClick = {}) { Text(t.label) } else OutlinedButton(onClick = { tab = t }) { Text(t.label) }
            }
        }
        Spacer(Modifier.height(12.dp))
        when (tab) {
            GarageTab.CAR -> VehicleForm(data.vehicle, vm::updateVehicle)
            GarageTab.FUEL -> FuelSection(vm, data)
            GarageTab.MAINT -> MaintenanceSection(vm, data)
            GarageTab.DEADLINES -> DeadlinesSection(vm)
        }
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
        Button(onClick = {
            onSave(
                v.copy(
                    name = name.ifBlank { "Meu carro" }, plate = plate.trim(), fuel = fuel.trim(),
                    tankLiters = parseNumber(tank), initialOdometerKm = parseNumber(odo),
                    vin = vin.trim(), calibration = cal.trim(),
                ),
            )
        }) { Text("Salvar") }
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
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun FuelSection(vm: ObdViewModel, data: GarageData) {
    val summary = remember(data.fuel) { Garage.fuelSummary(data.fuel) }
    var editing by remember { mutableStateOf<FuelEntry?>(null) }
    var creating by remember { mutableStateOf(false) }
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("Média", "${num(summary.averageKmPerLiter)} km/L", Modifier.weight(1f))
            Stat("Último tanque", "${num(summary.lastKmPerLiter)} km/L", Modifier.weight(1f))
            Stat("Custo por km", money(summary.averageCostPerKm), Modifier.weight(1f))
            Stat("Total gasto", money(summary.totalSpent), Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { creating = true }) { Text("Novo abastecimento") }
            Spacer(Modifier.width(12.dp))
            Text(
                "O consumo é medido de tanque cheio a tanque cheio.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn {
            items(data.fuel.sortedByDescending { it.dateMillis }, key = { it.id }) { e ->
                Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${dateText(e.dateMillis)} · ${num(e.odometerKm, 0)} km · ${num(e.liters, 2)} L" +
                                    if (e.fullTank) " · cheio" else " · parcial",
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "${money(e.totalPrice)} · ${money(e.pricePerLiter)}/L" +
                                    (if (e.station.isNotBlank()) " · ${e.station}" else ""),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = { editing = e }) { Text("Editar") }
                    }
                }
            }
        }
    }
    if (creating) {
        FuelDialog(
            initial = null,
            suggestedOdometer = Garage.currentOdometer(data),
            suggestedLiters = null,
            onDismiss = { creating = false },
            onSave = { vm.saveFuel(it); creating = false },
            onDelete = null,
        )
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
            Button(onClick = { newItem = true }) { Text("Novo item") }
            Spacer(Modifier.width(12.dp))
            Text(
                "Defina os intervalos conforme o manual da sua Pajero.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn {
            items(statuses.sortedBy { it.state.ordinal.let { o -> listOf(2, 1, 0, 3, 4)[o] } }, key = { it.item.id }) { s ->
                MaintenanceRow(s, onEdit = { editItem = s.item }, onDone = { doneFor = s.item })
            }
            val history = data.records.sortedByDescending { it.dateMillis }
            if (history.isNotEmpty()) {
                item { Text("Histórico", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
                items(history, key = { "r" + it.id }) { r ->
                    val name = data.items.firstOrNull { it.id == r.itemId }?.name ?: "Outro"
                    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${dateText(r.dateMillis)} · $name · ${num(r.odometerKm, 0)} km · ${money(r.cost)}" +
                                (if (r.description.isNotBlank()) " · ${r.description}" else ""),
                            Modifier.weight(1f),
                        )
                        TextButton(onClick = { vm.deleteRecord(r.id) }) { Text("Excluir") }
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
    val (color, text) = when (s.state) {
        DueState.OVERDUE -> Color(0xFFE5484D) to "Vencida"
        DueState.SOON -> Color(0xFFF5A524) to "Em breve"
        DueState.OK -> Color(0xFF46A758) to "Em dia"
        DueState.NEVER_DONE -> Color(0xFF8E8E93) to "Sem registro"
        DueState.NO_INTERVAL -> Color(0xFF8E8E93) to "Sem intervalo"
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
    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(color, RoundedCornerShape(6.dp)))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("${s.item.name} · $text", fontWeight = FontWeight.Bold)
                if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onEdit) { Text("Editar") }
            TextButton(onClick = onDone) { Text("Feito") }
        }
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
