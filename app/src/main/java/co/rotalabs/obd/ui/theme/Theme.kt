package co.rotalabs.obd.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import co.rotalabs.obd.R

/**
 * Tokens do RotaLabs DS v2 (tema escuro, o padrão). Os nomes seguem o DS (`--ds-*`).
 * Fonte: https://rotalabs.co — design system "RotaLabs DS".
 */
object Rl {
    val bg = Color(0xFF0B0C0D)
    val raised = Color(0xFF121416)
    val sunken = Color(0xFF07080A)
    val hover = Color(0xFF1A1D20)
    val line = Color(0xFF23272B)
    val lineStrong = Color(0xFF3A4248)
    val text = Color(0xFFF2F1EE)
    val text2 = Color(0xFFA9AEB2)
    val text3 = Color(0xFF80878C)
    val orange = Color(0xFFE77C22)
    val accent = Color(0xFFF28A2E)
    val accentHover = Color(0xFFFF9A42)
    val accentSoft = Color(0xFF2A1708)
    val accentLine = Color(0x59E77C22)
    val onAccent = Color(0xFF0B0C0D)
    val info = Color(0xFF7AA2FF)
    val infoSoft = Color(0xFF121C33)
    val success = Color(0xFF4CC38A)
    val successSoft = Color(0xFF10261B)
    val warning = Color(0xFFF2B441)
    val warningSoft = Color(0xFF1D1608)
    val warningLine = Color(0xFF5A4318)
    val danger = Color(0xFFF0655A)
    val dangerSoft = Color(0xFF22100E)
    val dangerLine = Color(0xFF5C2621)

    val radiusSm = 6.dp
    val radiusMd = 10.dp
    val radiusLg = 16.dp

    /** Alvo de toque mínimo para uso no carro. */
    val touch = 56.dp
}

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) = Font(
    res, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

object RlFonts {
    val display = FontFamily(variable(R.font.bricolage_grotesque, 500), variable(R.font.bricolage_grotesque, 600))
    val sans = FontFamily(variable(R.font.geist, 400), variable(R.font.geist, 500), variable(R.font.geist, 600))
    val mono = FontFamily(variable(R.font.geist_mono, 400), variable(R.font.geist_mono, 500))
}

/** Estilos próprios do app (além do Material): números grandes e overline. */
object RlType {
    val overline = TextStyle(fontFamily = RlFonts.mono, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.08.em)
    val metric = TextStyle(fontFamily = RlFonts.mono, fontWeight = FontWeight.Medium, letterSpacing = (-0.03).em)
    val mono = TextStyle(fontFamily = RlFonts.mono, fontWeight = FontWeight.Normal, fontSize = 14.sp)
    val title = TextStyle(fontFamily = RlFonts.display, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, letterSpacing = (-0.02).em, lineHeight = 32.sp)
}

private val colors = darkColorScheme(
    primary = Rl.orange,
    onPrimary = Rl.onAccent,
    primaryContainer = Rl.accentSoft,
    onPrimaryContainer = Rl.accent,
    secondary = Rl.text2,
    onSecondary = Rl.bg,
    secondaryContainer = Rl.hover,
    onSecondaryContainer = Rl.text,
    tertiary = Rl.info,
    background = Rl.bg,
    onBackground = Rl.text,
    surface = Rl.raised,
    onSurface = Rl.text,
    surfaceVariant = Rl.hover,
    onSurfaceVariant = Rl.text2,
    surfaceContainerLowest = Rl.sunken,
    surfaceContainerLow = Rl.raised,
    surfaceContainer = Rl.raised,
    surfaceContainerHigh = Rl.hover,
    surfaceContainerHighest = Rl.hover,
    outline = Rl.lineStrong,
    outlineVariant = Rl.line,
    error = Rl.danger,
    onError = Rl.bg,
    errorContainer = Rl.dangerSoft,
    onErrorContainer = Rl.danger,
)

private val typography = Typography().let { t ->
    val sans = RlFonts.sans
    val display = RlFonts.display
    t.copy(
        displayLarge = t.displayLarge.copy(fontFamily = display, fontWeight = FontWeight.SemiBold),
        displayMedium = t.displayMedium.copy(fontFamily = display, fontWeight = FontWeight.SemiBold),
        displaySmall = t.displaySmall.copy(fontFamily = display, fontWeight = FontWeight.SemiBold),
        headlineLarge = t.headlineLarge.copy(fontFamily = display, fontWeight = FontWeight.SemiBold),
        headlineMedium = t.headlineMedium.copy(fontFamily = display, fontWeight = FontWeight.SemiBold),
        headlineSmall = t.headlineSmall.copy(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 26.sp),
        titleLarge = t.titleLarge.copy(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
        titleMedium = t.titleMedium.copy(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
        titleSmall = t.titleSmall.copy(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 16.sp),
        bodyLarge = t.bodyLarge.copy(fontFamily = sans, fontSize = 18.sp, lineHeight = 26.sp),
        bodyMedium = t.bodyMedium.copy(fontFamily = sans, fontSize = 16.sp, lineHeight = 23.sp),
        bodySmall = t.bodySmall.copy(fontFamily = sans, fontSize = 14.sp, lineHeight = 20.sp),
        labelLarge = t.labelLarge.copy(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 16.sp),
        labelMedium = t.labelMedium.copy(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 14.sp),
        labelSmall = t.labelSmall.copy(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 13.sp),
    )
}

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(Rl.radiusSm),
    small = RoundedCornerShape(Rl.radiusMd),
    medium = RoundedCornerShape(Rl.radiusMd),
    large = RoundedCornerShape(Rl.radiusLg),
    extraLarge = RoundedCornerShape(Rl.radiusLg),
)

@Composable
fun RotaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes, content = content)
}
