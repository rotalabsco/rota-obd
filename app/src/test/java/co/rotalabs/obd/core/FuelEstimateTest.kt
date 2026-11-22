package co.rotalabs.obd.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FuelEstimateTest {
    @Test
    fun litrosPorHora() {
        // Marcha lenta da Pajero: 13 % de carga a 650 rpm, perto de 1,5 L/h com a calibração.
        assertEquals(1.24, FuelEstimate.litersPerHour(13.0, 650.0), 0.05)
        // Freio motor: carga 0, consumo 0.
        assertEquals(0.0, FuelEstimate.litersPerHour(0.0, 2800.0), 0.0)
    }

    @Test
    fun instantaneoKmPorLitroOuLitrosPorHora() {
        val (v, u) = FuelEstimate.instant(30.0, 1980.0, 90.0)!!
        assertEquals("km/L", u)
        assertEquals(10.4, v, 0.2)
        assertEquals("L/h", FuelEstimate.instant(13.0, 650.0, 0.0)!!.second)
        assertEquals(99.9 to "km/L", FuelEstimate.instant(0.0, 2500.0, 60.0))
        assertNull(FuelEstimate.instant(null, 650.0, 0.0))
    }

    @Test
    fun mediaDaViagem() {
        val t = FuelEstimate.Trip()
        var now = 0L
        // 90 km/h, 30 % de carga, 1980 rpm por 1 hora em passos de 1 s.
        repeat(3601) { t.add(30.0, 1980.0, 90.0, now); now += 1000 }
        assertEquals(90.0, t.km, 0.1)
        assertEquals(10.4, t.average()!!, 0.2)
        // Lacuna de 10 s (conexão caiu) não entra na conta.
        val km = t.km
        t.add(30.0, 1980.0, 90.0, now + 10_000)
        assertEquals(km, t.km, 0.0)
        // Mais de 30 min sem leitura: viagem nova.
        t.add(30.0, 1980.0, 90.0, now + 10_000 + FuelEstimate.NEW_TRIP_AFTER_MS)
        assertNull(t.average())
    }
}
