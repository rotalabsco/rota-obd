package co.rotalabs.obd.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PanelItemsTest {
    @Test
    fun escolhaRespeitaMaximoEOrdem() {
        var sel = listOf(PanelItems.VOLTAGE)
        sel = PanelItems.toggle(sel, 0x05)
        assertEquals(listOf(0x05, PanelItems.VOLTAGE), sel)
        sel = PanelItems.toggle(sel, 0x0C)
        sel = PanelItems.toggle(sel, 0x0D)
        assertEquals(4, sel.size)
        assertEquals(sel, PanelItems.toggle(sel, 0x04)) // já tem 4
        assertEquals(listOf(0x0C, 0x0D, PanelItems.VOLTAGE), PanelItems.toggle(sel, 0x05))
    }

    @Test
    fun gravaELe() {
        assertEquals(listOf(0x05, PanelItems.VOLTAGE), PanelItems.decode(PanelItems.encode(listOf(0x05, PanelItems.VOLTAGE))))
        assertEquals(listOf(0x0C), PanelItems.decode("12,999,x"))
        assertNull(PanelItems.decode(null))
    }

    @Test
    fun linhasComValorRecenteOuTraco() {
        val now = 100_000L
        val readings = mapOf(
            0x05 to PanelItems.Reading(88.0, now - 1_000),
            0x0C to PanelItems.Reading(1840.0, now - 60_000), // velho
        )
        val lines = PanelItems.lines(listOf(0x05, 0x0C, PanelItems.VOLTAGE), readings, 13.92, now)
        assertEquals(listOf("Motor" to "88,0 °C", "Rotação" to "—", "Bateria" to "13,9 V"), lines)
    }
}
