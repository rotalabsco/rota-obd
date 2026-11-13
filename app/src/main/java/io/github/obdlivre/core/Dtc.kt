package io.github.obdlivre.core

object Dtc {
    /**
     * Decodifica a resposta dos modos 03 (43), 07 (47) ou 0A (4A).
     * Em CAN, o segundo byte é a quantidade de códigos.
     */
    fun decode(response: IntArray): List<String> {
        if (response.size < 2) return emptyList()
        var start = 1
        val rest = response.size - 1
        if (rest % 2 == 1 && response[1] == (rest - 1) / 2) start = 2
        val codes = mutableListOf<String>()
        var i = start
        while (i + 1 < response.size) {
            val a = response[i]
            val b = response[i + 1]
            if (a != 0 || b != 0) codes.add(format(a, b))
            i += 2
        }
        return codes
    }

    fun format(a: Int, b: Int): String {
        val letter = "PCBU"[(a shr 6) and 0x03]
        val d1 = (a shr 4) and 0x03
        val d2 = a and 0x0F
        return "%c%d%X%02X".format(letter, d1, d2, b)
    }
}
