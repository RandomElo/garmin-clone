package fr.foulee.app

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

class HealthMetric(
    val key: String, // recovery, sleep, hrv, fitness, fatigue
    val label: String,
    val value: String,
    val unit: String?,
    /** good, normal or low */
    val status: String,
    val statusLabel: String,
    val delta: String?,
    val sentence: String,
    /** Trend over the chosen period, oldest first */
    val series: List<Pair<String, Double?>>,
    /** Draw the trend upside down (pace: lower is better), so that up always reads as progress */
    val inverted: Boolean,
    /** Raw value placed on the scale of the explanation (score, hours, ms, s/km gained, /10) */
    val numeric: Double,
    /** Personal normal range, for the HRV */
    val band: Pair<Double, Double>? = null,
)

/**
 * The health page in five numbers: everything the watch measures, aggregated into indicators that each answer one question
 * (port of health-summary.ts): recovery, sleep, HRV, fitness (pace at 150 bpm on steady runs) and training fatigue.
 */
object HealthSummary {
    private const val EFFICIENCY_HR = 150.0

    private fun mean(v: List<Double>) = v.sum() / v.size
    private fun median(v: List<Double>): Double {
        val s = v.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2
    }
    private fun sd(v: List<Double>): Double {
        if (v.size < 2) return 0.0
        val m = mean(v)
        return sqrt(v.sumOf { (it - m) * (it - m) } / (v.size - 1))
    }
    private fun dec(v: Double, digits: Int = 1) = Insights.dec(v, digits)
    private fun add(date: String, n: Long) = LocalDate.parse(date).plusDays(n).toString()

    // "Sommeil" -> "sommeil" inside a sentence, but brand names keep their capitals
    private fun inSentence(label: String) = if (label.startsWith("Body") || label.getOrNull(1)?.isUpperCase() == true) label else label.replaceFirstChar { it.lowercase() }

    private fun rolling(values: List<Double?>, n: Int): List<Double?> = values.indices.map { i ->
        val window = values.subList(maxOf(0, i - n + 1), i + 1).filterNotNull()
        if (window.size >= minOf(3, n)) mean(window) else null
    }

    fun compute(days: Int, activities: List<ActivityRow>, daily: List<DailyRow>, analysis: Analysis, today: LocalDate = LocalDate.now()): List<HealthMetric> {
        val todayKey = today.toString()
        val dates = (0 until days).map { add(todayKey, (it - days + 1).toLong()) }
        val byDate = daily.associateBy { it.date }
        return listOfNotNull(recovery(dates, analysis), sleep(dates, todayKey, byDate), hrv(dates, todayKey, byDate), fitness(dates, todayKey, activities), fatigue(days, analysis))
    }

    private fun recovery(dates: List<String>, analysis: Analysis): HealthMetric? {
        val readiness = dates.map { d -> d to analysis.readinessOn(d, add(d, -1)) }
        val scores = readiness.map { (d, r) -> d to r?.score?.toDouble() }
        val last = readiness.lastOrNull { it.second != null } ?: return null
        val today = last.second!!
        val lows = today.factors.filter { it.status == "low" }.sortedBy { it.z }
        val goods = today.factors.filter { it.status == "good" }.sortedByDescending { it.z }
        val avg = mean(scores.mapNotNull { it.second })
        val sentence = when {
            lows.isNotEmpty() -> "Ce qui tire vers le bas : ${lows.take(2).joinToString(", ") { "${inSentence(it.label)} (${it.note})" }}."
            goods.isNotEmpty() -> "Point fort : ${inSentence(goods[0].label)} (${goods[0].note})."
            else -> "Tous les signaux sont dans ta moyenne."
        }
        return HealthMetric(
            "recovery", "Récupération", today.score.toString(), "/ 100", if (today.score >= 66) "good" else if (today.score >= 52) "normal" else "low", today.label,
            "moyenne ${avg.roundToInt()} sur la période", sentence, scores, false, today.score.toDouble(),
        )
    }

    private fun sleep(dates: List<String>, today: String, map: Map<String, DailyRow>): HealthMetric? {
        fun nights(from: String, to: String): List<Pair<Double, Double?>> {
            val out = mutableListOf<Pair<Double, Double?>>()
            var d = from
            while (d <= to) {
                map[d]?.takeIf { (it.sleepSeconds ?: 0.0) > 0 }?.let { out += it.sleepSeconds!! to it.sleepScore }
                d = add(d, 1)
            }
            return out
        }
        val week = nights(add(today, -6), today)
        val before = nights(add(today, -66), add(today, -7))
        if (week.size < 3) return null
        val avg = mean(week.map { it.first })
        val usual = if (before.size >= 14) mean(before.map { it.first }) else null
        val scores = week.mapNotNull { it.second }
        val score = if (scores.isNotEmpty()) mean(scores).roundToInt() else null
        val gap = if (usual == null) 0.0 else avg - usual
        // Under 7 h on average is short for an athlete, whatever the habits
        val status = if (avg < 6.5 * 3600 || gap < -40 * 60) "low" else if (avg >= 7.5 * 3600 && gap > -10 * 60) "good" else "normal"
        val spread = sd(week.map { it.first })
        return HealthMetric(
            "sleep", "Sommeil", Txt.hours(avg), "/ nuit", status, if (status == "good") "Bon" else if (status == "low") "Insuffisant" else "Correct",
            if (usual == null) null else if (abs(gap) < 600) "comme d'habitude" else "${Txt.shortDuration(gap)} de ${if (gap > 0) "plus" else "moins"} que d'habitude",
            "Moyenne des 7 dernières nuits${score?.let { ", score $it/100" } ?: ""}. ${if (spread > 75 * 60) "Nuits très irrégulières d'un jour à l'autre." else "Durée régulière d'une nuit à l'autre."}",
            dates.map { d -> d to map[d]?.sleepSeconds?.takeIf { it > 0 }?.let { (it / 3600 * 10).roundToInt() / 10.0 } }, false, (avg / 3600 * 100).roundToInt() / 100.0,
        )
    }

