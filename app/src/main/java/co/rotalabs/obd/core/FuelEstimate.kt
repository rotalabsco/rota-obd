package co.rotalabs.obd.core

/**
 * Consumo estimado para motores que não informam a vazão de combustível (PID 5E), como o 4M41 da
 * Pajero.
 *
 * Em diesel, a carga calculada (PID 04) acompanha o volume injetado em relação ao máximo. Então:
 * litros/hora ≈ carga × volume de referência por injeção × cilindros × injeções por minuto.
 * Com o pedal solto e o motor segurando o carro (freio motor), a carga vai a 0 e o consumo também.
 *
 * O volume de referência foi calibrado com a viagem de 21/11/2026 (151 km): com 80 mm³ o modelo dava
 * 14,5 km/L e o Car Scanner marcou cerca de 9,5 km/L na mesma viagem, então 80 × 14,5 / 9,5 ≈ 122 mm³.
 * Os abastecimentos de tanque cheio na Garagem servem para conferir e ajustar esse número.
 */
object FuelEstimate {
    const val DEFAULT_REF_MM3 = 122.0
    const val CYLINDERS = 4

    /** Abaixo disso o carro está parado ou quase: mostra L/h em vez de km/L. */
    const val MOVING_KMH = 5.0

    /** Intervalo maior que isso entre leituras não entra na conta (conexão caiu, carro parado). */
    const val MAX_GAP_MS = 5_000L

    /** Sem leitura por esse tempo, a próxima leitura começa uma viagem nova. */
    const val NEW_TRIP_AFTER_MS = 30 * 60_000L

    fun litersPerHour(loadPct: Double, rpm: Double, refMm3: Double = DEFAULT_REF_MM3): Double =
        (loadPct.coerceIn(0.0, 100.0) / 100.0) * refMm3 * CYLINDERS * (rpm.coerceAtLeast(0.0) / 2.0) * 60.0 / 1_000_000.0

    /** km/L andando; null parado (aí vale o L/h). Limitado a 99,9: com o pé solto o consumo vai a zero. */
    fun kmPerLiter(speedKmh: Double, litersPerHour: Double): Double? {
        if (speedKmh < MOVING_KMH) return null
        if (litersPerHour <= 0.0) return 99.9
        return (speedKmh / litersPerHour).coerceAtMost(99.9)
    }

    /** Consumo para mostrar: km/L andando, L/h parado. */
    fun instant(loadPct: Double?, rpm: Double?, speedKmh: Double?): Pair<Double, String>? {
        if (loadPct == null || rpm == null || speedKmh == null) return null
        val lph = litersPerHour(loadPct, rpm)
        return kmPerLiter(speedKmh, lph)?.let { it to "km/L" } ?: (lph to "L/h")
    }

    /**
     * Consumo instantâneo suavizado (média móvel exponencial de ~3 s), para o motorista dosar o pé sem o
     * número pular a cada leitura. Suaviza litros/hora e velocidade separadamente e divide no fim.
     */
    class Smoother(private val tauMs: Double = 3_000.0) {
        private var lph: Double? = null
        private var speed: Double? = null
        private var load: Double? = null
        private var lastMs: Long? = null

        fun add(loadPct: Double, rpm: Double, speedKmh: Double, nowMs: Long) {
            val l = litersPerHour(loadPct, rpm)
            val prev = lastMs
            lastMs = nowMs
            val dt = if (prev == null) null else (nowMs - prev).toDouble()
            if (dt == null || dt <= 0 || dt > MAX_GAP_MS || lph == null) {
                lph = l; speed = speedKmh; load = loadPct
                return
            }
            val a = dt / (tauMs + dt)
            lph = lph!! + a * (l - lph!!)
            speed = speed!! + a * (speedKmh - speed!!)
            // A carga não é suavizada: o corte de injeção (pé solto) aparece na hora.
            load = loadPct
        }

        /** km/L andando ou L/h parado; null antes da primeira leitura. */
        fun value(): Pair<Double, String>? {
            val l = lph ?: return null
            val v = speed ?: return null
            return kmPerLiter(v, l)?.let { it to "km/L" } ?: (l to "L/h")
        }

        /** Andando com a injeção cortada (carga 0): o carro não está gastando nada. */
        fun fuelCut(): Boolean = (load ?: 1.0) <= 0.0 && (speed ?: 0.0) >= MOVING_KMH
    }

    /** Texto do instantâneo: "∞" no corte de injeção; ▲/▼ comparando com a média da viagem. */
    fun instantText(value: Pair<Double, String>?, fuelCut: Boolean, average: Double?): String? {
        if (fuelCut) return "∞ km/L"
        val (v, u) = value ?: return null
        val n = Formato.leitura(v, u) + " " + u
        if (u != "km/L" || average == null) return n
        return n + if (v >= average) " ▲" else " ▼"
    }

    /** Acumula litros e quilômetros da viagem a cada leitura de rotação. */
    class Trip {
        var liters = 0.0
            private set
        var km = 0.0
            private set
        private var lastMs: Long? = null

        fun add(loadPct: Double, rpm: Double, speedKmh: Double, nowMs: Long) {
            val prev = lastMs
            lastMs = nowMs
            if (prev == null) return
            val dt = nowMs - prev
            if (dt >= NEW_TRIP_AFTER_MS) {
                liters = 0.0
                km = 0.0
                return
            }
            if (dt <= 0 || dt > MAX_GAP_MS) return
            val h = dt / 3_600_000.0
            liters += litersPerHour(loadPct, rpm) * h
            km += speedKmh.coerceAtLeast(0.0) * h
        }

        /** Média em km/L; null até ter ao menos 1 km rodado. */
        fun average(): Double? = if (km < 1.0 || liters <= 0.0) null else km / liters
    }
}
