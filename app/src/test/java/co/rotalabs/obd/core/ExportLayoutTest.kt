package co.rotalabs.obd.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportLayoutTest {
    private fun line(kind: String, extra: Map<String, Any?> = emptyMap()) =
        Json.obj(mapOf("t_ms" to 1, "ts" to 1795271500621L, "kind" to kind) + extra)

    private fun start(step: String) = line("coleta", mapOf("event" to "inicio", "step" to step, "title" to "x"))
    private fun end(step: String, status: String = "concluído") = line("coleta", mapOf("event" to "fim", "step" to step, "status" to status))

    @Test
    fun pastasPorCategoria() {
        assertEquals("garagem/garagem.json", ExportLayout.entryFor("garagem.json"))
        assertEquals("garagem/vencimentos.json", ExportLayout.entryFor("vencimentos.json"))
        assertEquals("sessoes-completas/sessao_2026-11-21_11-31-40.ndjson", ExportLayout.entryFor("sessao_2026-11-21_11-31-40.ndjson"))
        assertEquals("descobertas/relatorio_1795271599745.md", ExportLayout.entryFor("relatorio_1795271599745.md"))
        assertEquals(
            "coleta/01-d1-ignicao/relatorio_1795271599745_d1-ignicao.md",
            ExportLayout.entryFor(ExportLayout.guidedReportName(1795271599745, "d1-ignicao")),
        )
        assertEquals("coleta/xx-passo-antigo", ExportLayout.stepFolder("passo-antigo"))
    }

    @Test
    fun marcador() {
        val m = ExportLayout.marker(end("viagem", "gravado"))!!
        assertEquals("fim", m.event)
        assertEquals("viagem", m.stepId)
        assertEquals("gravado", m.status)
        assertEquals(1795271500621L, m.ts)
        assertNull(ExportLayout.marker(line("lid_watch", mapOf("req" to "2105"))))
    }

    @Test
    fun recortePorPasso() {
        val lines = listOf(
            line("session"),
            start("cambio-alavanca-a"),
            line("lid_watch", mapOf("req" to "2105")),
            end("cambio-alavanca-a"),
            line("pid", mapOf("req" to "010C")),
            start("viagem"),
            line("lid_watch", mapOf("req" to "2101")),
            // app fechado no meio: sem marcador de fim
        )
        val parts = ExportLayout.splitByStep(lines.asSequence())
        assertEquals(listOf("cambio-alavanca-a", "viagem"), parts.keys.toList())
        assertEquals(3, parts.getValue("cambio-alavanca-a").size)
        assertTrue(parts.getValue("cambio-alavanca-a").none { it.contains("010C") })
        assertEquals(2, parts.getValue("viagem").size)
    }

    @Test
    fun leiaMe() {
        val text = ExportLayout.readme(listOf(ExportLayout.marker(end("d1-ignicao"))!!))
        assertTrue(text.contains("coleta/01-d1-ignicao: Ignição ligada, motor desligado (concluído)"))
        assertTrue(ExportLayout.readme(emptyList()).contains("Nenhum passo"))
    }
}
