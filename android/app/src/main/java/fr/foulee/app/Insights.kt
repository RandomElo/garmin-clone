package fr.foulee.app

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The two form values of the notification. */
class Form(val readiness: Int?, val readinessLabel: String?, val fatigue: Double?, val fatigueZone: String?)

class Factor(val key: String, val label: String, val display: String, val note: String, val baselineDisplay: String?, val z: Double, val value: Double = 0.0, val baseline: Double? = null) {
    /** "good", "normal" or "low" */
    val status get() = if (z <= -1) "low" else if (z >= 0.8) "good" else "normal"
}

class Readiness(val date: String, val score: Int, val label: String, val factors: List<Factor>)

/** One calendar day of the load model. Week and month are on 20, fatigue on 10. */
class LoadPoint(val date: String, val week: Double, val month: Double, val fatigue: Double?, val weekKm: Double, val weekSessions: Int, val monthKm: Double = 0.0, val monthSessions: Int = 0)

class HrRefs(val rest: Double, val max: Double)

class Analysis(
    val readiness: Readiness?,
    val load: List<LoadPoint>,
    /** Average of the three weeks before the last 7 days, on 20 */
    val previousWeek: Double?,
    val refs: HrRefs,
    private val trimpRatio: Double,
    /** Load of the heaviest weeks (the 99th percentile): 20 on the weekly scale */
    val weekRef: Double,
    /** Readiness of any day: `readinessOn(date, dayWhoseStressCounts)` */
    val readinessOn: (String, String) -> Readiness?,
) {
    /** Load of one session: Garmin's training load, or a heart-rate estimate without it. */
    fun loadOf(a: ActivityRow): Double? = if ((a.trainingLoad ?: 0.0) > 0) a.trainingLoad else Insights.trimp(a, refs)?.times(trimpRatio)

    /** Effort of a session on 10 (saturating curve) */
    fun effortOf(a: ActivityRow): Double? = loadOf(a)?.let { (100 * (1 - exp(-it / 200))).roundToInt() / 10.0 }

    /** Share of the weekly scale (on 20) that one session represents */
    fun pointsOf(a: ActivityRow): Double? = loadOf(a)?.let { (20 * it / weekRef * 10).roundToInt() / 10.0 }

    /** Load of the 7 days ending the day of the session, on 20 */
    fun weekLoadOn(a: ActivityRow): Double? = load.firstOrNull { it.date == a.startLocal.take(10) }?.week

    val today get() = load.lastOrNull()
    val form get() = Form(readiness?.score, readiness?.label, today?.fatigue, today?.fatigue?.let(Insights::zoneOf))
}

/**
 * Port of the former backend (load.ts and readiness.ts).
 * - fatigue, 0-10: load of the last 7 days against the average week of the last 28 days.
 * - readiness, 0-100: last night and recent days against the athlete's own habits, plus the fatigue of the day before.
 */
object Insights {
    private const val WEEK = 7
    private const val MONTH = 28

    fun analyze(db: Db, today: LocalDate = LocalDate.now()): Analysis {
        val daily = db.dailyRows()
        val model = LoadModel(db.activities(), daily, today)
        val load = model.points()
        val last = load.size - 1
        val before = (1..3).mapNotNull { k -> model.week.getOrNull(last - WEEK * k)?.let { on20(it, model.weekRef) } }
        val byDate = daily.associateBy { it.date }
        return Analysis(latestReadiness(byDate, model), load, before.takeIf { it.size == 3 }?.average(), model.refs, model.ratio, model.weekRef) { date, stressDay -> readinessOn(byDate, model, date, stressDay) }
    }

    fun zoneOf(fatigue: Double) = when {
        fatigue < 3 -> "fresh"
        fatigue < 7 -> "optimal"
        fatigue < 8.5 -> "high"
        else -> "overload"
    }

    fun adviceOf(zone: String?) = when (zone) {
        "fresh" -> "Bien reposé : bon moment pour une séance dure ou une course."
        "optimal" -> "Charge équilibrée : tu progresses sans t'épuiser."
        "high" -> "Charge élevée : privilégie le footing facile dans les prochains jours."
        "overload" -> "Surcharge : prévois du repos ou une semaine allégée."
        else -> "Pas assez d'entraînement récent pour estimer la fatigue."
    }

