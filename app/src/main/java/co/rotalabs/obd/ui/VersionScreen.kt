package co.rotalabs.obd.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.rotalabs.obd.core.UpdateCheck
import co.rotalabs.obd.ui.theme.Rl
import co.rotalabs.obd.ui.theme.RlIcons
import co.rotalabs.obd.ui.theme.RlType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val quando = DateTimeFormatter.ofPattern("dd/MM 'às' HH:mm")

private fun fmt(millis: Long): String = quando.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
private fun fmtIso(iso: String?): String? = iso?.let { runCatching { fmt(Instant.parse(it).toEpochMilli()) }.getOrNull() }

/** Versão instalada, a mais nova no GitHub, o que mudou e o botão de atualizar. */
@Composable
fun VersionScreen(vm: ObdViewModel) {
    val update by vm.update.collectAsState()
    val latest by vm.latestRelease.collectAsState()
    val lastCheck by vm.lastCheckAt.collectAsState()
    val installed = vm.installedBuild
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenHeader("Versão", "Rota.OBD v${vm.installedVersion}") {
            SecondaryButton(
                "Procurar agora", { vm.checkUpdate(manual = true) }, icon = RlIcons.refresh,
                enabled = update !is UpdateState.Checking && update !is UpdateState.Downloading && update !is UpdateState.Installing,
            )
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1.2f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                StatusCard(vm, update, installed, latest)
                RlCard(Modifier.fillMaxWidth()) {
                    Overline("Esta central")
                    Spacer(Modifier.height(8.dp))
                    Info("Instalada", "v${vm.installedVersion} (build $installed)")
                    Info("Instalada em", fmt(vm.installedAt))
                    Info("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    Info("Aparelho", "${Build.MANUFACTURER} ${Build.MODEL}")
                    Info("Última busca", lastCheck?.let { fmt(it) } ?: "ainda não procurou")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "O app procura sozinho ao abrir, ao voltar para a tela (a cada 15 min, ou 2 min se a última busca falhou) " +
                            "e de hora em hora. Precisa de internet na central.",
                        style = MaterialTheme.typography.bodySmall, color = Rl.text3,
                    )
                }
            }
            RlCard(Modifier.weight(1f).fillMaxHeight()) {
                Overline(latest?.let { "O que mudou · build ${it.build}" } ?: "O que mudou")
                Spacer(Modifier.height(10.dp))
                val changes = UpdateCheck.changes(latest?.notes)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        latest == null -> Text("Toque em Procurar agora para ver a versão mais nova.", style = MaterialTheme.typography.bodyMedium, color = Rl.text3)
                        changes.isEmpty() -> Text("Esta versão não trouxe a lista de mudanças.", style = MaterialTheme.typography.bodyMedium, color = Rl.text3)
                        else -> changes.forEach { c ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("•", color = Rl.accent, style = MaterialTheme.typography.bodyMedium)
                                Text(c, style = MaterialTheme.typography.bodyMedium, color = Rl.text2)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(vm: ObdViewModel, update: UpdateState, installed: Long, latest: UpdateCheck.Release?) {
    // Depois de "Depois" no aviso o estado volta a Idle, mas a versão nova continua lá: mostra assim mesmo.
    val newer = latest?.takeIf { UpdateCheck.isNewer(it, installed) }
    val quiet = update is UpdateState.Idle || update is UpdateState.UpToDate || (update is UpdateState.Failed && update.release == null)
    val shown = if (quiet && newer != null) UpdateState.Available(newer) else update
    val (title, detail, tone) = when (val s = shown) {
        is UpdateState.Available -> Triple(
            "Nova versão: build ${s.release.build}",
            listOfNotNull("Você está na build $installed.", fmtIso(s.release.publishedAt)?.let { "Publicada em $it." }).joinToString(" "),
            Rl.accent,
        )
        is UpdateState.UpToDate -> Triple("Você está na versão mais nova", "Build $installed.", Rl.success)
        is UpdateState.Checking -> Triple("Procurando versão nova…", "Consultando o GitHub.", Rl.text2)
        is UpdateState.Downloading -> Triple("Baixando a build ${s.release.build}", s.progress?.let { "${(it * 100).toInt()}%" } ?: "…", Rl.accent)
        is UpdateState.NeedsPermission -> Triple("Falta uma permissão", "Libere \"Instalar apps desconhecidos\" para o Rota.OBD e volte aqui.", Rl.warning)
        is UpdateState.Installing -> Triple("Confirme a instalação", "Na janela do Android.", Rl.accent)
        is UpdateState.Failed -> Triple("Não deu certo", s.message, Rl.danger)
        else -> Triple("Ainda não procurou", "Toque em Procurar agora (precisa de internet).", Rl.text2)
    }
    RlCard(Modifier.fillMaxWidth(), border = if (shown is UpdateState.Available) Rl.accentLine else Rl.line) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Dot(tone, 12.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = Rl.text)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = Rl.text2)
            }
        }
        if (update is UpdateState.Downloading) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { update.progress ?: 0f }, modifier = Modifier.fillMaxWidth().height(8.dp),
                color = Rl.orange, trackColor = Rl.hover, drawStopIndicator = {},
            )
        }
        val action = when (shown) {
            is UpdateState.Available -> "Atualizar agora"
            is UpdateState.NeedsPermission, is UpdateState.Failed -> if (shown is UpdateState.Failed && shown.release == null) null else "Tentar de novo"
            else -> null
        }
        if (action != null) {
            Spacer(Modifier.height(14.dp))
            PrimaryButton(action, { if (shown is UpdateState.Available && update !is UpdateState.Available) vm.installLatest() else vm.installUpdate() }, icon = RlIcons.play)
        }
    }
}

@Composable
private fun Info(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Rl.text3)
        Text(value, style = RlType.mono, fontSize = 14.sp, color = Rl.text)
    }
}
