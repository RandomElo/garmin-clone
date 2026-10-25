package fr.foulee.app

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

/** One finding about the runner: [good] is a strength, otherwise something to watch. */
class Finding(val good: Boolean, val title: String, val text: String)

class Figure(val label: String, val value: String, val caption: String? = null)

/** One side of the runner's training, scored 0 to 100 against a simple target, with what to do when it lags. */
class Dimension(val label: String, val score: Int, val advice: String, val detail: String = "", val dots: Pair<Int, Int>? = null, val how: String = "")

class ProfileReport(val figures: List<Figure>, val dimensions: List<Dimension>, val findings: List<Finding>, val summary: String?) {
    /** The lowest score: always there, even when nothing is wrong (the screen says so when it is high). */
    val weakest: Dimension? get() = dimensions.minByOrNull { it.score }
}

/**
 * The runner's profile, built only from what the phone stores: volume and regularity from the activities,
 * intensity mix from the kinds of session, form from Garmin's VO2 max, and the body from the daily wellness rows.
 * Every sentence comes from a measured number and a threshold, nothing is guessed.
 */
object Profile {
    private fun avg(values: List<Double?>) = values.filterNotNull().takeIf { it.isNotEmpty() }?.average()

    private fun percent(v: Double) = "${v.roundToInt()} %"

    private fun clamp(v: Double) = v.coerceIn(0.0, 100.0).roundToInt()

