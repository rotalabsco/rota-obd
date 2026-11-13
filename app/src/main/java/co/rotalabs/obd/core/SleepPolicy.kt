package co.rotalabs.obd.core

/**
 * Convive com o modo de espera do adaptador (Vgate e outros).
 *
 * Cada pedido OBD gera tráfego na rede CAN e mantém adaptador e centrais acordados. A tensão lida pelo
 * próprio adaptador (ATRV) não gera tráfego no carro e diz se o motor está ligado: com o alternador
 * carregando fica acima de ~13 V; com o motor desligado cai para ~12,6 V ou menos.
 *
 * - [Mode.ACTIVE]: motor ligado, pode consultar à vontade.
 * - [Mode.QUIET]: motor desligado há [quietAfterMs]; pare de consultar o carro.
 * - [Mode.RELEASE]: motor desligado há [releaseAfterMs], ou o adaptador parou de responder;
 *   solte a conexão para ele dormir. O app reconecta quando o carro ligar de novo.
 */
class SleepPolicy(
    startMillis: Long,
    private val runningVolts: Double = 13.0,
    private val quietAfterMs: Long = 2 * 60_000L,
    private val releaseAfterMs: Long = 7 * 60_000L,
    private val maxMissedReads: Int = 3,
) {
    enum class Mode { ACTIVE, QUIET, RELEASE }

    private var lastRunningAt = startMillis
    private var missed = 0
    var lastVoltage: Double? = null
        private set

    /** Ação pedida pelo usuário conta como atividade: não pausa o que ele acabou de mandar fazer. */
    fun touch(nowMillis: Long) {
        lastRunningAt = maxOf(lastRunningAt, nowMillis)
    }

    fun observe(nowMillis: Long, voltage: Double?): Mode {
        if (voltage == null) {
            missed++
            if (missed >= maxMissedReads) return Mode.RELEASE
        } else {
            missed = 0
            lastVoltage = voltage
            if (voltage >= runningVolts) lastRunningAt = nowMillis
        }
        val off = nowMillis - lastRunningAt
        return when {
            off >= releaseAfterMs -> Mode.RELEASE
            off >= quietAfterMs -> Mode.QUIET
            else -> Mode.ACTIVE
        }
    }

    companion object {
        private val volts = Regex("(\\d{1,2}[.,]\\d)\\s*V", RegexOption.IGNORE_CASE)

        /** Lê a resposta do ATRV ("12.6V", "14.1 V"). */
        fun parseVoltage(raw: String?): Double? =
            raw?.let { volts.find(it)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() }
    }
}
