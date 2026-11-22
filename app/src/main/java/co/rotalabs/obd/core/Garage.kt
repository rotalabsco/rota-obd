package co.rotalabs.obd.core

import java.io.File
import java.util.Calendar
import java.util.UUID

/* Garagem: carro, abastecimentos e manutenções. Tudo guardado num arquivo JSON simples. */

data class Vehicle(
    val name: String = "Meu carro",
    val vin: String = "",
    val calibration: String = "",
    val plate: String = "",
    val fuel: String = "Diesel",
    val tankLiters: Double? = null,
    val initialOdometerKm: Double? = null,
    /**
     * Último valor do contador de km da central do motor (só em carros com perfil, como a Pajero). Ele
     * acompanha a distância rodada, mas não bate com o painel: na Pajero ficou 1.541 km abaixo.
     */
    val ecuOdometerKm: Double? = null,
    /** Painel − contador da central, informado uma vez pelo dono. Com ele o hodômetro anda sozinho. */
    val odometerOffsetKm: Double? = null,
) {
    /** Hodômetro do painel calculado pela central: contador + diferença. Null sem os dois. */
    val dashboardOdometerKm: Double?
        get() = if (ecuOdometerKm != null && odometerOffsetKm != null) ecuOdometerKm + odometerOffsetKm else null
}

data class FuelEntry(
    val id: String = newId(),
    val dateMillis: Long,
    val odometerKm: Double,
    val liters: Double,
    val totalPrice: Double? = null,
    val fullTank: Boolean = true,
    val station: String = "",
    val note: String = "",
) {
    val pricePerLiter: Double? get() = totalPrice?.takeIf { liters > 0 }?.div(liters)
}

data class MaintenanceItem(
    val id: String = newId(),
    val name: String,
    val intervalKm: Double? = null,
    val intervalMonths: Int? = null,
    val note: String = "",
)

data class MaintenanceRecord(
    val id: String = newId(),
    val itemId: String?,
    val dateMillis: Long,
    val odometerKm: Double?,
    val cost: Double? = null,
    val description: String = "",
)

data class GarageData(
    val vehicle: Vehicle = Vehicle(),
    val fuel: List<FuelEntry> = emptyList(),
    val items: List<MaintenanceItem> = Garage.defaultItems(),
    val records: List<MaintenanceRecord> = emptyList(),
    /** Último nível de combustível lido pelo OBD (%), para perceber um abastecimento. */
    val lastFuelLevelPct: Double? = null,
    val lastFuelLevelAt: Long? = null,
)

fun newId(): String = UUID.randomUUID().toString().take(8)

/** Trecho entre dois tanques cheios: a única medida de consumo que não depende de estimativa. */
data class ConsumptionSegment(
    val fromMillis: Long,
    val toMillis: Long,
    val km: Double,
    val liters: Double,
    val cost: Double?,
) {
    val kmPerLiter: Double get() = km / liters
    val costPerKm: Double? get() = cost?.div(km)
}

data class FuelSummary(
    val segments: List<ConsumptionSegment>,
    val averageKmPerLiter: Double?,
    val lastKmPerLiter: Double?,
    val averageCostPerKm: Double?,
    val totalSpent: Double,
    val totalLiters: Double,
)

enum class DueState { OK, SOON, OVERDUE, NEVER_DONE, NO_INTERVAL }

data class MaintenanceStatus(
    val item: MaintenanceItem,
    val lastRecord: MaintenanceRecord?,
    val dueKm: Double?,
    val dueMillis: Long?,
    val remainingKm: Double?,
    val remainingDays: Long?,
    val state: DueState,
)

object Garage {
    /** Itens comuns, sem intervalo: o intervalo certo vem do manual do carro. */
    fun defaultItems(): List<MaintenanceItem> = listOf(
        "Óleo e filtro do motor",
        "Filtro de combustível",
        "Filtro de ar",
        "Filtro de cabine",
        "Fluido do câmbio automático",
        "Líquido de arrefecimento",
        "Pastilhas e discos de freio",
        "Fluido de freio",
        "Correias",
        "Pneus (rodízio e alinhamento)",
        "Bateria",
    ).map { MaintenanceItem(name = it) }