    fun compute(snap: Snapshot): ProfileReport {
        val today = LocalDate.now()
        val runs = snap.activities.filter { it.isRun }
        if (runs.isEmpty()) return ProfileReport(emptyList(), emptyList(), emptyList(), null)
        val findings = mutableListOf<Finding>()
        val dimensions = mutableListOf<Dimension>()

        // Full weeks only: the current one is not over
        val weeks = snap.weeks.dropLast(1)
        val last12 = weeks.takeLast(12)
        val weeklyKm = last12.takeIf { it.isNotEmpty() }?.map { it.distance / 1000 }?.average()
        val weeklyRuns = last12.takeIf { it.isNotEmpty() }?.map { it.count.toDouble() }?.average()

        if (last12.size >= 6) {
            val regular = last12.count { it.count >= 3 }
            val sparse = last12.count { it.count < 2 }
            dimensions += Dimension(
                "Régularité", clamp(regular * 100.0 / last12.size),
                "Viser 3 sorties chaque semaine, même courtes, rend la progression plus sûre.",
                "$regular semaines sur ${last12.size} avec au moins 3 sorties", regular to last12.size,
                how = "100 = au moins 3 sorties chaque semaine pendant 12 semaines.",
            )
            if (last12.size >= 8) {
                if (regular >= last12.size * 0.8) findings += Finding(true, "Régularité", "$regular semaines sur ${last12.size} avec au moins 3 sorties. C'est la base de la progression.")
                else if (sparse >= 3) findings += Finding(false, "Régularité", "$sparse semaines sur ${last12.size} avec moins de 2 sorties : les interruptions freinent la progression.")
            }
        }

        val recent4 = weeks.takeLast(4).map { it.distance / 1000 }
        val before8 = weeks.dropLast(4).takeLast(8).map { it.distance / 1000 }
        if (recent4.size == 4 && before8.size >= 6 && before8.average() >= 10) {
            val change = (recent4.average() / before8.average() - 1) * 100
            when {
                change > 30 -> findings += Finding(false, "Hausse rapide du volume", "+${percent(change)} de kilomètres sur 4 semaines par rapport aux 8 d'avant. Une montée aussi vite expose aux blessures : laisse le corps s'adapter.")
                change >= 5 -> findings += Finding(true, "Volume en progression", "+${percent(change)} de kilomètres sur 4 semaines par rapport aux 8 d'avant, à un rythme que le corps absorbe bien.")
                change <= -30 -> findings += Finding(false, "Volume en baisse", "${percent(change)} de kilomètres sur 4 semaines par rapport aux 8 d'avant.")
            }
        }
        // Volume against the runner's own best weeks
        val best8 = snap.weeks.sortedByDescending { it.distance }.take(8).map { it.distance / 1000 }
        if (recent4.size == 4 && best8.size == 8 && best8.average() > 0) {
            val ratio = recent4.average() / best8.average() * 100
            dimensions += Dimension(
                "Volume", clamp(ratio),
                "Remonte progressivement, sans dépasser environ 10 % de plus d'une semaine à l'autre.",
                "${Fmt.num(recent4.average(), 0)} km par semaine sur 4 semaines, contre ${Fmt.num(best8.average(), 0)} sur tes 8 meilleures",
                how = "100 = tes 4 dernières semaines valent la moyenne de tes 8 meilleures semaines.",
            )
        }

        // Mix of sessions over the last 8 weeks
        val since8 = today.minusDays(56).toString()
        val recentRuns = runs.filter { it.startLocal.take(10) >= since8 }
        if (recentRuns.size >= 8) {
            val hard = recentRuns.count { it.category == "interval" || it.category == "competition" }
            val share = hard * 100.0 / recentRuns.size
            when {
                share > 40 -> findings += Finding(false, "Beaucoup de séances dures", "${percent(share)} de tes sorties des 8 dernières semaines sont dures (fractionné ou compétition). Trop d'intensité gêne la récupération : environ 20 % suffit à progresser.")
                share >= 10 && share <= 30 -> findings += Finding(true, "Bonne répartition de l'intensité", "${percent(share)} de séances dures sur 8 semaines, le reste en endurance facile : le dosage classique des coureurs qui progressent.")
                share < 5 -> findings += Finding(false, "Peu d'intensité", "Presque aucune séance dure en 8 semaines. Un peu de fractionné fait progresser la vitesse.")
            }
            dimensions += Dimension(
                "Équilibre", clamp(100 - abs(share - 20) * 4),
                if (share > 20) "Ajoute des footings vraiment faciles entre les séances rapides."
                else "Une séance rapide de plus par semaine ferait progresser.",
                "${percent(share)} de séances dures, objectif environ 20 %",
                how = "100 = environ 20 % de séances dures (fractionné, compétition) sur 8 semaines. Plus ou moins fait baisser le score.",
            )
            val intervals = recentRuns.count { it.category == "interval" }
            if (intervals >= 6) findings += Finding(true, "Fractionné régulier", "$intervals séances de fractionné en 8 semaines.")
            dimensions += Dimension(
                "Vitesse", clamp(intervals * 100.0 / 6),
                "Une séance rapide par semaine fait progresser la vitesse.",
                "${Txt.plural(intervals, "fractionné")} en 8 semaines, objectif 6", minOf(intervals, 6) to 6,
                how = "100 = 6 séances de fractionné en 8 semaines.",
            )
            val longRuns = recentRuns.count { it.category == "long" || (it.duration ?: 0.0) >= 5400 }
            if (longRuns >= 3) findings += Finding(true, "Endurance", "$longRuns sorties longues (19 km ou plus, ou plus d'1 h 30) en 8 semaines.")
            else if (longRuns == 0) findings += Finding(false, "Pas de sortie longue", "Aucune sortie de plus d'1 h 30 en 8 semaines : elle construit l'endurance de fond.")
            dimensions += Dimension(
                "Endurance", clamp(longRuns * 100.0 / 3),
                "Une sortie longue toutes les une à deux semaines construit l'endurance de fond.",
                "${Txt.plural(longRuns, "sortie longue")} en 8 semaines, objectif 3", minOf(longRuns, 3) to 3,
                how = "100 = 3 sorties longues (plus d'1 h 30) en 8 semaines.",
            )
        }

        // VO2 max given by Garmin: latest value against one at least 45 days older
        val vo2 = snap.fitness.filter { it.vo2Max != null }
        val latestVo2 = vo2.lastOrNull()
        val olderVo2 = latestVo2?.let { l -> vo2.lastOrNull { LocalDate.parse(it.date).isBefore(LocalDate.parse(l.date).minusDays(44)) } }
        if (latestVo2 != null && olderVo2 != null) {
            val diff = latestVo2.vo2Max!! - olderVo2.vo2Max!!
            val days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(olderVo2.date), LocalDate.parse(latestVo2.date))
            if (diff >= 1) findings += Finding(true, "VO2 max en hausse", "+${Fmt.num(diff)} point${if (diff >= 2) "s" else ""} en $days jours selon Garmin (${Fmt.num(latestVo2.vo2Max)} aujourd'hui).")
            else if (diff <= -1.5) findings += Finding(false, "VO2 max en baisse", "${Fmt.num(diff)} point${if (diff <= -2) "s" else ""} en $days jours selon Garmin.")
        }