    private fun round1(v: Double) = (v * 10).roundToInt() / 10.0
    private fun on20(v: Double, ref: Double) = round1(min(20 * v / ref, 20.0))
    private fun mean(v: List<Double>) = if (v.isEmpty()) 0.0 else v.sum() / v.size
    private fun sd(v: List<Double>): Double {
        if (v.size < 2) return 0.0
        val m = mean(v)
        return sqrt(v.sumOf { (it - m) * (it - m) } / (v.size - 1))
    }

    private fun percentile(values: List<Double>, p: Double): Double {
        val sorted = values.filter { it > 0 }.sorted()
        return if (sorted.isEmpty()) 1.0 else sorted[min(sorted.size - 1, (sorted.size * p).toInt())]
    }

    /** French number: 7,5 */
    fun dec(value: Double, digits: Int = 1): String =
        DecimalFormat("0" + if (digits > 0) "." + "#".repeat(digits) else "", DecimalFormatSymbols(Locale.FRANCE)).format(value)

    private fun hours(seconds: Double): String {
        val m = (seconds / 60).roundToInt()
        return "${m / 60} h ${(m % 60).toString().padStart(2, '0')}"
    }

    private fun shortDuration(seconds: Double): String {
        val m = (abs(seconds) / 60).roundToInt()
        return if (m < 60) "$m min" else "${m / 60} h" + if (m % 60 != 0) " ${(m % 60).toString().padStart(2, '0')}" else ""
    }

    /** Session loads, then rolling 7 / 28-day sums for every calendar day from the first activity to today. */
    private class LoadModel(activities: List<ActivityRow>, daily: List<DailyRow>, today: LocalDate) {
        val dates = ArrayList<String>()
        val index = HashMap<String, Int>()
        val week = ArrayList<Double>()
        val month = ArrayList<Double>()
        val weekKm = ArrayList<Double>()
        val weekSessions = ArrayList<Int>()
        val monthKm = ArrayList<Double>()
        val monthSessions = ArrayList<Int>()
        val weekRef: Double
        val monthRef: Double
        val refs: HrRefs = hrRefs(activities, daily, today)
        val ratio: Double = trimpToLoad(activities, refs)

        init {
            val perDay = HashMap<String, Triple<Double, Double, Int>>()
            for (a in activities) {
                val load = if ((a.trainingLoad ?: 0.0) > 0) a.trainingLoad!! else (trimp(a, refs)?.times(ratio) ?: 0.0)
                val (l, km, n) = perDay[a.startLocal.take(10)] ?: Triple(0.0, 0.0, 0)
                perDay[a.startLocal.take(10)] = Triple(l + load, km + (a.distance ?: 0.0) / 1000, n + 1)
            }
            val loads = ArrayList<Double>()
            val kms = ArrayList<Double>()
            val counts = ArrayList<Int>()
            var day = activities.firstOrNull()?.startLocal?.take(10)?.let(LocalDate::parse)
            while (day != null && !day.isAfter(today)) {
                val (l, km, n) = perDay[day.toString()] ?: Triple(0.0, 0.0, 0)
                loads += l; kms += km; counts += n
                dates += day.toString()
                index[day.toString()] = loads.size - 1
                week += loads.takeLast(WEEK).sum()
                month += loads.takeLast(MONTH).sum()
                weekKm += kms.takeLast(WEEK).sum()
                weekSessions += counts.takeLast(WEEK).sum()
                monthKm += kms.takeLast(MONTH).sum()
                monthSessions += counts.takeLast(MONTH).sum()
                day = day.plusDays(1)
            }
            weekRef = percentile(week, 0.99)
            monthRef = percentile(month, 0.99)
        }

        fun fatigueAt(i: Int): Double? {
            val avgWeek = month[i] / 4
            // The ratio means nothing without a base month (start of history, long break)
            return if (avgWeek > weekRef * 0.1) round1(min(max((week[i] / avgWeek - 0.6) * 10, 0.0), 10.0)) else null
        }

        fun fatigueOn(date: String): Double? = index[date]?.let(::fatigueAt)