    fun currentOdometer(d: GarageData): Double? = listOfNotNull(
        d.vehicle.initialOdometerKm,
        d.vehicle.dashboardOdometerKm,
        d.fuel.maxOfOrNull { it.odometerKm },
        d.records.mapNotNull { it.odometerKm }.maxOrNull(),
    ).maxOrNull()

    fun fuelSummary(entries: List<FuelEntry>): FuelSummary {
        val sorted = entries.sortedWith(compareBy({ it.odometerKm }, { it.dateMillis }))
        val segments = mutableListOf<ConsumptionSegment>()
        var anchor: Int? = null
        for ((idx, e) in sorted.withIndex()) {
            if (!e.fullTank) continue
            val a = anchor
            if (a != null) {
                val between = sorted.subList(a + 1, idx + 1)
                val km = e.odometerKm - sorted[a].odometerKm
                val liters = between.sumOf { it.liters }
                val cost = if (between.all { it.totalPrice != null }) between.sumOf { it.totalPrice!! } else null
                if (km > 0 && liters > 0) {
                    segments.add(ConsumptionSegment(sorted[a].dateMillis, e.dateMillis, km, liters, cost))
                }
            }
            anchor = idx
        }
        val totalKm = segments.sumOf { it.km }
        val totalLiters = segments.sumOf { it.liters }
        val priced = segments.filter { it.cost != null }
        return FuelSummary(
            segments = segments,
            averageKmPerLiter = if (totalLiters > 0) totalKm / totalLiters else null,
            lastKmPerLiter = segments.lastOrNull()?.kmPerLiter,
            averageCostPerKm = if (priced.isNotEmpty()) priced.sumOf { it.cost!! } / priced.sumOf { it.km } else null,
            totalSpent = entries.sumOf { it.totalPrice ?: 0.0 },
            totalLiters = entries.sumOf { it.liters },
        )
    }

    fun maintenanceStatus(d: GarageData, nowMillis: Long = System.currentTimeMillis()): List<MaintenanceStatus> {
        val odo = currentOdometer(d)
        return d.items.map { item ->
            val last = d.records.filter { it.itemId == item.id }.maxByOrNull { it.dateMillis }
            if (item.intervalKm == null && item.intervalMonths == null) {
                return@map MaintenanceStatus(item, last, null, null, null, null, DueState.NO_INTERVAL)
            }
            if (last == null) {
                return@map MaintenanceStatus(item, null, null, null, null, null, DueState.NEVER_DONE)
            }
            val dueKm = item.intervalKm?.let { iv -> last.odometerKm?.plus(iv) }
            val dueMillis = item.intervalMonths?.let { addMonths(last.dateMillis, it) }
            val remKm = if (dueKm != null && odo != null) dueKm - odo else null
            val remDays = dueMillis?.let { (it - nowMillis) / 86_400_000L }
            val overdue = (remKm != null && remKm < 0) || (remDays != null && remDays < 0)
            val soonKm = item.intervalKm?.let { minOf(1000.0, it * 0.1) }
            val soon = (remKm != null && soonKm != null && remKm <= soonKm) || (remDays != null && remDays <= 30)
            val state = when {
                overdue -> DueState.OVERDUE
                soon -> DueState.SOON
                else -> DueState.OK
            }
            MaintenanceStatus(item, last, dueKm, dueMillis, remKm, remDays, state)
        }
    }

    /** Subida de pelo menos [thresholdPct] pontos no nível do tanque desde a última leitura. */
    fun looksLikeRefuel(previousPct: Double?, currentPct: Double?, thresholdPct: Double = 15.0): Boolean =
        previousPct != null && currentPct != null && currentPct - previousPct >= thresholdPct

    private val thousands = Regex("^-?\\d{1,3}(\\.\\d{3})+$")

    /** Número digitado em português: "1.234,5", "1234,5", "10.000" (milhar) e "1234.5". */
    fun parseNumber(s: String): Double? {
        val t = s.trim().replace(" ", "").removePrefix("R$")
        if (t.isEmpty()) return null
        val normalized = when {
            t.contains(',') -> t.replace(".", "").replace(',', '.')
            thousands.matches(t) -> t.replace(".", "")
            else -> t
        }
        return normalized.toDoubleOrNull()
    }

    fun addMonths(millis: Long, months: Int): Long =
        Calendar.getInstance().apply { timeInMillis = millis; add(Calendar.MONTH, months) }.timeInMillis

    // ---------- JSON ----------

