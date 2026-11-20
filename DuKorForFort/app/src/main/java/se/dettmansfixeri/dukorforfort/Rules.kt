package se.dettmansfixeri.dukorforfort

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.MonthDay

/**
 * En avvikande hastighet från ISA-datan, t.ex. "30 km/h vardagar 07–17".
 * Se verktyg/konvertera-isa.mjs för formatet.
 */
class Deviation(
    val kmh: Int,
    private val dagslag: String?,
    private val clock: List<IntArray>,
    private val dateFrom: MonthDay?,
    private val dateTo: MonthDay?,
    private val dayFrom: DayOfWeek?,
    private val dayTo: DayOfWeek?,
    private val vehicles: Set<Int>?,
    private val vehiclesExcluded: Boolean,
    private val overTon: Double?,
) {
    /** Avvikelser helt utan villkor vet vi inte när de gäller – de ignoreras. */
    private val hasCondition = dagslag != null || clock.isNotEmpty() || dateFrom != null ||
            dayFrom != null || vehicles != null || overTon != null

    fun applies(now: LocalDateTime, vehicle: Vehicle, trailer: Boolean): Boolean {
        if (!hasCondition) return false
        if (vehicles != null) {
            val hit = vehicle.codes(trailer).any { it in vehicles }
            if (vehiclesExcluded == hit) return false
        }
        if (overTon != null && !vehicle.heavy) return false

        val date = now.toLocalDate()
        if (dateFrom != null && dateTo != null) {
            val md = MonthDay.from(date)
            val inside = if (!dateTo.isBefore(dateFrom)) !md.isBefore(dateFrom) && !md.isAfter(dateTo)
            else !md.isBefore(dateFrom) || !md.isAfter(dateTo)
            if (!inside) return false
        }
        if (dayFrom != null) {
            val d = date.dayOfWeek.value
            val a = dayFrom.value
            val b = (dayTo ?: dayFrom).value
            val inside = if (a <= b) d in a..b else d >= a || d <= b
            if (!inside) return false
        }
        if (dagslag != null) {
            val holiday = SwedishHolidays.isSundayOrHoliday(date)
            val beforeHoliday = SwedishHolidays.isSundayOrHoliday(date.plusDays(1))
            val ok = when (dagslag) {
                "V" -> !holiday
                "VUF" -> !holiday && !beforeHoliday
                "VF" -> !holiday && beforeHoliday
                "S" -> holiday
                else -> true
            }
            if (!ok) return false
        }
        if (clock.isNotEmpty()) {
            val hhmm = now.hour * 100 + now.minute
            val inside = clock.any { (start, end) ->
                when {
                    start == end -> true
                    start < end -> hhmm in start until end
                    else -> hhmm >= start || hhmm < end // över midnatt
                }
            }
            if (!inside) return false
        }
        return true
    }

    companion object {
        private val DAYS = mapOf(
            "måndag" to DayOfWeek.MONDAY, "tisdag" to DayOfWeek.TUESDAY, "onsdag" to DayOfWeek.WEDNESDAY,
            "torsdag" to DayOfWeek.THURSDAY, "fredag" to DayOfWeek.FRIDAY, "lördag" to DayOfWeek.SATURDAY,
            "söndag" to DayOfWeek.SUNDAY,
        )

        private fun monthDay(s: String?): MonthDay? =
            if (s == null || s.length != 4) null
            else runCatching { MonthDay.of(s.substring(0, 2).toInt(), s.substring(2, 4).toInt()) }.getOrNull()

        fun parseAll(json: String): List<List<Deviation>> {
            val arr = JSONArray(json)
            return List(arr.length()) { i ->
                val list = arr.getJSONArray(i)
                List(list.length()) { j -> parse(list.getJSONObject(j)) }
            }
        }

        private fun parse(o: JSONObject): Deviation {
            val clock = mutableListOf<IntArray>()
            o.optJSONArray("k")?.let { k ->
                for (i in 0 until k.length()) {
                    val p = k.getJSONArray(i)
                    clock += intArrayOf(p.getInt(0), p.getInt(1))
                }
            }
            val w = o.optJSONArray("w")
            val f = o.optJSONArray("f")
            return Deviation(
                kmh = o.getInt("v"),
                dagslag = o.optString("d").ifEmpty { null },
                clock = clock,
                dateFrom = monthDay(o.optString("s").ifEmpty { null }),
                dateTo = monthDay(o.optString("e").ifEmpty { null }),
                dayFrom = w?.let { DAYS[it.optString(0)] },
                dayTo = w?.let { DAYS[it.optString(1)] },
                vehicles = f?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() },
                vehiclesExcluded = o.optInt("fi", 2) == 1,
                overTon = if (o.has("tv")) o.getDouble("tv") else null,
            )
        }
    }
}

/** Svenska allmänna helgdagar (sön- och helgdagar enligt lagen om allmänna helgdagar). */
object SwedishHolidays {
    private val cache = HashMap<Int, Set<LocalDate>>()

    fun isSundayOrHoliday(d: LocalDate): Boolean =
        d.dayOfWeek == DayOfWeek.SUNDAY || d in holidays(d.year)

    private fun holidays(year: Int): Set<LocalDate> = cache.getOrPut(year) {
        val easter = easterSunday(year)
        fun firstSaturdayFrom(month: Int, day: Int): LocalDate {
            var d = LocalDate.of(year, month, day)
            while (d.dayOfWeek != DayOfWeek.SATURDAY) d = d.plusDays(1)
            return d
        }
        setOf(
            LocalDate.of(year, 1, 1),              // nyårsdagen
            LocalDate.of(year, 1, 6),              // trettondedag jul
            easter.minusDays(2),                   // långfredagen
            easter,                                // påskdagen
            easter.plusDays(1),                    // annandag påsk
            LocalDate.of(year, 5, 1),              // första maj
            easter.plusDays(39),                   // Kristi himmelsfärdsdag
            easter.plusDays(49),                   // pingstdagen
            LocalDate.of(year, 6, 6),              // nationaldagen
            firstSaturdayFrom(6, 20),              // midsommardagen
            firstSaturdayFrom(10, 31),             // alla helgons dag
            LocalDate.of(year, 12, 25),            // juldagen
            LocalDate.of(year, 12, 26),            // annandag jul
        )
    }

    /** Påskdagen (gregoriansk, "anonymous algorithm"). */
    private fun easterSunday(y: Int): LocalDate {
        val a = y % 19
        val b = y / 100
        val c = y % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(y, month, day)
    }
}