        fun points() = dates.indices.map { LoadPoint(dates[it], on20(week[it], weekRef), on20(month[it], monthRef), fatigueAt(it), weekKm[it], weekSessions[it], monthKm[it], monthSessions[it]) }
    }

    /** Resting HR: 30-day average. Max HR: 99th percentile of the activity maxima over two years. */
    private fun hrRefs(activities: List<ActivityRow>, daily: List<DailyRow>, today: LocalDate): HrRefs {
        val rests = daily.filter { it.date >= today.minusDays(30).toString() }.mapNotNull { it.restingHr }
        val maxes = activities.filter { it.startLocal.take(10) >= today.minusDays(730).toString() }.mapNotNull { it.maxHr }.sortedDescending()
        return HrRefs(if (rests.isEmpty()) 50.0 else mean(rests).roundToInt().toDouble(), maxes.getOrNull((maxes.size * 0.01).toInt()) ?: 190.0)
    }

    /** Banister TRIMP: from the stored heart-rate curve when there is one, else from the average heart rate. */
    fun trimp(a: ActivityRow, refs: HrRefs): Double? {
        a.trimp?.let { return it }
        val hr = a.averageHr ?: return null
        val duration = a.duration ?: return null
        if (hr <= 0 || duration <= 0) return null
        val x = min(max((hr - refs.rest) / (refs.max - refs.rest), 0.0), 1.0)
        return duration / 60 * x * 0.64 * exp(1.92 * x)
    }

    /** Heart-rate references (resting and max) from the local data. */
    fun refs(db: Db, today: LocalDate = LocalDate.now()): HrRefs = hrRefs(db.activities(), db.dailyRows(), today)

    /** TRIMP from the heart-rate curve (gaps capped at a minute), or from the average heart rate without a curve. */
    fun trimpFromSamples(samples: List<Sample>, averageHr: Double?, duration: Double?, refs: HrRefs): Double? {
        fun weight(hr: Double, minutes: Double): Double {
            val x = min(max((hr - refs.rest) / (refs.max - refs.rest), 0.0), 1.0)
            return minutes * x * 0.64 * exp(1.92 * x)
        }
        val points = samples.filter { it.t != null && (it.hr ?: 0.0) > 0 }
        if (points.size > 10) {
            var total = 0.0
            for (i in 1 until points.size) {
                val dt = min(points[i].t!! - points[i - 1].t!!, 60.0)
                if (dt > 0) total += weight(points[i].hr!!, dt / 60)
            }
            return total
        }
        return if (averageHr != null && duration != null && averageHr > 0 && duration > 0) weight(averageHr, duration / 60) else null
    }

    /** Garmin load per TRIMP point, measured on the sessions that have both. */
    private fun trimpToLoad(activities: List<ActivityRow>, refs: HrRefs): Double {
        val ratios = activities.mapNotNull { a ->
            val t = trimp(a, refs)
            if ((a.trainingLoad ?: 0.0) > 0 && t != null && t > 10) a.trainingLoad!! / t else null
        }.sorted()
        return if (ratios.isEmpty()) 1.3 else ratios[ratios.size / 2]
    }

    private class Spec(
        val key: String, val label: String, val weight: Double, val window: Int, val minSd: Double, val sign: Double, val log: Boolean,
        val get: (DailyRow) -> Double?, val display: (Double) -> String, val note: (Double, Double) -> String,
    )

    private fun diffNote(delta: Double, unit: String, digits: Int = 0): String {
        val rounded = (delta * Math.pow(10.0, digits.toDouble())).roundToInt() / Math.pow(10.0, digits.toDouble())
        if (rounded == 0.0) return "comme d'habitude"
        return "${dec(abs(rounded), digits)} $unit ${if (rounded > 0) "de plus" else "de moins"} que d'habitude"
    }

