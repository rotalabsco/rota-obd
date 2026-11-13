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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import co.rotalabs.obd.core.Deadline
import co.rotalabs.obd.core.DeadlineStatus
import co.rotalabs.obd.core.Deadlines
import co.rotalabs.obd.core.DueState
import co.rotalabs.obd.core.Garage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val localePt = Locale("pt", "BR")
private fun fmtDate(millis: Long) = SimpleDateFormat("dd/MM/yyyy", localePt).format(Date(millis))
private fun readDate(s: String): Long? = runCatching {
    SimpleDateFormat("dd/MM/yyyy", localePt).apply { isLenient = false }.parse(s.trim())?.time
}.getOrNull()
private fun fmtMoney(v: Double?): String = v?.let { String.format(localePt, "R$ %,.2f", it) } ?: ""

@Composable
fun DeadlinesSection(vm: ObdViewModel) {
    val list by vm.deadlines.collectAsState()
    val statuses = remember(list) { Deadlines.statuses(list) }
    var editing by remember { mutableStateOf<Deadline?>(null) }
    var creating by remember { mutableStateOf(false) }
    var renewing by remember { mutableStateOf<Deadline?>(null) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { creating = true }) { Text("Novo vencimento") }
            Spacer(Modifier.width(12.dp))
            Text(
                "IPVA, licenciamento, seguro, CNH, garantias. \"Pago\" avança para o próximo período.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn {
            items(statuses, key = { it.deadline.id }) { s ->
                DeadlineRow(s, onEdit = { editing = s.deadline }, onRenew = { renewing = s.deadline })
            }
        }
    }
    if (creating) DeadlineDialog(null, { creating = false }, { vm.saveDeadline(it); creating = false }, null)
    editing?.let { d ->
        DeadlineDialog(d, { editing = null }, { vm.saveDeadline(it); editing = null }, { vm.deleteDeadline(d.id); editing = null })
    }
    renewing?.let { d ->
        val next = Deadlines.renew(d).dueMillis
        AlertDialog(
            onDismissRequest = { renewing = null },
            title = { Text("${d.name}: marcar como pago?") },
            text = {
                Text(
                    if (next != null) "O próximo vencimento passa para ${fmtDate(next)}."
                    else "Este vencimento não se repete; a data será limpa até você informar a próxima.",
                )
            },
            confirmButton = { TextButton(onClick = { vm.renewDeadline(d.id); renewing = null }) { Text("Confirmar") } },
            dismissButton = { TextButton(onClick = { renewing = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun DeadlineRow(s: DeadlineStatus, onEdit: () -> Unit, onRenew: () -> Unit) {
    val d = s.deadline
    val (color, label) = when (s.state) {
        DueState.OVERDUE -> Color(0xFFE5484D) to "Vencido há ${-(s.daysLeft ?: 0)} dias"
        DueState.SOON -> Color(0xFFF5A524) to if (s.daysLeft == 0L) "Vence hoje" else "Vence em ${s.daysLeft} dias"
        DueState.OK -> Color(0xFF46A758) to "Faltam ${s.daysLeft} dias"
        else -> Color(0xFF8E8E93) to "Sem data"
    }
    val detail = listOfNotNull(
        d.dueMillis?.let { fmtDate(it) },
        d.repeatMonths?.let { if (it == 12) "anual" else "a cada $it meses" },
        fmtMoney(d.amount).ifEmpty { null },
        d.note.ifBlank { null },
    ).joinToString(" · ")
    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(color, RoundedCornerShape(6.dp)))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("${d.name} · $label", fontWeight = FontWeight.Bold)
                if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onEdit) { Text("Editar") }
            if (d.dueMillis != null) TextButton(onClick = onRenew) { Text("Pago") }
        }
    }
}

@Composable
private fun DeadlineDialog(initial: Deadline?, onDismiss: () -> Unit, onSave: (Deadline) -> Unit, onDelete: (() -> Unit)?) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var date by remember { mutableStateOf(initial?.dueMillis?.let { fmtDate(it) } ?: "") }
    var repeat by remember { mutableStateOf(initial?.repeatMonths?.toString() ?: (if (initial == null) "12" else "")) }
    var warn by remember { mutableStateOf((initial?.warnDaysBefore ?: 15).toString()) }
    var amount by remember { mutableStateOf(initial?.amount?.let { String.format(localePt, "%,.2f", it) } ?: "") }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    val numeric = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Novo vencimento" else "Editar vencimento") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Nome") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(date, { date = it }, label = { Text("Vence em (dd/mm/aaaa)") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(repeat, { repeat = it }, label = { Text("Repete a cada (meses)") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = numeric)
                    OutlinedTextField(warn, { warn = it }, label = { Text("Avisar com (dias)") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = numeric)
                    OutlinedTextField(amount, { amount = it }, label = { Text("Valor (R$)") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = numeric)
                }
                OutlinedTextField(note, { note = it }, label = { Text("Observação (seguradora, apólice, parcelas)") })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val due = if (date.isBlank()) null else readDate(date)
                error = when {
                    name.isBlank() -> "Informe o nome"
                    date.isNotBlank() && due == null -> "Data no formato dd/mm/aaaa"
                    else -> null
                }
                if (error == null) {
                    onSave(
                        (initial ?: Deadline(name = name)).copy(
                            name = name.trim(), dueMillis = due,
                            repeatMonths = Garage.parseNumber(repeat)?.toInt()?.takeIf { it > 0 },
                            warnDaysBefore = Garage.parseNumber(warn)?.toInt() ?: 15,
                            amount = Garage.parseNumber(amount), note = note.trim(),
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
