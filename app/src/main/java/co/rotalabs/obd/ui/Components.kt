package co.rotalabs.obd.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.rotalabs.obd.core.LiveMetrics
import co.rotalabs.obd.ui.theme.Rl
import co.rotalabs.obd.ui.theme.RlType

/** Estado visual de um quadro: normal, atenção, perigo ou leitura que falhou. */
enum class Tone { NORMAL, WARNING, DANGER, MISSING }

fun LiveMetrics.Level.tone(): Tone = when (this) {
    LiveMetrics.Level.NORMAL -> Tone.NORMAL
    LiveMetrics.Level.WARNING -> Tone.WARNING
    LiveMetrics.Level.DANGER -> Tone.DANGER
}

@Composable
fun RlCard(
    modifier: Modifier = Modifier,
    background: Color = Rl.raised,
    border: Color = Rl.line,
    padding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .background(background, RoundedCornerShape(Rl.radiusLg))
            .border(1.dp, border, RoundedCornerShape(Rl.radiusLg))
            .padding(padding),
        content = content,
    )
}

@Composable
fun Overline(text: String, color: Color = Rl.text3, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = RlType.overline, color = color, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Cabeçalho de tela: overline técnica, título e ações à direita. */
@Composable
fun ScreenHeader(overline: String, title: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Overline(overline)
            Text(title, style = RlType.title, color = Rl.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

@Composable
private fun ButtonContent(text: String, icon: ImageVector?) {
    if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
    }
    Text(text, style = MaterialTheme.typography.labelLarge, fontSize = 17.sp, maxLines = 1)
}

private val buttonPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)

/** CTA principal: laranja com texto escuro. Um por tela. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    Button(
        onClick, modifier.defaultMinSize(minHeight = Rl.touch), enabled = enabled,
        shape = RoundedCornerShape(Rl.radiusMd), contentPadding = buttonPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = Rl.orange, contentColor = Rl.onAccent,
            disabledContainerColor = Rl.hover, disabledContentColor = Rl.text3,
        ),
    ) { ButtonContent(text, icon) }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    OutlinedButton(
        onClick, modifier.defaultMinSize(minHeight = Rl.touch), enabled = enabled,
        shape = RoundedCornerShape(Rl.radiusMd), contentPadding = buttonPadding,
        border = BorderStroke(1.dp, if (enabled) Rl.lineStrong else Rl.line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Rl.text, disabledContentColor = Rl.text3),
    ) { ButtonContent(text, icon) }
}

@Composable
fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = false, enabled: Boolean = true) {
    if (filled) {
        Button(
            onClick, modifier.defaultMinSize(minHeight = Rl.touch), enabled = enabled,
            shape = RoundedCornerShape(Rl.radiusMd), contentPadding = buttonPadding,
            colors = ButtonDefaults.buttonColors(containerColor = Rl.danger, contentColor = Rl.onAccent),
        ) { ButtonContent(text, null) }
    } else {
        OutlinedButton(
            onClick, modifier.defaultMinSize(minHeight = Rl.touch), enabled = enabled,
            shape = RoundedCornerShape(Rl.radiusMd), contentPadding = buttonPadding,
            border = BorderStroke(1.dp, if (enabled) Rl.dangerLine else Rl.line),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Rl.danger, disabledContentColor = Rl.text3),
        ) { ButtonContent(text, null) }
    }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(
        onClick, modifier.defaultMinSize(minHeight = 48.dp), enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = Rl.text2, disabledContentColor = Rl.text3),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** Selo curto em mono: estado, PID, tipo. */
@Composable
fun Badge(text: String, tone: Tone = Tone.NORMAL, accent: Boolean = false) {
    val (fg, bg) = when {
        accent -> Rl.accent to Rl.accentSoft
        tone == Tone.WARNING -> Rl.warning to Rl.warningSoft
        tone == Tone.DANGER -> Rl.danger to Rl.dangerSoft
        tone == Tone.MISSING -> Rl.text2 to Rl.hover
        else -> Rl.success to Rl.successSoft
    }
    Text(
        text.uppercase(), style = RlType.overline, color = fg,
        modifier = Modifier.background(bg, RoundedCornerShape(Rl.radiusSm)).padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

@Composable
fun Dot(color: Color, size: Dp = 10.dp) = Box(Modifier.size(size).background(color, CircleShape))

/** Abas em pílula, com alvo de toque grande. */
@Composable
fun <T> PillTabs(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            val on = o == selected
            Box(
                Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .background(if (on) Rl.accentSoft else Color.Transparent, RoundedCornerShape(Rl.radiusMd))
                    .border(1.dp, if (on) Rl.accentLine else Rl.line, RoundedCornerShape(Rl.radiusMd))
                    .semantics { this.selected = on }
                    .clickable(role = Role.Tab) { onSelect(o) }
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(o), style = MaterialTheme.typography.labelLarge, color = if (on) Rl.accent else Rl.text2)
            }
        }
    }
}

