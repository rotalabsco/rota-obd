package co.rotalabs.obd.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SleepAndDeadlinesTest {
    private val min = 60_000L
    private val day = 86_400_000L

    @Test
    fun parsesAdapterVoltage() {
        assertEquals(12.6, SleepPolicy.parseVoltage("12.6V")!!, 1e-9)
        assertEquals(14.1, SleepPolicy.parseVoltage("ATRV\r14.1 V\r")!!, 1e-9)
        assertEquals(null, SleepPolicy.parseVoltage("?"))
        assertEquals(null, SleepPolicy.parseVoltage(null))
    }

    @Test
    fun goesQuietThenReleasesWhenEngineOff() {
        val p = SleepPolicy(startMillis = 0)
        assertEquals(SleepPolicy.Mode.ACTIVE, p.observe(0, 14.2))
        assertEquals(SleepPolicy.Mode.ACTIVE, p.observe(1 * min, 12.5))
        assertEquals(SleepPolicy.Mode.QUIET, p.observe(3 * min, 12.5))
        // Motor ligou de novo: volta a ativo.
        assertEquals(SleepPolicy.Mode.ACTIVE, p.observe(4 * min, 13.9))
        assertEquals(SleepPolicy.Mode.QUIET, p.observe(7 * min, 12.4))
        assertEquals(SleepPolicy.Mode.RELEASE, p.observe(11 * min, 12.4))
    }

    @Test
    fun userActionKeepsItActive() {
        val p = SleepPolicy(startMillis = 0)
        p.observe(0, 12.4)
        p.touch(5 * min)
        assertEquals(SleepPolicy.Mode.ACTIVE, p.observe(6 * min, 12.4))
        assertEquals(SleepPolicy.Mode.QUIET, p.observe(8 * min, 12.4))
    }

    @Test
    fun releasesWhenAdapterStopsAnswering() {
        val p = SleepPolicy(startMillis = 0)
        assertEquals(SleepPolicy.Mode.ACTIVE, p.observe(0, null))
        assertEquals(SleepPolicy.Mode.ACTIVE, p.observe(1, null))
        assertEquals(SleepPolicy.Mode.RELEASE, p.observe(2, null))
    }

    @Test
    fun deadlineStatesAndRenewal() {
        val now = 1_767_225_600_000L // 01/01/2026 00:00 UTC
        val ipva = Deadline(name = "IPVA", dueMillis = now + 10 * day, repeatMonths = 12)
        val seguro = Deadline(name = "Seguro", dueMillis = now + 60 * day)
        val cnh = Deadline(name = "CNH", dueMillis = now - 2 * day, repeatMonths = null)
        val sem = Deadline(name = "Garantia")
        val st = Deadlines.statuses(listOf(seguro, sem, ipva, cnh), now)
        assertEquals(listOf("CNH", "IPVA", "Seguro", "Garantia"), st.map { it.deadline.name })
        assertEquals(DueState.OVERDUE, st[0].state)
        assertEquals(DueState.SOON, st[1].state)
        assertEquals(DueState.OK, st[2].state)
        assertEquals(DueState.NO_INTERVAL, st[3].state)

        val renewed = Deadlines.renew(ipva)
        assertTrue(renewed.dueMillis!! - ipva.dueMillis!! in 364 * day..366 * day)
        assertEquals(null, Deadlines.renew(cnh).dueMillis)
    }

    @Test
    fun deadlinesRoundTrip() {
        val list = listOf(Deadline(name = "IPVA", dueMillis = 123, amount = 1500.5, note = "cota única"))
        assertEquals(list, Deadlines.fromJson(Deadlines.toJson(list)))
        val dir = File(System.getProperty("java.io.tmpdir"), "dl-test-" + System.nanoTime()).apply { mkdirs() }
        val store = DeadlineStore(File(dir, "vencimentos.json"))
        assertEquals(Deadlines.defaults().map { it.name }, store.load().map { it.name })
        store.save(list)
        assertEquals(list, store.load())
        dir.deleteRecursively()
    }
}
