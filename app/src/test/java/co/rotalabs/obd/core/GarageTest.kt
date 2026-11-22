package co.rotalabs.obd.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GarageTest {
    private val day = 86_400_000L

    @Test
    fun consumptionBetweenFullTanks() {
        val entries = listOf(
            FuelEntry(dateMillis = 0, odometerKm = 10_000.0, liters = 60.0, totalPrice = 360.0, fullTank = true),
            FuelEntry(dateMillis = day, odometerKm = 10_300.0, liters = 20.0, totalPrice = 120.0, fullTank = false),
            FuelEntry(dateMillis = 2 * day, odometerKm = 10_800.0, liters = 50.0, totalPrice = 300.0, fullTank = true),
            FuelEntry(dateMillis = 3 * day, odometerKm = 11_400.0, liters = 60.0, totalPrice = 360.0, fullTank = true),
        )
        val s = Garage.fuelSummary(entries)
        assertEquals(2, s.segments.size)
        // 800 km com 70 L; depois 600 km com 60 L
        assertEquals(800.0 / 70.0, s.segments[0].kmPerLiter, 1e-9)
        assertEquals(10.0, s.lastKmPerLiter!!, 1e-9)
        assertEquals(1400.0 / 130.0, s.averageKmPerLiter!!, 1e-9)
        assertEquals(780.0 / 1400.0, s.averageCostPerKm!!, 1e-9)
        assertEquals(1140.0, s.totalSpent, 1e-9)
    }

    @Test
    fun maintenanceDueStates() {
        val oil = MaintenanceItem(id = "oil", name = "Óleo", intervalKm = 10_000.0, intervalMonths = 12)
        val air = MaintenanceItem(id = "air", name = "Filtro de ar", intervalKm = 20_000.0)
        val atf = MaintenanceItem(id = "atf", name = "Fluido do câmbio")
        val now = 400 * day
        val d = GarageData(
            vehicle = Vehicle(initialOdometerKm = 50_000.0),
            items = listOf(oil, air, atf),
            fuel = listOf(FuelEntry(dateMillis = now, odometerKm = 59_500.0, liters = 50.0)),
            records = listOf(
                MaintenanceRecord(itemId = "oil", dateMillis = now - 100 * day, odometerKm = 50_000.0),
                MaintenanceRecord(itemId = "air", dateMillis = now - 10 * day, odometerKm = 59_000.0),
            ),
        )
        val st = Garage.maintenanceStatus(d, now).associateBy { it.item.id }
        assertEquals(DueState.SOON, st["oil"]!!.state)
        assertEquals(500.0, st["oil"]!!.remainingKm!!, 1e-9)
        assertEquals(DueState.OK, st["air"]!!.state)
        assertEquals(DueState.NO_INTERVAL, st["atf"]!!.state)
    }

    @Test
    fun jsonRoundTripAndStore() {
        val d = GarageData(
            vehicle = Vehicle(name = "Pajero \"Full\"", vin = "JMYLYV98WEJA00000", tankLiters = 88.0),
            fuel = listOf(FuelEntry(id = "a1", dateMillis = 1_700_000_000_000, odometerKm = 123_456.7, liters = 60.5, totalPrice = null, station = "Posto\nCentro")),
            records = listOf(MaintenanceRecord(itemId = null, dateMillis = 5, odometerKm = null, description = "Revisão")),
            lastFuelLevelPct = 42.5,
        )
        val back = Garage.fromJson(Garage.toJson(d))
        assertEquals(d.vehicle, back.vehicle)
        assertEquals(d.fuel, back.fuel)
        assertEquals(d.items.map { it.name }, back.items.map { it.name })
        assertEquals(d.records.single().description, back.records.single().description)
        assertEquals(42.5, back.lastFuelLevelPct!!, 1e-9)

        val dir = File(System.getProperty("java.io.tmpdir"), "garage-test-" + System.nanoTime()).apply { mkdirs() }
        val store = GarageStore(File(dir, "garagem.json"))
        store.save(d)
        assertEquals(d.fuel, store.load().fuel)
        File(dir, "garagem.json").writeText("{quebrado")
        assertEquals(GarageData().vehicle, store.load().vehicle)
        assertTrue(dir.listFiles()!!.any { it.name.contains("corrompido") })
        dir.deleteRecursively()
    }

    @Test
    fun odometerFromEcuCounterAndDashboardOffset() {
        // Pajero em 22/11/2026: central 234.174 km, painel 235.715 km.
        val noOffset = GarageData(vehicle = Vehicle(initialOdometerKm = 230_000.0, ecuOdometerKm = 234_174.0))
        assertEquals(null, noOffset.vehicle.dashboardOdometerKm)
        assertEquals(230_000.0, Garage.currentOdometer(noOffset)!!, 1e-9)

        val withOffset = noOffset.copy(vehicle = noOffset.vehicle.copy(odometerOffsetKm = 1_541.0))
        assertEquals(235_715.0, Garage.currentOdometer(withOffset)!!, 1e-9)
        val later = withOffset.copy(vehicle = withOffset.vehicle.copy(ecuOdometerKm = 234_200.0))
        assertEquals(235_741.0, Garage.currentOdometer(later)!!, 1e-9)
        assertEquals(later.vehicle, Garage.fromJson(Garage.toJson(later)).vehicle)
    }

    @Test
    fun parsesBrazilianNumbers() {
        assertEquals(1234.5, Garage.parseNumber("1.234,5")!!, 1e-9)
        assertEquals(10000.0, Garage.parseNumber("10.000")!!, 1e-9)
        assertEquals(60.25, Garage.parseNumber("60,25")!!, 1e-9)
        assertEquals(1234.5, Garage.parseNumber("1234.5")!!, 1e-9)
        assertEquals(350.0, Garage.parseNumber("R$ 350,00")!!, 1e-9)
        assertEquals(null, Garage.parseNumber(""))
    }

    @Test
    fun refuelDetection() {
        assertTrue(Garage.looksLikeRefuel(20.0, 95.0))
        assertTrue(!Garage.looksLikeRefuel(50.0, 55.0))
        assertTrue(!Garage.looksLikeRefuel(null, 90.0))
    }
}