    private val specs = listOf(
        Spec("sleep", "Sommeil", 0.22, 30, 25 * 60.0, 1.0, false, { it.sleepSeconds }, ::hours) { v, base ->
            if (abs(v - base) < 600) "comme d'habitude" else "${shortDuration(v - base)} de ${if (v > base) "plus" else "moins"} que d'habitude"
        },
        Spec("sleepScore", "Score de sommeil", 0.08, 30, 4.0, 1.0, false, { it.sleepScore }, { "${it.roundToInt()}/100" }) { v, base -> diffNote(v - base, "points") },
        Spec("hrv", "VFC de la nuit", 0.27, 60, 0.06, 1.0, true, { it.hrvNight }, { "${it.roundToInt()} ms" }) { v, base -> diffNote(v - base, "ms") },
        Spec("restingHr", "FC au repos", 0.15, 30, 1.5, -1.0, false, { it.restingHr }, { "${it.roundToInt()} bpm" }) { v, base -> diffNote(v - base, "bpm") },
        Spec("bodyBattery", "Body Battery au réveil", 0.08, 30, 6.0, 1.0, false, { it.bodyBatteryHigh }, { "${it.roundToInt()}/100" }) { v, base -> diffNote(v - base, "points") },
        Spec("stress", "Stress de la journée", 0.05, 30, 3.0, -1.0, false, { it.avgStress }, { "${it.roundToInt()}/100" }) { v, base -> diffNote(v - base, "points") },
    )
    private const val FATIGUE_WEIGHT = 0.15
    private const val MIN_HISTORY = 7

    /** Readiness of the latest day with a night recorded (today, or earlier if the watch has not synced yet). */
    private fun latestReadiness(days: Map<String, DailyRow>, model: LoadModel): Readiness? {
        val date = days.values.filter { it.sleepSeconds != null || it.hrvNight != null }.maxOfOrNull { it.date } ?: return null
        return readinessOn(days, model, date, LocalDate.parse(date).minusDays(1).toString())
    }

    /** Factors and score of a day. [stressDay] is the day whose stress counts: the session day for an afternoon or evening session. */
    private fun readinessOn(days: Map<String, DailyRow>, model: LoadModel, date: String, stressDay: String): Readiness? {
        val factors = ArrayList<Factor>()
        val weights = ArrayList<Double>()
        for (spec in specs) {
            val day = if (spec.key == "stress") stressDay else date
            val raw = days[day]?.let(spec.get) ?: continue
            val past = (1..spec.window).mapNotNull { days[LocalDate.parse(day).minusDays(it.toLong()).toString()]?.let(spec.get) }
            val scale = { v: Double -> if (spec.log) ln(v) else v }
            var z = 0.0
            var baseline: Double? = null
            if (past.size >= MIN_HISTORY) {
                val scaled = past.map(scale)
                z = spec.sign * (scale(raw) - mean(scaled)) / max(sd(scaled), spec.minSd)
                baseline = if (spec.log) exp(mean(scaled)) else mean(scaled)
            }
            // Below 6 h the literature sees a performance cost whatever the habits
            if (spec.key == "sleep" && raw < 6 * 3600) z = min(z, -1.2)
            factors += Factor(spec.key, spec.label, spec.display(raw), baseline?.let { spec.note(raw, it) } ?: "pas encore assez d'historique", baseline?.let { spec.display(it) }, (z * 100).roundToInt() / 100.0, raw, baseline)
            weights += spec.weight
        }

        model.fatigueOn(LocalDate.parse(date).minusDays(1).toString())?.let { fatigue ->
            val z = if (fatigue > 7) -(fatigue - 7) / 1.2 else if (fatigue < 3) 0.5 else 0.0
            val note = if (fatigue > 7) "semaine plus chargée que ton mois" else if (fatigue < 3) "semaine légère, tu es frais" else "dans la zone équilibrée"
            factors += Factor("fatigue", "Fatigue d'entraînement", "${dec(fatigue)}/10", note, "3 à 7", (z * 100).roundToInt() / 100.0, fatigue, null)
            weights += FATIGUE_WEIGHT
        }
        if (factors.none { it.key == "sleep" || it.key == "hrv" }) return null

        val weighted = factors.indices.sumOf { weights[it] * factors[it].z.coerceIn(-2.5, 2.0) } / weights.sum()
        val score = (68 + 18 * weighted).coerceIn(0.0, 100.0).roundToInt()
        val label = when {
            score >= 82 -> "Au top"
            score >= 66 -> "Prêt"
            score >= 52 -> "Correct"
            score >= 38 -> "Fatigué"
            else -> "À ménager"
        }
        return Readiness(date, score, label, factors)
    }
}
