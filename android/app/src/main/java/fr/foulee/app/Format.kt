package fr.foulee.app

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

object Fmt {
    private val shortDate = DateTimeFormatter.ofPattern("EEE d MMM", Locale.FRANCE)
    private val longDate = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRANCE)
    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.FRANCE)
    private val monthName = DateTimeFormatter.ofPattern("MMM", Locale.FRANCE)

    fun num(v: Double, digits: Int = 1) = Insights.dec(v, digits)

    fun km(meters: Double?) = meters?.let { "${num(it / 1000)} km" } ?: "n/d"

    /** 3725 -> "1 h 02", 312 -> "5 min" */
    fun hoursMinutes(seconds: Double): String {
        val m = (seconds / 60).roundToInt()
        return if (m >= 60) "${m / 60} h ${(m % 60).toString().padStart(2, '0')}" else "$m min"
    }

    /** 3725 -> "1:02:05", 312 -> "5:12" */
    fun clock(seconds: Double?): String {
        if (seconds == null) return "n/d"
        val s = seconds.roundToInt()
        return if (s >= 3600) "${s / 3600}:${(s % 3600 / 60).toString().padStart(2, '0')}:${(s % 60).toString().padStart(2, '0')}" else "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    fun pace(speed: Double?): String {
        if (speed == null || speed <= 0) return "n/d"
        val s = (1000 / speed).roundToInt()
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')} /km"
    }

    fun speed(speed: Double?) = if (speed == null) "n/d" else "${num(speed * 3.6)} km/h"

    fun date(iso: String): String = LocalDate.parse(iso.take(10)).format(shortDate).replace(".", "")
    fun longDate(date: LocalDate): String = date.format(longDate).replaceFirstChar { it.uppercase() }
    fun dayMonth(date: LocalDate): String = date.format(dayMonth).replace(".", "")
    fun month(date: LocalDate): String = date.format(monthName).replace(".", "")

    fun typeLabel(type: String) = when {
        type.contains("trail") -> "Trail"
        type.contains("treadmill") -> "Tapis de course"
        type.contains("running") -> "Course à pied"
        type.contains("cycl") || type.contains("biking") -> "Vélo"
        type.contains("walking") -> "Marche"
        type.contains("hiking") -> "Randonnée"
        type.contains("strength") -> "Musculation"
        type.contains("swim") -> "Natation"
        else -> type.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    /** "à l'instant", "il y a 5 min", "il y a 3 h", "hier", "il y a 4 j" */
    fun relative(epochMillis: Long): String {
        if (epochMillis <= 0) return "jamais"
        val minutes = (System.currentTimeMillis() - epochMillis) / 60_000
        return when {
            minutes < 1 -> "à l'instant"
            minutes < 60 -> "il y a $minutes min"
            minutes < 24 * 60 -> "il y a ${minutes / 60} h"
            minutes < 48 * 60 -> "hier"
            else -> "il y a ${minutes / (24 * 60)} j"
        }
    }

    /** "40 s", "12 min", "1 h 05" */
    fun eta(seconds: Double): String {
        if (seconds < 60) return "${maxOf(seconds.roundToInt(), 1)} s"
        val m = (seconds / 60).roundToInt()
        return if (m < 60) "$m min" else "${m / 60} h ${(m % 60).toString().padStart(2, '0')}"
    }

    /** "24/10 19:50" */
    fun dateTime(epochMillis: Long): String = java.time.Instant.ofEpochMilli(epochMillis).atZone(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM HH:mm"))
}
