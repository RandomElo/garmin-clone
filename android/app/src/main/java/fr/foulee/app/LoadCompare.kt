package fr.foulee.app

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

class WindowSession(val activity: ActivityRow, val category: String, val km: Double, val points: Double)

class LoadWindow(val from: String, val to: String, val points: Double, val km: Int, val sessions: List<WindowSession>)

class CategoryDiff(val category: String, val label: String, val count: Int, val avgCount: Double, val points: Double, val avgPoints: Double, val delta: Double)

class LoadComparison(val current: LoadWindow, val previous: List<LoadWindow>, val avgPoints: Double, val avgKm: Int, val delta: Double, val categories: List<CategoryDiff>, val explanation: String)

/** Last 7 days against the three weeks before, session by session: what makes the load go up or down (port of briefing.ts). */
object LoadCompare {
    private fun round1(v: Double) = (v * 10).roundToInt() / 10.0
    private fun dec(v: Double) = Insights.dec(v, 1)

    private val nouns = mapOf(
        "competition" to "compétitions", "interval" to "fractionné", "long" to "sorties longues", "easy" to "footings", "strength" to "musculation", "other" to "autres sports",
    )

    fun pluralLabel(category: String) = when (category) {
        "competition" -> "Compétitions"
        "interval" -> "Fractionnés"
        "long" -> "Sorties longues"
        "easy" -> "Footings"
        "strength" -> "Musculation"
        else -> "Autres sports"
    }

    fun compute(activities: List<ActivityRow>, analysis: Analysis, today: LocalDate = LocalDate.now()): LoadComparison {
        val start = today.minusDays(27).toString()
        val sessions = activities.filter { it.startLocal.take(10) >= start }.map {
            WindowSession(it, it.category, if (it.isRun) round1((it.distance ?: 0.0) / 1000) else 0.0, analysis.pointsOf(it) ?: 0.0)
        }
        fun window(k: Int): LoadWindow {
            val to = today.minusDays(7L * k)
            val from = to.minusDays(6)
            val list = sessions.filter { it.activity.startLocal.take(10) >= from.toString() && it.activity.startLocal.take(10) <= to.toString() }
            return LoadWindow(from.toString(), to.toString(), round1(list.sumOf { it.points }), list.sumOf { it.km }.roundToInt(), list)
        }
        val current = window(0)
        val previous = listOf(1, 2, 3).map(::window)
        val avgPoints = round1(previous.sumOf { it.points } / 3)
        val avgKm = (previous.sumOf { it.km } / 3.0).roundToInt()

        val categories = Categories.all.map { category ->
            val mine = current.sessions.filter { it.category == category }
            val before = previous.flatMap { w -> w.sessions.filter { it.category == category } }
            val points = round1(mine.sumOf { it.points })
            val avg = round1(before.sumOf { it.points } / 3)
            CategoryDiff(category, pluralLabel(category), mine.size, round1(before.size / 3.0), points, avg, round1(points - avg))
        }.filter { it.count > 0 || it.avgCount > 0 }.sortedByDescending { abs(it.delta) }

        val delta = round1(current.points - avgPoints)
        return LoadComparison(current, previous, avgPoints, avgKm, delta, categories, explain(current, avgPoints, delta, categories))
    }

    /** One plain sentence: is the load up or down, and which kind of session explains it. */
    private fun explain(current: LoadWindow, avgPoints: Double, delta: Double, categories: List<CategoryDiff>): String {
        if (abs(delta) < 0.8) return "Charge stable : ${dec(current.points)} cette semaine glissante, contre ${dec(avgPoints)} en moyenne les trois semaines d'avant."
        val up = delta > 0
        val reasons = categories.filter { abs(it.delta) >= 0.6 && sign(it.delta) == sign(delta) }.take(2)
            .map { "${if (it.delta > 0) "plus" else "moins"} de ${nouns[it.category]} (${it.count} contre ${dec(it.avgCount)} par semaine avant)" }
        val head = "Charge en ${if (up) "hausse" else "baisse"} de ${dec(abs(delta))} point${if (abs(delta) >= 2) "s" else ""}"
        // The biggest move in the other direction, so the table never contradicts the sentence silently
        val against = categories.firstOrNull { abs(it.delta) >= 1 && sign(it.delta) != sign(delta) }
        val counter = against?.let { ", même si tu as fait ${if (it.delta > 0) "plus" else "moins"} de ${nouns[it.category]} (${it.count} contre ${dec(it.avgCount)})" } ?: ""
        return if (reasons.isNotEmpty()) "$head, surtout parce que tu as fait ${Txt.join(reasons)}$counter."
        else "$head, sans type de séance qui ressorte : c'est l'ensemble qui est ${if (up) "plus" else "moins"} chargé$counter."
    }
}