        // The body: the last 14 days against the 60 days before
        val recentFrom = today.minusDays(14).toString()
        val baseFrom = today.minusDays(74).toString()
        val recent = snap.daily.filter { it.date >= recentFrom }
        val base = snap.daily.filter { it.date >= baseFrom && it.date < recentFrom }
        if (recent.size >= 7 && base.size >= 20) {
            val rhrNow = avg(recent.map { it.restingHr })
            val rhrBase = avg(base.map { it.restingHr })
            var rhrScore: Double? = null
            if (rhrNow != null && rhrBase != null) {
                val diff = rhrNow - rhrBase
                rhrScore = 100 - 12 * maxOf(0.0, diff)
                if (diff <= -2) findings += Finding(true, "FC au repos en baisse", "${Fmt.num(abs(diff), 0)} bpm de moins que d'habitude (${rhrNow.roundToInt()} bpm) : le cœur travaille moins pour le même repos, bon signe de forme.")
                else if (diff >= 3) findings += Finding(false, "FC au repos en hausse", "${Fmt.num(diff, 0)} bpm de plus que d'habitude (${rhrNow.roundToInt()} bpm) : peut venir de la fatigue, du stress ou d'un début de maladie.")
            }
            val hrvNow = avg(recent.map { it.hrvNight })
            val hrvBase = avg(base.map { it.hrvNight })
            var hrvScore: Double? = null
            if (hrvNow != null && hrvBase != null && hrvBase > 0) {
                val change = (hrvNow / hrvBase - 1) * 100
                hrvScore = if (change >= 0) 100.0 else 100 + change * 4
                if (change >= 8) findings += Finding(true, "VFC en hausse", "+${percent(change)} par rapport à tes 2 mois d'avant : le corps récupère bien.")
                else if (change <= -10) findings += Finding(false, "VFC en baisse", "${percent(change)} par rapport à tes 2 mois d'avant : le corps encaisse moins bien la charge.")
            }
            val sleep = avg(recent.map { it.sleepSeconds })
            var sleepScore: Double? = null
            if (sleep != null) {
                sleepScore = sleep / (7.5 * 3600) * 100
                if (sleep >= 7.5 * 3600) findings += Finding(true, "Sommeil suffisant", "${Fmt.hoursMinutes(sleep)} de sommeil par nuit en moyenne sur 14 jours.")
                else if (sleep < 6.75 * 3600) findings += Finding(false, "Sommeil court", "${Fmt.hoursMinutes(sleep)} par nuit en moyenne sur 14 jours : le sommeil est ce qui fait absorber l'entraînement.")
            }
            val parts = listOfNotNull(sleepScore, rhrScore, hrvScore)
            val note = listOfNotNull(
                sleep?.let { "sommeil ${Fmt.hoursMinutes(it)}" },
                if (rhrNow != null && rhrBase != null) (if (abs(rhrNow - rhrBase) < 0.5) "FC repos stable" else "FC repos ${if (rhrNow >= rhrBase) "+" else "−"}${Fmt.num(abs(rhrNow - rhrBase), 0)} bpm") else null,
                if (hrvNow != null && hrvBase != null) (if (abs(hrvNow - hrvBase) < 0.5) "VFC stable" else "VFC ${if (hrvNow >= hrvBase) "+" else "−"}${Fmt.num(abs(hrvNow - hrvBase), 0)} ms") else null,
            ).joinToString(" · ")
            if (parts.isNotEmpty()) dimensions += Dimension(
                "Récupération", clamp(parts.average()),
                "Protège tes nuits : c'est là que l'entraînement est absorbé.",
                "$note, comparé à tes 2 mois d'avant",
                how = "100 = 7 h 30 de sommeil, FC au repos et VFC au niveau de tes 2 mois d'avant. Moyenne des trois.",
            )
        }
        snap.analysis.today?.fatigue?.takeIf { it >= 7.5 }?.let {
            findings += Finding(false, "Fatigue élevée", "Ta semaine pèse beaucoup plus que ton mois habituel (fatigue ${Fmt.num(it)} sur 10) : une semaine plus légère aiderait.")
        }

        val figures = mutableListOf<Figure>()
        weeklyKm?.let { figures += Figure("Volume moyen", "${Fmt.num(it, 0)} km / sem.", weeklyRuns?.let { r -> "${Fmt.num(r)} sorties par semaine, sur 12 semaines" }) }
        snap.weeks.maxByOrNull { it.distance }?.takeIf { it.distance > 0 }?.let { figures += Figure("Semaine la plus chargée", "${Fmt.num(it.distance / 1000, 0)} km", "semaine du ${Fmt.dayMonth(it.start)}") }

        val first = runs.minOf { it.startLocal }.take(10).let(LocalDate::parse)
        val strengths = findings.filter { it.good }
        val weakest = dimensions.minByOrNull { it.score }
        val summary = buildString {
            append("Depuis ${Fmt.month(first)} ${first.year}, tu as couru ${Math.round(runs.sumOf { it.distance ?: 0.0 } / 1000)} km en ${runs.size} sorties.")
            strengths.firstOrNull()?.let { append(" Point fort du moment : ${it.title.replaceFirstChar { c -> c.lowercase() }}.") }
            weakest?.let { append(" Point faible : ${it.label.lowercase()}.") }
        }
        return ProfileReport(figures, dimensions, findings, summary)
    }
}
