package co.rotalabs.obd.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    @Test
    fun parsesSingleFrameWithSpacesAndEcho() {
        val r = ElmParser.parse("010C\r41 0C 1A F8\r\r", "010C")
        assertTrue(r is ObdResult.Ok)
        assertEquals("410C1AF8", (r as ObdResult.Ok).hex)
        assertEquals(1726.0, ObdPids.decode(0x0C, r.bytes)!!, 0.01)
    }

    @Test
    fun parsesMultiFrame() {
        val raw = "014\r0: 49 02 01 4A 4D 59\r1: 4C 59 56 39 38 57 45\r2: 4A 41 30 31 30 33 30\r"
        val r = ElmParser.parse(raw, "0902") as ObdResult.Ok
        assertEquals(20, r.bytes.size)
        assertEquals("4902014A4D59", r.hex.take(12))
    }

    @Test
    fun negativeAndNoData() {
        val n = ElmParser.parse("7F 21 31\r", "2105")
        assertTrue(n is ObdResult.Negative && n.nrc == 0x31)
        assertTrue(ElmParser.parse("NO DATA\r", "2105") is ObdResult.NoData)
        assertTrue(ElmParser.parse("SEARCHING...\rUNABLE TO CONNECT\r", "0100") is ObdResult.Error)
        assertTrue(ElmParser.parse(null) is ObdResult.Timeout)
    }

    @Test
    fun skipsResponsePending() {
        val r = ElmParser.parse("7F2178\r6101AABB\r", "2101")
        assertTrue(r is ObdResult.Ok)
        assertEquals("6101AABB", (r as ObdResult.Ok).hex)
    }

    @Test
    fun decodesDtcsAndBitmap() {
        assertEquals(listOf("P0401", "P0234"), Dtc.decode(intArrayOf(0x43, 0x02, 0x04, 0x01, 0x02, 0x34)))
        assertEquals(listOf("U0100"), Dtc.decode(intArrayOf(0x43, 0xC1, 0x00)))
        assertEquals(emptyList<String>(), Dtc.decode(intArrayOf(0x43, 0x00)))
        val pids = ObdPids.decodeBitmap(0x00, intArrayOf(0xBE, 0x3E, 0xB8, 0x11))
        assertTrue(0x01 in pids && 0x0C in pids && 0x0D in pids && 0x20 in pids)
    }

    @Test
    fun describesDtcs() {
        // Código real da Pajero 4M41, apagado no primeiro teste no carro.
        val p2426 = DtcCatalog.describe("P2426")
        assertEquals("Circuito de controle da válvula de arrefecimento da EGR com sinal baixo", p2426.title)
        assertEquals("Motor e câmbio", p2426.system)
        assertFalse(p2426.manufacturer)
        assertTrue(DtcCatalog.describe("p0401").title!!.contains("EGR"))
        // Código do fabricante: sem descrição inventada, só a estrutura.
        val p1 = DtcCatalog.describe("P1A23")
        assertNull(p1.title)
        assertTrue(p1.manufacturer)
        assertTrue(p1.summary().contains("fabricante"))
        // Genérico fora da tabela: informa o grupo pela norma.
        val unknown = DtcCatalog.describe("P0499")
        assertNull(unknown.title)
        assertFalse(unknown.manufacturer)
        assertTrue(unknown.summary().contains("emissões"))
        assertEquals("Rede de comunicação", DtcCatalog.describe("U0100").system)
        assertTrue(DtcCatalog.describe("U1100").manufacturer)
        assertNull(DtcCatalog.describe("XYZ").title)
        assertEquals("sistema híbrido", DtcCatalog.describe("P0A80").group)
        assertNull("P2Axx não é do grupo híbrido", DtcCatalog.describe("P2A00").group)
    }

    @Test
    fun discoveryAgainstSimulator() = runBlocking {
        val elm = Elm327(ElmExchanger(SimulatedLink(latencyMs = 0)))
        val info = elm.initialize()
        assertEquals("6", info.protocol)
        val reports = Discovery(elm, NoopRecorder) { _, _ -> }.run()
        val engine = reports.first { it.module == Modules.ENGINE }
        assertTrue(0x0C in engine.supportedPids)
        assertEquals("JMYLYV98WEJA00000", engine.info["VIN"])
        assertEquals(listOf("P0401"), engine.dtcs.first { it.mode == "03" }.codes)
        val ok = engine.lids.filter { it.result is ObdResult.Ok }.map { it.lid }
        assertEquals(listOf(0x01, 0x02, 0x03, 0x10, 0x11), ok)
        val trans = reports.first { it.module == Modules.TRANSMISSION }
        assertEquals(listOf(0x01, 0x30), trans.lids.filter { it.result is ObdResult.Ok }.map { it.lid })
        val text = Discovery.textReport(info, reports, "versão 0.1.12 (build 12)")
        assertTrue(text.contains("App: versão 0.1.12 (build 12)"))
        assertTrue(text.contains("2101"))
        assertTrue(text.contains("P0401 Fluxo da EGR insuficiente"))
    }

    @Test
    fun readsTestRelease() {
        // Formato real de GET /repos/rotalabsco/rota-obd/releases/tags/latest (campos usados).
        val json = """{"name":"Versão de teste #7","published_at":"2026-11-21T15:10:00Z","assets":[
            {"name":"outro.txt","size":10,"browser_download_url":"https://github.com/x/outro.txt"},
            {"name":"rota-obd-debug.apk","size":9529060,"browser_download_url":"https://github.com/rotalabsco/rota-obd/releases/download/latest/rota-obd-debug.apk"}]}"""
        val r = UpdateCheck.parse(json)!!
        assertEquals(7, r.build)
        assertEquals(9529060L, r.sizeBytes)
        assertTrue(r.apkUrl.endsWith("/rota-obd-debug.apk"))
        assertTrue(UpdateCheck.isNewer(r, 3))
        assertFalse(UpdateCheck.isNewer(r, 7))
        assertNull("sem número de build", UpdateCheck.parse("""{"name":"Versão de teste","assets":[]}"""))
        assertNull("sem APK", UpdateCheck.parse("""{"name":"Versão de teste #8","assets":[]}"""))
        assertNull("só https", UpdateCheck.parse(json.replace("https://github.com/rotalabsco", "http://github.com/rotalabsco")))
    }

    @Test
    fun jsonEscapes() {
        assertEquals("{\"a\":\"x\\\"y\",\"b\":1.5,\"c\":null}", Json.obj(mapOf("a" to "x\"y", "b" to 1.5, "c" to null)))
    }
}