/**
 * Quadro de leitura. Sem valor mostra "—" e o motivo: falha de leitura nunca vira zero.
 * O tamanho do número acompanha a altura do quadro, para caber em qualquer densidade de tela.
 */
@Composable
fun MetricTile(
    label: String,
    value: String?,
    unit: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    tone: Tone = Tone.NORMAL,
    badge: String? = null,
    maxNumber: TextUnit = 56.sp,
) {
    val missing = value == null
    val t = if (missing) Tone.MISSING else tone
    val (bg, border) = when (t) {
        Tone.WARNING -> Rl.warningSoft to Rl.warningLine
        Tone.DANGER -> Rl.dangerSoft to Rl.dangerLine
        Tone.MISSING -> Rl.bg to Rl.lineStrong
        Tone.NORMAL -> Rl.raised to Rl.line
    }
    val numberColor = when (t) {
        Tone.WARNING -> Rl.warning
        Tone.DANGER -> Rl.danger
        Tone.MISSING -> Rl.text3
        Tone.NORMAL -> Rl.text
    }
    RlCard(modifier, background = bg, border = border, padding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = if (t == Tone.WARNING || t == Tone.DANGER) Rl.text else Rl.text2,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            badge?.let { Badge(it, accent = true) }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            // Cabe na altura e na largura: mono tem ~0,6 em por caractere; a unidade sai a 30% do tamanho.
            val shown = value ?: "—"
            val unitChars = if (missing) 0 else unit.length
            val byHeight = maxHeight.value * 0.62f
            val byWidth = maxWidth.value / (shown.length * 0.62f + unitChars * 0.19f + 0.6f)
            val size = minOf(byHeight, byWidth, maxNumber.value).sp
            Row(verticalAlignment = Alignment.Bottom) {
                Text(shown, style = RlType.metric, fontSize = size, color = numberColor, maxLines = 1, softWrap = false)
                if (!missing && unit.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        unit, style = RlType.metric, fontSize = (size.value * 0.3f).coerceAtLeast(14f).sp, maxLines = 1, softWrap = false,
                        color = Rl.text2, modifier = Modifier.padding(bottom = (size.value * 0.12f).dp),
                    )
                }
            }
        }
        caption?.let {
            Text(
                it, style = RlType.mono, fontSize = 13.sp,
                color = when (t) {
                    Tone.WARNING -> Rl.warning
                    Tone.DANGER -> Rl.danger
                    Tone.MISSING -> Rl.text2
                    Tone.NORMAL -> Rl.text3
                },
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Tela vazia com orientação do que fazer. */
@Composable
fun EmptyState(title: String, text: String, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    RlCard(modifier.fillMaxWidth(), padding = PaddingValues(28.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Rl.text)
        Spacer(Modifier.height(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Rl.text2)
        Spacer(Modifier.height(18.dp))
        action()
    }
}
