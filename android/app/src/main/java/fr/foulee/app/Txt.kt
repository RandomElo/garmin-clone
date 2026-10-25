package fr.foulee.app

import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/** French formatting and wording helpers for the generated texts (analysis, briefing). */
object Txt {
    const val NBSP = " "

    /** "3:12/km" from a speed in m/s */
    fun pace(speed: Double): String {
        val s = (1000 / speed).roundToInt()
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}/km"
    }

    /** "7 h 05" from seconds */
    fun hours(seconds: Double): String {
        val m = (seconds / 60).roundToInt()
        return "${m / 60}${NBSP}h${NBSP}${(m % 60).toString().padStart(2, '0')}"
    }

    /** "45 min" or "1 h 10" for a gap expressed in seconds */
    fun shortDuration(seconds: Double): String {
        val m = (Math.abs(seconds) / 60).roundToInt()
        if (m < 60) return "$m${NBSP}min"
        return "${m / 60}${NBSP}h" + if (m % 60 != 0) "$NBSP${(m % 60).toString().padStart(2, '0')}" else ""
    }

    fun plural(n: Int, word: String, many: String = "${word}s") = "$n ${if (n > 1) many else word}"

    fun ordinal(n: Int) = if (n == 1) "1re" else "${n}e"

    fun capitalize(s: String) = s.replaceFirstChar { it.uppercase() }

    /** "a, b et c" */
    fun join(parts: List<String>): String = if (parts.size <= 1) parts.joinToString("") else "${parts.dropLast(1).joinToString(", ")} et ${parts.last()}"

    /**
     * Picks one wording among several, always the same for a given seed (activity id, date),
     * so a text does not change on every reload but two sessions do not read the same.
     */
    fun <T> pick(seed: Int, variants: List<T>): T {
        val x = sin(seed * 9301.0 + 49297.0) * 233280.0
        return variants[floor((x - floor(x)) * variants.size).toInt().coerceIn(0, variants.size - 1)]
    }

    fun seedOf(value: Any): Int {
        var h = 0
        for (c in value.toString()) h = h * 31 + c.code
        return Math.abs(h)
    }
}
