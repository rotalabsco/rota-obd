package co.rotalabs.obd.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bytes reais da Pajero, da coleta de 22/11/2026. */
class PerfilMitsubishiTest {
    private fun h(s: String) = PerfilMitsubishi.hex(s.replace(" ", ""))

    @Test
    fun alavancaETemperaturaDoFluido() {
        assertEquals("P", PerfilMitsubishi.lever(h("61 08 00 00 E0 00 54 00 15 40")))
        assertEquals("R", PerfilMitsubishi.lever(h("61 08 10 00 00 00 55 00 15 40")))
        assertEquals("N", PerfilMitsubishi.lever(h("61 08 00 00 20 00 55 00 00 40")))
        assertEquals("D", PerfilMitsubishi.lever(h("61 08 00 00 40 24 56 00 00 40")))
        assertEquals(44.0, PerfilMitsubishi.atf(h("61 08 00 00 E0 00 54 00 15 40"))!!, 0.0)
        // Na chegada, motor quente: 59 °C.
        assertEquals(59.0, PerfilMitsubishi.atf(h("61 08 00 00 E0 00 63 00 15 40"))!!, 0.0)
        assertNull(PerfilMitsubishi.atf(h("61 05 40 42 0F D0 02 80 00 00")))
    }

    @Test
    fun marchaEngatada() {
        val d = h("61 08 00 00 40 24 56 00 00 40")
        assertEquals("3ª", PerfilMitsubishi.gearText(d, h("61 02 FF 04 FA 00 00 74 6D 00 FF 90")))
        assertEquals("5ª", PerfilMitsubishi.gearText(d, h("61 02 FF 04 FA 00 00 74 6D 00 FF C0")))
        // Em P a alavanca manda, mesmo com o bit da 1ª ligado (0x85).
        assertEquals("P", PerfilMitsubishi.gearText(h("61 08 00 00 E0 00 63 00 15 40"), h("61 02 FF 04 FA 00 00 74 6D 00 FF 85")))
        assertEquals(1, PerfilMitsubishi.gear(h("61 02 FF 04 FA 00 00 74 6D 00 FF 84")))
        assertNull(PerfilMitsubishi.gear(h("61 02 2D 80 4C 56 CA"))) // 2102 do motor, não do câmbio
    }

    @Test
    fun soNaCalibracaoDaPajero() {
        assertTrue(PerfilMitsubishi.active("1860C02202"))
        assertFalse(PerfilMitsubishi.active("XYZ"))
        assertFalse(PerfilMitsubishi.active(null))
    }
}
