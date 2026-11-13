package co.rotalabs.obd.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveMetricsTest {
    @Test
    fun formatoBrasileiro() {
        assertEquals("1.840", Formato.leitura(1840.0, "rpm"))
        assertEquals("88,0", Formato.leitura(88.0, "°C"))
        assertEquals("104", Formato.leitura(104.0, "°C"))
        assertEquals("13,9", Formato.leitura(13.92, "V"))
        assertEquals("R$ 1.236,40", Formato.dinheiro(1236.4))
    }

    @Test
    fun consumoInstantaneo() {
        assertEquals(9.43, LiveMetrics.kmPerLiter(82.0, 8.7)!!, 0.01)
        assertNull("parado não tem km/L", LiveMetrics.kmPerLiter(0.0, 1.2))
        assertNull("sem vazão não vira infinito", LiveMetrics.kmPerLiter(80.0, 0.0))
        assertNull(LiveMetrics.kmPerLiter(null, 5.0))
    }

    @Test
    fun niveisDeAlerta() {
        assertEquals(LiveMetrics.Level.NORMAL, LiveMetrics.level(0x05, 90.0))
        assertEquals(LiveMetrics.Level.WARNING, LiveMetrics.level(0x05, 106.0))
        assertEquals(LiveMetrics.Level.DANGER, LiveMetrics.level(0x05, 115.0))
        assertEquals(LiveMetrics.Level.NORMAL, LiveMetrics.level(0x05, null))
        assertEquals(LiveMetrics.Level.NORMAL, LiveMetrics.level(0x0C, 4000.0))
    }
}
