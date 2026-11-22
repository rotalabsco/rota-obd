package co.rotalabs.obd.core

/**
 * Dados do serviço 21 da Pajero 4M41 (motor 1860C02202, câmbio TCM 8631A87001), descobertos na viagem
 * de 22/11/2026 (veja docs/veiculo-pajero-4m41.md). Só valem para essa calibração: em outro carro os
 * mesmos bytes podem significar outra coisa.
 *
 * Câmbio (7E1):
 * - 2108, byte de dados 4: temperatura do fluido, °C = valor − 40 (a frio bate com a ambiente; aquece
 *   mais devagar que o motor). O byte 6 do 2102 é a mesma temperatura com −50.
 * - 2108, byte de dados 2: alavanca. E0 = P, 00 = R, 20 = N, 40 = D (bits altos).
 * - 2102, byte de dados 9: marcha engatada, um bit por marcha: 04 = 1ª, 08 = 2ª, 10 = 3ª, 20 = 4ª, 40 = 5ª.
 *
 * Motor (7E0):
 * - 2103, bytes de dados 1–3: provável hodômetro em km (subiu 7 em 6,9 km; marcava 234.143). A confirmar
 *   com o painel do carro.
 */
object PerfilMitsubishi {
    val engineCalibrations = setOf("1860C02202")

    /** Ids no Ao vivo e no painel (fora da faixa dos PIDs e dos calculados). */
    const val ATF = 0x110
    const val GEAR = 0x111
    const val ODO = 0x112
    val ids = listOf(ATF, GEAR, ODO)

    /** Marcados sozinhos no Ao vivo na primeira vez (o hodômetro fica opcional). */
    val autoSelect = listOf(ATF, GEAR)

    const val REQ_2108 = "2108"
    const val REQ_2102 = "2102"
    const val REQ_2103 = "2103"

    fun active(engineCalibration: String?): Boolean = engineCalibration?.trim() in engineCalibrations

    fun name(id: Int): String? = when (id) {
        ATF -> "Temp. do fluido do câmbio"
        GEAR -> "Marcha"
        ODO -> "Hodômetro (módulo do motor)"
        else -> null
    }

    fun unit(id: Int): String = when (id) {
        ATF -> "°C"
        ODO -> "km"
        else -> ""
    }

    fun note(id: Int): String? = when (id) {
        ATF -> "câmbio · serviço 21 (21 08)"
        GEAR -> "câmbio · serviço 21 (21 02 e 21 08)"
        ODO -> "motor · serviço 21 (21 03) · confira com o painel"
        else -> null
    }

    /** [r2103]: resposta inteira do motor, começando em 61 03. */
    fun odometer(r2103: IntArray?): Double? =
        r2103?.takeIf { it.size >= 6 && it[0] == 0x61 && it[1] == 0x03 }?.let { ((it[3] shl 16) or (it[4] shl 8) or it[5]).toDouble() }

    /** [r2108]: resposta inteira, começando em 61 08. */
    fun atf(r2108: IntArray?): Double? =
        r2108?.takeIf { it.size >= 7 && it[0] == 0x61 && it[1] == 0x08 }?.let { (it[6] - 40).toDouble() }

    fun lever(r2108: IntArray?): String? {
        val b = r2108?.takeIf { it.size >= 5 && it[0] == 0x61 && it[1] == 0x08 }?.get(4) ?: return null
        return when (b and 0xE0) {
            0xE0 -> "P"
            0x00 -> "R"
            0x20 -> "N"
            0x40 -> "D"
            else -> null
        }
    }

    /** [r2102]: resposta inteira do câmbio, começando em 61 02 (12 bytes). */
    fun gear(r2102: IntArray?): Int? {
        val b = r2102?.takeIf { it.size >= 12 && it[0] == 0x61 && it[1] == 0x02 }?.get(11) ?: return null
        return when (b and 0x7C) {
            0x04 -> 1
            0x08 -> 2
            0x10 -> 3
            0x20 -> 4
            0x40 -> 5
            else -> null
        }
    }

    /** "P", "R", "N" pela alavanca; em D, a marcha engatada ("3ª"). */
    fun gearText(r2108: IntArray?, r2102: IntArray?): String? = when (val l = lever(r2108)) {
        "P", "R", "N" -> l
        else -> gear(r2102)?.let { "${it}ª" } ?: l
    }

    fun hex(s: String?): IntArray? =
        s?.takeIf { it.length >= 4 && it.length % 2 == 0 }?.let { h -> IntArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16) } }
}