    fun toJson(d: GarageData): String = Json.obj(
        mapOf(
            "version" to 1,
            "vehicle" to with(d.vehicle) {
                mapOf(
                    "name" to name, "vin" to vin, "calibration" to calibration, "plate" to plate, "fuel" to fuel,
                    "tankLiters" to tankLiters, "initialOdometerKm" to initialOdometerKm,
                    "ecuOdometerKm" to ecuOdometerKm, "odometerOffsetKm" to odometerOffsetKm,
                )
            },
            "fuel" to d.fuel.map {
                mapOf(
                    "id" to it.id, "date" to it.dateMillis, "odometerKm" to it.odometerKm, "liters" to it.liters,
                    "totalPrice" to it.totalPrice, "fullTank" to it.fullTank, "station" to it.station, "note" to it.note,
                )
            },
            "items" to d.items.map {
                mapOf(
                    "id" to it.id, "name" to it.name, "intervalKm" to it.intervalKm,
                    "intervalMonths" to it.intervalMonths, "note" to it.note,
                )
            },
            "records" to d.records.map {
                mapOf(
                    "id" to it.id, "itemId" to it.itemId, "date" to it.dateMillis, "odometerKm" to it.odometerKm,
                    "cost" to it.cost, "description" to it.description,
                )
            },
            "lastFuelLevelPct" to d.lastFuelLevelPct,
            "lastFuelLevelAt" to d.lastFuelLevelAt,
        ),
    )

    @Suppress("UNCHECKED_CAST")
    fun fromJson(text: String): GarageData {
        val root = MiniJson.parse(text) as Map<String, Any?>
        fun Map<String, Any?>.s(k: String) = this[k] as? String ?: ""
        fun Map<String, Any?>.d(k: String) = (this[k] as? Number)?.toDouble()
        fun Map<String, Any?>.l(k: String) = (this[k] as? Number)?.toLong()
        fun list(k: String) = (root[k] as? List<Any?>).orEmpty().filterIsInstance<Map<String, Any?>>()
        val v = root["vehicle"] as? Map<String, Any?> ?: emptyMap()
        return GarageData(
            vehicle = Vehicle(
                name = v.s("name").ifEmpty { "Meu carro" }, vin = v.s("vin"), calibration = v.s("calibration"),
                plate = v.s("plate"), fuel = v.s("fuel").ifEmpty { "Diesel" },
                tankLiters = v.d("tankLiters"), initialOdometerKm = v.d("initialOdometerKm"),
                ecuOdometerKm = v.d("ecuOdometerKm"), odometerOffsetKm = v.d("odometerOffsetKm"),
            ),
            fuel = list("fuel").map {
                FuelEntry(
                    id = it.s("id").ifEmpty { newId() }, dateMillis = it.l("date") ?: 0L,
                    odometerKm = it.d("odometerKm") ?: 0.0, liters = it.d("liters") ?: 0.0,
                    totalPrice = it.d("totalPrice"), fullTank = it["fullTank"] as? Boolean ?: true,
                    station = it.s("station"), note = it.s("note"),
                )
            },
            items = list("items").map {
                MaintenanceItem(
                    id = it.s("id").ifEmpty { newId() }, name = it.s("name"), intervalKm = it.d("intervalKm"),
                    intervalMonths = it.d("intervalMonths")?.toInt(), note = it.s("note"),
                )
            },
            records = list("records").map {
                MaintenanceRecord(
                    id = it.s("id").ifEmpty { newId() }, itemId = it["itemId"] as? String,
                    dateMillis = it.l("date") ?: 0L, odometerKm = it.d("odometerKm"), cost = it.d("cost"),
                    description = it.s("description"),
                )
            },
            lastFuelLevelPct = root.d("lastFuelLevelPct"),
            lastFuelLevelAt = root.l("lastFuelLevelAt"),
        )
    }
}

/** Guarda a garagem num arquivo, com gravação atômica e cópia do arquivo se ele estiver corrompido. */
class GarageStore(private val file: File) {
    fun load(): GarageData {
        if (!file.exists()) return GarageData()
        return try {
            Garage.fromJson(file.readText())
        } catch (e: Exception) {
            file.copyTo(File(file.parentFile, file.name + ".corrompido-" + System.currentTimeMillis()), overwrite = true)
            GarageData()
        }
    }

    @Synchronized
    fun save(d: GarageData) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(Garage.toJson(d))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
