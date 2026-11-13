package io.github.obdlivre.core

import java.io.File

/** Vencimento do carro ou do motorista: IPVA, licenciamento, seguro, CNH, garantias. */
data class Deadline(
    val id: String = newId(),
    val name: String,
    val dueMillis: Long? = null,
    /** Repete a cada N meses ao marcar como pago/renovado (12 = anual). Nulo = não repete. */
    val repeatMonths: Int? = 12,
    val warnDaysBefore: Int = 15,
    val amount: Double? = null,
    val note: String = "",
)

data class DeadlineStatus(val deadline: Deadline, val daysLeft: Long?, val state: DueState)

object Deadlines {
    fun defaults(): List<Deadline> = listOf(
        Deadline(name = "IPVA"),
        Deadline(name = "Licenciamento"),
        Deadline(name = "Seguro"),
        Deadline(name = "CNH", repeatMonths = null, warnDaysBefore = 45),
    )

    fun status(d: Deadline, nowMillis: Long = System.currentTimeMillis()): DeadlineStatus {
        val due = d.dueMillis ?: return DeadlineStatus(d, null, DueState.NO_INTERVAL)
        val days = Math.floorDiv(due - startOfDay(nowMillis), 86_400_000L)
        val state = when {
            days < 0 -> DueState.OVERDUE
            days <= d.warnDaysBefore -> DueState.SOON
            else -> DueState.OK
        }
        return DeadlineStatus(d, days, state)
    }

    fun statuses(list: List<Deadline>, nowMillis: Long = System.currentTimeMillis()): List<DeadlineStatus> =
        list.map { status(it, nowMillis) }.sortedWith(
            compareBy<DeadlineStatus> { listOf(2, 1, 0, 3, 4)[it.state.ordinal] }.thenBy { it.daysLeft ?: Long.MAX_VALUE },
        )

    /** Marca como pago/renovado: avança o vencimento pela recorrência, ou limpa a data se não repete. */
    fun renew(d: Deadline): Deadline {
        val due = d.dueMillis ?: return d
        return d.copy(dueMillis = d.repeatMonths?.let { Garage.addMonths(due, it) })
    }

    private fun startOfDay(millis: Long): Long =
        java.util.Calendar.getInstance().apply {
            timeInMillis = millis
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

    fun toJson(list: List<Deadline>): String = Json.obj(
        mapOf(
            "version" to 1,
            "deadlines" to list.map {
                mapOf(
                    "id" to it.id, "name" to it.name, "due" to it.dueMillis, "repeatMonths" to it.repeatMonths,
                    "warnDaysBefore" to it.warnDaysBefore, "amount" to it.amount, "note" to it.note,
                )
            },
        ),
    )

    @Suppress("UNCHECKED_CAST")
    fun fromJson(text: String): List<Deadline> {
        val root = MiniJson.parse(text) as Map<String, Any?>
        return (root["deadlines"] as? List<Any?>).orEmpty().filterIsInstance<Map<String, Any?>>().map {
            Deadline(
                id = (it["id"] as? String).orEmpty().ifEmpty { newId() },
                name = it["name"] as? String ?: "",
                dueMillis = (it["due"] as? Number)?.toLong(),
                repeatMonths = (it["repeatMonths"] as? Number)?.toInt(),
                warnDaysBefore = (it["warnDaysBefore"] as? Number)?.toInt() ?: 15,
                amount = (it["amount"] as? Number)?.toDouble(),
                note = it["note"] as? String ?: "",
            )
        }
    }
}

class DeadlineStore(private val file: File) {
    fun load(): List<Deadline> {
        if (!file.exists()) return Deadlines.defaults()
        return try {
            Deadlines.fromJson(file.readText())
        } catch (e: Exception) {
            file.copyTo(File(file.parentFile, file.name + ".corrompido-" + System.currentTimeMillis()), overwrite = true)
            Deadlines.defaults()
        }
    }

    @Synchronized
    fun save(list: List<Deadline>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(Deadlines.toJson(list))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
