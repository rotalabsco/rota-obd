package co.rotalabs.obd.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun selecaoDoAoVivo() {
        // PIDs que a Pajero 4M41 informou no primeiro teste.
        val pajero = listOf(0x01, 0x04, 0x05, 0x0B, 0x0C, 0x0D, 0x0F, 0x10, 0x11, 0x1C, 0x1F, 0x21, 0x23, 0x2C,
            0x2D, 0x30, 0x31, 0x33, 0x3C, 0x42, 0x45, 0x49, 0x4A, 0x4C, 0x4D, 0x4E)
        val available = LiveSelection.available(pajero)
        assertEquals(
            listOf(0x0D, 0x0C, LiveSelection.BOOST, LiveSelection.CONSUMPTION, LiveSelection.TRIP_AVG, 0x05, 0x23),
            available.take(7),
        )
        assertTrue("sem fórmula não aparece", 0x01 !in available && 0x1C !in available)
        // Sem escolha salva: tudo marcado, para a sessão gravar todos os dados.
        assertEquals(available, LiveSelection.effective(null, available))
        // Escolha salva: só o que existe neste carro, na ordem de exibição.
        assertEquals(listOf(0x0D, LiveSelection.BOOST), LiveSelection.effective(setOf(LiveSelection.BOOST, 0x5E, 0x0D), available))
        // O turbo pede MAP e barométrica; não pede um PID 0x100.
        assertEquals(listOf(0x0C, 0x0B, 0x33), LiveSelection.pidsToRead(listOf(0x0C, LiveSelection.BOOST)))
        // Escolha salva vazia continua vazia (o usuário desmarcou tudo).
        assertEquals(emptyList<Int>(), LiveSelection.effective(emptySet(), available))
        assertEquals(setOf(0x0D, LiveSelection.BOOST), LiveSelection.decode(LiveSelection.encode(listOf(0x0D, LiveSelection.BOOST))))
        assertNull(LiveSelection.decode(null))
        assertEquals(0.85, LiveSelection.boostBar(186.0, 101.0)!!, 0.001)
        assertNull(LiveSelection.boostBar(186.0, null))
        assertEquals("0,85", Formato.leitura(0.85, "bar"))
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
