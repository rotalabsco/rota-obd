package co.rotalabs.obd.core

import java.util.Locale

/** Números no formato brasileiro: 1.840 rpm, 88,0 °C, R$ 1.236,40. */
object Formato {
    private val ptBR = Locale("pt", "BR")

    // Criar um formatador a cada número custava caro (era o maior gasto de CPU do painel e do Ao vivo).
    private val formatos = ThreadLocal.withInitial { arrayOfNulls<java.text.DecimalFormat>(4) }

    fun numero(v: Double, casas: Int): String {
        val c = casas.coerceIn(0, 3)
        val cache = formatos.get()!!
        val f = cache[c] ?: (java.text.NumberFormat.getNumberInstance(ptBR) as java.text.DecimalFormat).apply {
            minimumFractionDigits = c
            maximumFractionDigits = c
            isGroupingUsed = true
            roundingMode = java.math.RoundingMode.HALF_UP
        }.also { cache[c] = it }
        return f.format(v)
    }

    fun dinheiro(v: Double): String = "R$ " + numero(v, 2)

    /** Casas decimais pela grandeza: rotação e velocidade inteiras, temperatura e tensão com uma casa. */
    fun casasPara(unidade: String, v: Double): Int = when (unidade) {
        "rpm", "km/h", "km", "s", "Nm", "kPa", "%", "" -> 0
        "°C" -> if (kotlin.math.abs(v) >= 100) 0 else 1
        "V", "L/h", "g/s", "km/L" -> 1
        "bar" -> if (kotlin.math.abs(v) >= 100) 0 else 2
        else -> if (kotlin.math.abs(v) >= 100) 0 else 1
    }

    fun leitura(v: Double, unidade: String): String = numero(v, casasPara(unidade, v))
}

/** Valores derivados e níveis de alerta das leituras ao vivo. */
object LiveMetrics {
    enum class Level { NORMAL, WARNING, DANGER }

    const val PID_SPEED = 0x0D
    const val PID_RPM = 0x0C
    const val PID_COOLANT = 0x05
    const val PID_OIL = 0x5C
    const val PID_FUEL_RATE = 0x5E

    /**
     * Consumo instantâneo em km/L a partir da velocidade (PID 0D) e da vazão (PID 5E).
     * Parado ou quase parado não existe km/L: devolve null em vez de zero ou infinito.
     */
    fun kmPerLiter(speedKmh: Double?, litersPerHour: Double?): Double? {
        if (speedKmh == null || litersPerHour == null) return null
        if (speedKmh < MIN_SPEED_KMH || litersPerHour <= MIN_RATE_LPH) return null
        return speedKmh / litersPerHour
    }

    /**
     * Limites padrão de alerta. Valores conservadores para motor diesel; ajustáveis quando houver
     * perfil do veículo. Sem leitura não há alerta.
     */
    fun level(pid: Int, value: Double?): Level {
        if (value == null) return Level.NORMAL
        val (warn, danger) = limits[pid] ?: return Level.NORMAL
        return when {
            value >= danger -> Level.DANGER
            value >= warn -> Level.WARNING
            else -> Level.NORMAL
        }
    }

    val limits: Map<Int, Pair<Double, Double>> = mapOf(
        PID_COOLANT to (105.0 to 112.0),
        PID_OIL to (120.0 to 130.0),
    )

    private const val MIN_SPEED_KMH = 5.0
    private const val MIN_RATE_LPH = 0.05
}