    private fun hrv(dates: List<String>, today: String, map: Map<String, DailyRow>): HealthMetric? {
        fun values(from: String, to: String, pick: (DailyRow) -> Double?): List<Double> {
            val out = mutableListOf<Double>()
            var d = from
            while (d <= to) {
                map[d]?.let(pick)?.takeIf { it > 0 }?.let { out += it }
                d = add(d, 1)
            }
            return out
        }
        val week = values(add(today, -6), today) { it.hrvNight }
        val base = values(add(today, -66), add(today, -7)) { it.hrvNight }
        if (week.size < 3 || base.size < 14) return null
        // Normal range on a log scale, like the HRV-guided training studies
        val lnBase = base.map { ln(it) }
        val m = mean(lnBase)
        val s = max(sd(lnBase), 0.05)
        val avg = exp(mean(week.map { ln(it) }))
        val low = exp(m - s)
        val high = exp(m + s)
        val status = if (avg < low) "low" else if (avg > high) "good" else "normal"

        val rhrWeek = values(add(today, -6), today) { it.restingHr }
        val rhrBase = values(add(today, -66), add(today, -7)) { it.restingHr }
        val rhrGap = if (rhrWeek.isNotEmpty() && rhrBase.isNotEmpty()) mean(rhrWeek) - mean(rhrBase) else null
        val rhrText = when {
            rhrGap == null -> ""
            abs(rhrGap) < 1.5 -> " FC au repos stable (${mean(rhrWeek).roundToInt()} bpm)."
            else -> " FC au repos ${if (rhrGap > 0) "en hausse" else "en baisse"} de ${dec(abs(rhrGap), 0)} bpm (${mean(rhrWeek).roundToInt()} bpm)."
        }
        val smooth = rolling(dates.map { map[it]?.hrvNight }, 7)
        return HealthMetric(
            "hrv", "VFC", avg.roundToInt().toString(), "ms", status, if (status == "low") "Sous ta norme" else if (status == "good") "Au-dessus" else "Équilibrée",
            "ta plage normale : ${low.roundToInt()} à ${high.roundToInt()} ms",
            "Moyenne des 7 dernières nuits.${if (status == "low") " Une VFC sous ta plage signale une fatigue ou un stress : allège l'intensité quelques jours." else ""}$rhrText",
            dates.mapIndexed { i, d -> d to smooth[i]?.roundToInt()?.toDouble() }, false, avg.roundToInt().toDouble(), low.roundToInt().toDouble() to high.roundToInt().toDouble(),
        )
    }

    private fun fitness(dates: List<String>, today: String, activities: List<ActivityRow>): HealthMetric? {
        // Steady runs only: no intervals, 30 min to 2 h, average HR 130-165; speed scaled linearly to 150 bpm
        val since = add(today, -400)
        val points = activities.filter {
            it.isRun && it.intervalTitle == null && it.startLocal.take(10) >= since && (it.averageHr ?: 0.0) in 130.0..165.0 && (it.duration ?: 0.0) in 1800.0..7200.0 && (it.averageSpeed ?: 0.0) > 0
        }.map { it.startLocal.take(10) to it.averageSpeed!! * EFFICIENCY_HR / it.averageHr!! }
        fun between(from: String, to: String) = points.filter { it.first in from..to }.map { it.second }
        val recent = between(add(today, -27), today)
        val before = between(add(today, -83), add(today, -28))
        if (recent.size < 3) return null
        val now = median(recent)
        // s/km gained at 150 bpm versus the two months before (positive = faster now)
        val gap = if (before.size >= 3) (1000 / median(before) - 1000 / now).roundToInt() else null
        val status = if (gap == null || abs(gap) < 3) "normal" else if (gap > 0) "good" else "low"
        // Sparkline: median of the 28 days ending on each date (smooth enough to read a trend)
        val series = dates.map { d -> val w = between(add(d, -27), d); d to if (w.size >= 3) (1000 / median(w) * 10).roundToInt() / 10.0 else null }
        return HealthMetric(
            "fitness", "Condition", Txt.pace(now).replace("/km", ""), "/km à ${EFFICIENCY_HR.roundToInt()} bpm", status, if (status == "good") "En progrès" else if (status == "low") "En baisse" else "Stable",
            if (gap == null) null else if (gap == 0) "comme les 2 mois d'avant" else "${abs(gap)} s/km ${if (gap > 0) "plus vite" else "plus lent"} que les 2 mois d'avant",
            "Ton allure à ${EFFICIENCY_HR.roundToInt()} bpm sur tes footings réguliers des 4 dernières semaines : plus elle baisse, plus tu es en forme.",
            series, true, (gap ?: 0).toDouble(),
        )
    }

    private fun fatigue(days: Int, analysis: Analysis): HealthMetric? {
        val today = analysis.today ?: return null
        val fatigue = today.fatigue ?: return null
        val zone = Insights.zoneOf(fatigue)
        return HealthMetric(
            "fatigue", "Fatigue", dec(fatigue), "/ 10", if (zone == "high" || zone == "overload") "low" else "good",
            when (zone) { "fresh" -> "Frais"; "optimal" -> "Équilibrée"; "high" -> "Élevée"; else -> "Surcharge" },
            "3 à 7 = équilibré", Insights.adviceOf(zone), analysis.load.takeLast(days).map { it.date to it.fatigue }, false, fatigue,
        )
    }
}
