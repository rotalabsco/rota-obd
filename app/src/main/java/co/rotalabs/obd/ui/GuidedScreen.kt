package co.rotalabs.obd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.rotalabs.obd.core.GuidedCollection
import co.rotalabs.obd.core.GuidedCollection.Action
import co.rotalabs.obd.ui.theme.Rl
import co.rotalabs.obd.ui.theme.RlIcons
import co.rotalabs.obd.ui.theme.RlType
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Coleta guiada: o roteiro do teste no carro, passo a passo. Cada passo diz o que fazer em português,
 * roda a leitura sozinho e marca no arquivo o começo e o fim, para a análise e a exportação.
 */
@Composable
fun GuidedScreen(vm: ObdViewModel, shareZip: () -> Unit) {
    val done by vm.guidedDone.collectAsState()
    val run by vm.guidedRun.collectAsState()
    val busy by vm.busy.collectAsState()
    val conn by vm.conn.collectAsState()
    var selectedId by remember { mutableStateOf(GuidedCollection.nextPending(null, done)?.id ?: GuidedCollection.steps.first().id) }
    // Ao terminar um passo, leva para o próximo que falta.
    LaunchedEffect(done) {
        if (run == null && selectedId in done) GuidedCollection.nextPending(selectedId, done)?.let { selectedId = it.id }
    }
    val running = run
    val shownId = running?.stepId ?: selectedId
    val step = GuidedCollection.step(shownId) ?: GuidedCollection.steps.first()
    val total = GuidedCollection.steps.size

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenHeader("Coleta guiada · ${done.size} de $total feitos", "Teste no carro, passo a passo") {
            GhostButton("Nova coleta", vm::resetGuided, enabled = running == null && done.isNotEmpty())
            SecondaryButton("Exportar (.zip)", shareZip, icon = RlIcons.share)
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StepList(Modifier.weight(0.42f).fillMaxHeight(), done, shownId, enabled = running == null) { selectedId = it }
            RlCard(Modifier.weight(0.58f).fillMaxHeight()) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Overline(step.group)
                    Text(step.title, style = MaterialTheme.typography.titleLarge, color = Rl.text)
                    if (running != null) {
                        RunningPanel(vm, step, running)
                    } else {
                        Text(step.howTo, style = MaterialTheme.typography.bodyLarge, color = Rl.text2)
                        if (step.id in done) Badge("Feito", accent = true)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    when {
                        running != null -> SecondaryButton("Parar", vm::stop, icon = RlIcons.stop)
                        step.action is Action.Manual -> PrimaryButton(
                            "Marcar como feito", { vm.runGuidedStep(step) }, icon = RlIcons.check, enabled = step.id !in done,
                        )
                        else -> PrimaryButton(
                            "Começar", { vm.runGuidedStep(step) }, icon = RlIcons.play,
                            enabled = conn is ConnState.Connected && busy == null,
                        )
                    }
                    if (running == null && step.id in done) GhostButton("Desmarcar", { vm.setGuidedDone(step.id, false) })
                    Spacer(Modifier.weight(1f))
                    if (running == null && step.action !is Action.Manual && conn !is ConnState.Connected) {
                        Text("Conecte ao adaptador primeiro", style = MaterialTheme.typography.bodyMedium, color = Rl.warning)
                    } else if (running == null && busy != null) {
                        Text("Aguarde: $busy", style = MaterialTheme.typography.bodyMedium, color = Rl.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        val (files, bytes) = remember(done, running) { vm.storedData() }
        Text(
            "Gravado na central: $files arquivos, ${"%.1f".format(Locale.ROOT, bytes / 1_048_576.0).replace('.', ',')} MB. " +
                "Nada é apagado sozinho; só desinstalar o app apaga.",
            style = MaterialTheme.typography.bodySmall, color = Rl.text3,
        )
    }
}

@Composable
private fun StepList(modifier: Modifier, done: Set<String>, selectedId: String, enabled: Boolean, onSelect: (String) -> Unit) {
    val groups = GuidedCollection.steps.groupBy { it.group }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        groups.forEach { (group, steps) ->
            item(key = group) { Overline(group, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)) }
            items(steps, key = { it.id }) { s ->
                val on = s.id == selectedId
                val isDone = s.id in done
                Row(
                    Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp)
                        .background(if (on) Rl.accentSoft else Color.Transparent, RoundedCornerShape(Rl.radiusMd))
                        .border(1.dp, if (on) Rl.accentLine else Rl.line, RoundedCornerShape(Rl.radiusMd))
                        .clickable(enabled = enabled) { onSelect(s.id) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Dot(if (isDone) Rl.success else Rl.lineStrong, 10.dp)
                    Text(
                        s.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                        color = if (on) Rl.text else if (isDone) Rl.text3 else Rl.text2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun RunningPanel(vm: ObdViewModel, step: GuidedCollection.Step, run: GuidedRun) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(run.startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    val elapsed = ((now - run.startedAt) / 1000).toInt().coerceAtLeast(0)
    when (val a = step.action) {
        is Action.Watch -> {
            val cue = GuidedCollection.cueAt(a.cues, elapsed)
            Text(
                cue?.text ?: "", style = RlType.title, fontSize = 34.sp, lineHeight = 40.sp, color = Rl.accent,
            )
            val dur = a.durationSec
            if (dur != null) {
                val left = (dur - elapsed).coerceAtLeast(0)
                LinearProgressIndicator(
                    progress = { (elapsed.toFloat() / dur).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(10.dp),
                    color = Rl.orange, trackColor = Rl.hover, drawStopIndicator = {},
                )
                Text("Faltam $left s", style = RlType.metric, fontSize = 22.sp, color = Rl.text)
                GuidedCollection.nextCue(a.cues, elapsed)?.let {
                    Text("Depois: ${it.text} (em ${it.atSec - elapsed} s)", style = MaterialTheme.typography.bodyLarge, color = Rl.text2)
                }
            } else {
                Text("Gravando há %d:%02d".format(elapsed / 60, elapsed % 60), style = RlType.metric, fontSize = 22.sp, color = Rl.text)
            }
            Text("${run.samples} leituras", style = MaterialTheme.typography.bodySmall, color = Rl.text3)
        }
        Action.Discovery -> {
            val progress by vm.progress.collectAsState()
            val current by vm.step.collectAsState()
            Text("Lendo tudo o que o carro informa. Não mexa em nada até terminar.", style = MaterialTheme.typography.bodyLarge, color = Rl.text2)
            LinearProgressIndicator(
                progress = { progress }, modifier = Modifier.fillMaxWidth().height(10.dp),
                color = Rl.orange, trackColor = Rl.hover, drawStopIndicator = {},
            )
            Text("${(progress * 100).toInt()}% · " + "%d:%02d".format(elapsed / 60, elapsed % 60), style = RlType.metric, fontSize = 22.sp, color = Rl.text)
            current?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Rl.text3, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Action.Manual -> Unit
    }
}
