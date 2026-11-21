package co.rotalabs.obd.core

/**
 * Quais dados aparecem no Ao vivo.
 *
 * O usuário marca o que quer ver; o app lê só esses PIDs (mais os necessários para os valores
 * calculados), então menos itens marcados significa atualização mais rápida.
 */
object LiveSelection {

    /** Valor calculado, sem PID próprio: pressão do turbo = MAP (0B) − barométrica (33). */
    const val BOOST = 0x100

    data class Derived(val id: Int, val name: String, val unit: String, val needs: List<Int>, val note: String)

    val derived: Map<Int, Derived> = listOf(
        Derived(BOOST, "Pressão do turbo", "bar", listOf(0x0B, 0x33), "MAP − barométrica"),
    ).associateBy { it.id }

    /**
     * Ordem de exibição. Os três primeiros marcados ficam grandes no topo. Pensada para diesel:
     * velocidade, rotação e turbo primeiro; depois o que serve de alerta.
     */
    private val preferredOrder = listOf(
        0x0D, 0x0C, BOOST, 0x5E, 0x05, 0x23, 0x5C, 0x0F, 0x04, 0x42, 0x49, 0x2C, 0x2F,
    )

    fun name(id: Int): String = derived[id]?.name ?: ObdPids.defs[id]?.name ?: "PID %02X".format(id)
    fun unit(id: Int): String = derived[id]?.unit ?: ObdPids.defs[id]?.unit ?: ""

    /** Tudo o que este carro permite mostrar, na ordem de exibição. */
    fun available(supported: Collection<Int>): List<Int> {
        val readable = supported.filter { it in ObdPids.defs }.toSet()
        val calc = derived.values.filter { d -> d.needs.all { it in readable } }.map { it.id }
        return order(readable + calc)
    }

    fun order(ids: Collection<Int>): List<Int> =
        ids.distinct().sortedWith(compareBy({ preferredOrder.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it }))

    /**
     * Seleção efetiva: a salva (só o que existe neste carro) ou, sem escolha, tudo marcado. Na fase
     * de descoberta a sessão gravada precisa de todos os dados; o usuário desmarca o que não quer.
     */
    fun effective(saved: Set<Int>?, available: List<Int>): List<Int> =
        if (saved == null) available else available.filter { it in saved }

    /** PIDs a pedir ao motor para mostrar a seleção. */
    fun pidsToRead(selected: List<Int>): List<Int> =
        selected.flatMap { derived[it]?.needs ?: listOf(it) }.distinct()

    /** Pressão do turbo em bar (relativa à atmosfera). */
    fun boostBar(mapKpa: Double?, baroKpa: Double?): Double? =
        if (mapKpa == null || baroKpa == null) null else (mapKpa - baroKpa) / 100.0

    fun encode(ids: Collection<Int>): String = ids.joinToString(",") { "%X".format(it) }

    fun decode(text: String?): Set<Int>? {
        if (text == null) return null
        return text.split(',').mapNotNull { it.trim().takeIf(String::isNotEmpty)?.toIntOrNull(16) }.toSet()
    }
}
