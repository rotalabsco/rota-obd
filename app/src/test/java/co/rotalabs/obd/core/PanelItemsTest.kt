package co.rotalabs.obd.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PanelItemsTest {
    @Test
    fun escolhaRespeitaMaximoEOrdem() {
        val order = PanelItems.options(listOf(0x0D, 0x0C, LiveSelection.BOOST, 0x05, 0x04)).map { it.id }
        assertEquals(listOf(0x0D, 0x0C, LiveSelection.BOOST, 0x05, 0x04, PanelItems.VOLTAGE), order)
        var sel = listOf(PanelItems.VOLTAGE)
        sel = PanelItems.toggle(sel, 0x05, order)
        assertEquals(listOf(0x05, PanelItems.VOLTAGE), sel)
        sel = PanelItems.toggle(sel, 0x0C, order)
        sel = PanelItems.toggle(sel, LiveSelection.BOOST, order)
        assertEquals(listOf(0x0C, LiveSelection.BOOST, 0x05, PanelItems.VOLTAGE), sel)
        assertEquals(sel, PanelItems.toggle(sel, 0x04, order)) // já tem 4
        assertEquals(listOf(0x0C, LiveSelection.BOOST, PanelItems.VOLTAGE), PanelItems.toggle(sel, 0x05, order))
    }

    @Test
    fun gravaELe() {
        assertEquals(listOf(0x05, PanelItems.VOLTAGE), PanelItems.decode(PanelItems.encode(listOf(0x05, PanelItems.VOLTAGE))))
        assertEquals(listOf(12, 999), PanelItems.decode("12,999,x"))
        assertNull(PanelItems.decode(null))
    }

    @Test
    fun linhasComValorRecenteOuTraco() {
        val now = 100_000L
        val readings = mapOf(
            0x05 to PanelItems.Reading(88.0, now - 1_000),
            0x0C to PanelItems.Reading(1840.0, now - 90_000), // velho
            0x0B to PanelItems.Reading(166.0, now - 500),
            0x33 to PanelItems.Reading(100.0, now - 30_000),
        )
        val lines = PanelItems.lines(listOf(0x05, 0x0C, LiveSelection.BOOST, PanelItems.VOLTAGE), readings, 13.92, now)
        assertEquals(listOf("Motor" to "88,0 °C", "Rotação" to "—", "Turbo" to "0,66 bar", "Bateria" to "13,9 V"), lines)
        assertEquals(listOf(0x05, 0x0B, 0x33), PanelItems.pidsToRead(listOf(0x05, LiveSelection.BOOST, PanelItems.VOLTAGE)))
    }
}
