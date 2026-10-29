package fr.foulee.app

import kotlin.math.abs
import kotlin.math.pow

/** The name and the description the app would put on a Strava interval session. */
class StravaText(val name: String, val description: String, val interval: Boolean = true)

object StravaTextBuilder {
    /** Race paces in seconds per km (5 km 16:48, 10 km 37:02, half marathon 1:23:48), the marathon from the half with Riegel's formula. */
    private val RACE_PACES = listOf(
        "5 km" to (16 * 60 + 48.0) / 5,
        "10 km" to (37 * 60 + 2.0) / 10,
        "semi" to (83 * 60 + 48.0) / 21.0975,
        "marathon" to (83 * 60 + 48.0) * 2.0.pow(1.06) / 42.195,
    )
    private const val SPE_TOLERANCE = 0.025
    private const val SPE_MIN_REP_METERS = 800.0
    private const val SPE_MIN_REP_SECONDS = 150.0
    const val SIGNATURE = "🤖 Généré par Foulée"

    /** The proposal shown to Eloi, never applied alone: "5 km", "10 km", "semi" or "marathon" when the main reps run at that race pace (short reps never do). */
    fun proposedSpeciality(analysis: IntervalAnalysis): String? {
        val main = analysis.groups
            .filter { it.avgDistance >= SPE_MIN_REP_METERS || it.avgDuration >= SPE_MIN_REP_SECONDS }
            .maxByOrNull { it.reps.size * it.avgDistance } ?: return null
        if (main.avgSpeed <= 0) return null
        val pace = 1000 / main.avgSpeed
        val (label, ref) = RACE_PACES.minByOrNull { abs(it.second - pace) } ?: return null
        return label.takeIf { abs(pace - ref) / ref <= SPE_TOLERANCE }
    }

    /** 3:21 /km written "3 min 21 / km", like the rest of his Strava texts. */
    private fun pace(speed: Double): String {
        val s = Math.round(1000 / speed).toInt()
        return "${s / 60} min ${(s % 60).toString().padStart(2, '0')} / km"
    }

    private fun repsOf(g: RepGroup) = "${if (g.reps.size > 1) "${g.reps.size} × " else ""}${g.label}"

    /** Eloi's way of writing it on Strava: plain spaces, "x", "sec". */
    private fun plain(text: String) = text.replace('\u00a0', ' ').replace('×', 'x').replace(Regex("(\\d) s\\b"), "$1 sec")

    /**
     * "Fractionné - 5 × 1000 m (spé 10 km)" as the name; the description gives each type with its average pace and its recovery,
     * then the two form figures, then the signature. A figure that is unknown is left out.
     */
    fun build(analysis: IntervalAnalysis, weekLoad: Double?, readiness: Int?, speciality: String?): StravaText {
        val spe = speciality?.takeIf { it != "none" }
        // "Spé 5 km - 2000 m + 1000 m + 5 x 200 m ( r : 2 min 30 + 3 min + 100 m )": the recoveries once at the end, one per type of effort
        val recoveries = analysis.groups.mapNotNull { it.recovery?.label }.let { all -> if (all.distinct().size == 1) all.distinct() else all }
        val name = plain((if (spe != null) "Spé $spe - " else "Fractionné - ") + analysis.groups.joinToString(" + ") { repsOf(it) } +
            if (recoveries.isNotEmpty()) " ( r : ${recoveries.joinToString(" + ")} )" else "")
        // The recoveries are already in the name: one line per type of effort, with its average pace
        val lines = analysis.groups.map { g -> "${plain(repsOf(g))} : ${pace(g.avgSpeed)}" }
        return StravaText(name, describe(lines.joinToString("\n"), weekLoad, readiness))
    }

    /** The two form figures after the lines of the session, then the signature. A figure that is unknown is left out. */
    private fun describe(lines: String, weekLoad: Double?, readiness: Int?): String {
        val form = listOfNotNull(
            weekLoad?.let { "Charge d'entraînement : ${Fmt.num(it)} / 20" },
            readiness?.let { "Forme du jour : $it / 100" },
        )
        return (listOf(lines) + listOfNotNull(form.takeIf { it.isNotEmpty() }?.joinToString("\n")) + SIGNATURE).joinToString("\n\n")
    }

    /** A footing or a long run: just "Footing" (or "Sortie longue") as the name, and the training load as the only figure of the description. */
    fun buildEasy(a: ActivityRow, weekLoad: Double?, readiness: Int?): StravaText? {
        if ((a.distance ?: 0.0) <= 0 || (a.duration ?: 0.0) <= 0) return null
        val kind = if (a.category == "long") "Sortie longue" else "Footing"
        val description = (listOfNotNull(weekLoad?.let { "Charge d'entraînement : ${Fmt.num(it)} / 20" }) + SIGNATURE).joinToString("\n\n")
        return StravaText(kind, description, interval = false)
    }
}
