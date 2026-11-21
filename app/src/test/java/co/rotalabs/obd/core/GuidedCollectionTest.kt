package co.rotalabs.obd.core

import co.rotalabs.obd.core.GuidedCollection.Action
import co.rotalabs.obd.core.GuidedCollection.Cue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidedCollectionTest {
    private val cues = listOf(Cue(0, "P"), Cue(10, "R"), Cue(20, "N"))

    @Test
    fun instrucaoVigenteEProxima() {
        assertEquals("P", GuidedCollection.cueAt(cues, 0)?.text)
        assertEquals("P", GuidedCollection.cueAt(cues, 9)?.text)
        assertEquals("R", GuidedCollection.cueAt(cues, 10)?.text)
        assertEquals("N", GuidedCollection.cueAt(cues, 59)?.text)
        assertEquals("R", GuidedCollection.nextCue(cues, 3)?.text)
        assertNull(GuidedCollection.nextCue(cues, 20))
    }

    @Test
    fun passosConsistentes() {
        val steps = GuidedCollection.steps
        assertEquals("ids repetidos", steps.size, steps.map { it.id }.toSet().size)
        for (s in steps) {
            val a = s.action
            if (a is Action.Watch) {
                assertTrue(s.id, a.reads.isNotEmpty())
                assertTrue(s.id, a.cues.isNotEmpty() && a.cues.first().atSec == 0)
                assertEquals(s.id, a.cues.map { it.atSec }.sorted(), a.cues.map { it.atSec })
                a.durationSec?.let { d -> assertTrue(s.id, a.cues.all { it.atSec < d }) }
                for (r in a.reads) assertTrue(r.hex, r.hex.matches(Regex("(21|01)[0-9A-F]{2}")))
            }
        }
        // Só a gravação da viagem não tem fim marcado.
        assertEquals(listOf("viagem"), steps.filter { (it.action as? Action.Watch)?.durationSec == null && it.action is Action.Watch }.map { it.id })
    }

    @Test
    fun proximoPassoPendente() {
        val ids = GuidedCollection.steps.map { it.id }
        assertEquals(ids[0], GuidedCollection.nextPending(null, emptySet())?.id)
        assertEquals(ids[2], GuidedCollection.nextPending(ids[0], setOf(ids[0], ids[1]))?.id)
        // Volta ao começo quando o que falta ficou para trás.
        assertEquals(ids[0], GuidedCollection.nextPending(ids.last(), ids.drop(1).toSet())?.id)
        assertNull(GuidedCollection.nextPending(ids[3], ids.toSet()))
        assertFalse(ids.isEmpty())
    }
}
