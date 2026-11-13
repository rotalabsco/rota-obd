package co.rotalabs.obd.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Ícones de traço no estilo Lucide (https://lucide.dev, licença ISC), redesenhados como caminhos SVG
 * para não depender de biblioteca de ícones.
 */
object RlIcons {
    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach {
                addPath(
                    pathData = addPathNodes(it),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.75f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    val gauge = icon("gauge", "M12 14l4-4", "M3.34 19a10 10 0 1 1 17.32 0")
    val alert = icon(
        "alert",
        "M21.73 18l-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3", "M12 9v4", "M12 17h.01",
    )
    val car = icon(
        "car",
        "M19 17h2c.6 0 1-.4 1-1v-3c0-.9-.7-1.7-1.5-1.9C18.7 10.6 16 10 16 10s-1.3-1.4-2.2-2.3c-.5-.4-1.1-.7-1.8-.7H5c-.6 0-1.1.4-1.4.9l-1.4 2.9A3.7 3.7 0 0 0 2 12v4c0 .6.4 1 1 1h2",
        "M5 17a2 2 0 1 0 4 0a2 2 0 1 0-4 0", "M9 17h6", "M15 17a2 2 0 1 0 4 0a2 2 0 1 0-4 0",
    )
    val radar = icon(
        "radar",
        "M19.07 4.93A10 10 0 0 0 6.99 3.34", "M4 6h.01", "M2.29 9.62A10 10 0 1 0 21.31 8.35",
        "M16.24 7.76A6 6 0 1 0 8.23 16.67", "M12 18h.01", "M17.99 11.66A6 6 0 0 1 15.77 16.67",
        "M10 12a2 2 0 1 0 4 0a2 2 0 1 0-4 0", "M13.41 10.59l5.66-5.66",
    )
    val binary = icon(
        "binary",
        "M16 14a2 2 0 0 1 2 2v2a2 2 0 0 1-4 0v-2a2 2 0 0 1 2-2z", "M8 4a2 2 0 0 1 2 2v2a2 2 0 0 1-4 0V6a2 2 0 0 1 2-2z",
        "M6 20h4", "M14 10h4", "M6 14h2v6", "M14 4h2v6",
    )
    val bluetooth = icon("bluetooth", "M7 7l10 10-5 5V2l5 5L7 17")
    val log = icon(
        "log",
        "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7z", "M14 2v4a2 2 0 0 0 2 2h4",
        "M10 9H8", "M16 13H8", "M16 17H8",
    )
    val refresh = icon(
        "refresh",
        "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8", "M21 3v5h-5",
        "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16", "M8 16H3v5",
    )
    val trash = icon("trash", "M3 6h18", "M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6", "M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2")
    val fuel = icon(
        "fuel",
        "M3 22h12", "M4 9h10", "M14 22V4a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v18",
        "M14 13h2a2 2 0 0 1 2 2v2a2 2 0 0 0 2 2a2 2 0 0 0 2-2V9.83a2 2 0 0 0-.59-1.42L18 5",
    )
    val plus = icon("plus", "M12 5v14", "M5 12h14")
    val share = icon("share", "M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8", "M16 6l-4-4-4 4", "M12 2v13")
    val play = icon("play", "M6 3l14 9-14 9V3z")
    val stop = icon("stop", "M6 6h12v12H6z")
    val check = icon("check", "M20 6L9 17l-5-5")
}
