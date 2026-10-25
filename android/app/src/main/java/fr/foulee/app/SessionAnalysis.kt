package fr.foulee.app

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.atanh
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Automatic review of an interval session. Port of the former backend (session-analysis.ts).
 *
 * 1. Performance: each rep is compared with the athlete's past reps of a similar duration (±35 %, last 12 months).
 *    The session index is the duration-weighted pace gap, in %; heart rate is compared the same way.
 * 2. How it went: fade (last third of the reps versus the first), regularity, heart-rate recovery.
 * 3. Why: the context of the day (sleep, HRV, resting HR, Body Battery, stress, fatigue, rest since the last hard
 *    session, heat). Personal correlations are measured over all the interval sessions; when the history says nothing
 *    clear, effects from the literature are used, with conservative sizes (Craven et al. 2022 for acute sleep loss,
 *    HRV-guided training studies, heat analyses of races).
 */
class RepVerdict(val group: Int, val rep: Int, val speed: Double, val reference: Double?, val deltaSecPerKm: Int?, val hr: Double?)

class Contribution(val key: String, val label: String, val detail: String, val effectPct: Double, val effectSecPerKm: Int, val source: String)

class Correlation(val key: String, val label: String, val n: Int, val rho: Double, val p: Double, val strength: String, val splitEffectPct: Double, val text: String)

class HrRecovery(val avg: Int, val first: Int, val last: Int)

class SessionAnalysis(
    val verdict: String,
    val verdictLabel: String,
    val paceIndex: Double?,
    val paceDeltaSecPerKm: Int?,
    val hrDelta: Double?,
    val fadePct: Double?,
    val hrRecovery: HrRecovery?,
    val reps: List<RepVerdict>,
    val factors: List<Factor>,
    val contributions: List<Contribution>,
    val correlations: List<Correlation>,
    /** Number of interval sessions behind the personal correlations */
    val sampleSize: Int,
    val paragraphs: List<String>,
    /** One-line version for the home page */
    val summary: String,
)

private class RepRecord(val sessionId: Long, val label: String, val date: String, val duration: Double, val speed: Double, val hr: Double?)

private class SessionRecord(
    val id: Long, val date: String, val startLocal: String, val title: String, val analysis: IntervalAnalysis, val reps: List<RepVerdict>, val paceIndex: Double?, val hrDelta: Double?,
    val avgSpeed: Double, val context: Map<String, Double>, val factors: List<Factor>,
)

private class CtxSpec(
    val key: String, val label: String,
    /** Expected direction from physiology: +1 = more is better, -1 = more is worse, 0 = no prior */
    val sign: Int,
    val format: (Double) -> String,
    val below: (Double) -> String,
    val above: (Double) -> String,
    /** Effect from the literature, % of pace, for a value v when the athlete's typical value is t */
    val prior: (Double, Double) -> Double,
)

class SessionHistory(private val activities: List<ActivityRow>, private val db: Db, private val analysis: Analysis) {
    companion object {
        private const val SIMILAR_DURATION = 1.35
        // Longer blocks are warm-ups or cool-downs taken for reps by the lap detection, not efforts to rate
        private const val MAX_REP_SECONDS = 15 * 60
        private const val HISTORY_DAYS = 365L
        // Recent sessions count more in the reference: weight halves every 60 days
        private const val HALF_LIFE = 60.0
        private const val MIN_SESSIONS = 10
        private val NBSP = Txt.NBSP
    }

    private val sessions: List<SessionRecord> by lazy { build() }

    /** The latest interval session with its laps, for the home page, or null. */
    fun latest(): ActivityRow? = sessions.lastOrNull()?.let { s -> activities.firstOrNull { it.id == s.id } }

    private fun dayDiff(a: String, b: String) = LocalDate.parse(a).toEpochDay() - LocalDate.parse(b).toEpochDay()
    private fun addDays(date: String, n: Long) = LocalDate.parse(date).plusDays(n).toString()
    private fun mean(v: List<Double>) = if (v.isEmpty()) 0.0 else v.sum() / v.size
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
    private fun round1(v: Double) = (v * 10).roundToInt() / 10.0
    private fun dec(v: Double, digits: Int = 1) = Insights.dec(v, digits)
    private fun speedOf(l: Lap) = (l.distance ?: 0.0) / (l.duration ?: 1.0)

    /** Median where recent values count more (half-life of HALF_LIFE days). */
    private fun weightedMedian(items: List<Pair<Double, String>>, date: String): Double {
        val weighted = items.map { (value, d) -> value to 0.5.pow(dayDiff(date, d) / HALF_LIFE) }.sortedBy { it.first }
        val total = weighted.sumOf { it.second }
        var acc = 0.0
        for ((value, w) in weighted) {
            acc += w
            if (acc >= total / 2) return value
        }
        return weighted.last().first
    }

    private class Ref(val speed: Double, val hr: Double?)

    /**
     * Usual speed for a rep: first the same format (your "1000 m", your "30 s"), then reps of a similar duration,
     * then a pace / duration curve. Laps far slower than the rest (a jog tagged as a rep) are left out.
     */
    private fun referenceFor(label: String, duration: Double, date: String, past: List<RepRecord>): Ref? {
        val from = addDays(date, -HISTORY_DAYS)
        val recent = past.filter { it.date >= from }
        fun usable(reps: List<RepRecord>): Ref? {
            if (reps.size < 3 || reps.map { it.sessionId }.toSet().size < 2) return null
            val typical = median(reps.map { it.speed })
            val kept = reps.filter { it.speed >= typical * 0.8 }
            val hrs = kept.filter { it.hr != null }.map { it.hr!! to it.date }
            return Ref(weightedMedian(kept.map { it.speed to it.date }, date), if (hrs.size >= 3) weightedMedian(hrs, date) else null)
        }
        usable(recent.filter { it.label == label })?.let { return it }
        usable(recent.filter { it.duration <= duration * SIMILAR_DURATION && it.duration >= duration / SIMILAR_DURATION })?.let { return it }
        if (recent.size < 12) return null
        // Riegel-like curve: log(speed) = a + b log(duration)
        val xs = recent.map { ln(it.duration) }
        val ys = recent.map { ln(it.speed) }
        val mx = mean(xs)
        val my = mean(ys)
        val den = xs.sumOf { (it - mx) * (it - mx) }.takeIf { it != 0.0 } ?: 1.0
        val b = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / den
        return Ref(exp(my + b * (ln(duration) - mx)), null)
    }

    private class Rating(val reps: List<RepVerdict>, val paceIndex: Double?, val hrDelta: Double?)

    private fun rateSession(a: IntervalAnalysis, date: String, past: List<RepRecord>): Rating {
        val reps = mutableListOf<RepVerdict>()
        var weighted = 0.0
        var covered = 0.0
        var total = 0.0
        val hrGaps = mutableListOf<Double>()
        a.groups.forEachIndexed { gi, g ->
            g.reps.forEachIndexed { ri, r ->
                val duration = r.lap.duration!!
                val speed = speedOf(r.lap)
                val ref = if (g.avgDuration <= MAX_REP_SECONDS) referenceFor(g.label, duration, date, past) else null
                if (g.avgDuration <= MAX_REP_SECONDS) total += duration
                if (ref != null) {
                    weighted += (speed / ref.speed - 1) * duration
                    covered += duration
                    // Under 90 s the heart rate has no time to rise: its average says more about the recovery than the rep
                    if (ref.hr != null && (r.lap.averageHr ?: 0.0) > 0 && duration >= 90) hrGaps += r.lap.averageHr!! - ref.hr
                }
                reps += RepVerdict(gi, ri + 1, speed, ref?.speed, ref?.let { (1000 / speed - 1000 / it.speed).roundToInt() }, r.lap.averageHr)
            }
        }
        // Not enough comparable reps: no verdict rather than a misleading one
        val paceIndex = if (covered > 0 && covered >= total * 0.5) (weighted / covered * 1000).roundToInt() / 10.0 else null
        val hrDelta = if (hrGaps.size >= 2) (mean(hrGaps) * 10).roundToInt() / 10.0 else null
        return Rating(reps, paceIndex, hrDelta)
    }

    private fun build(): List<SessionRecord> {
        // Days of the hard sessions (effort ≥ 6 / 10: intervals, tempo or long run)
        val hardDays = activities.filter { (analysis.effortOf(it) ?: 0.0) >= 6 }.map { it.startLocal.take(10) }
        val intervalRuns = activities.filter { it.intervalTitle != null && it.isRun }.sortedBy { it.startLocal }
        val lapsById = db.lapsOf(intervalRuns.map { it.id })

        val past = mutableListOf<RepRecord>()
        val out = mutableListOf<SessionRecord>()
        for (a in intervalRuns) {
            val ia = lapsById[a.id]?.let { Intervals.analyze(it) } ?: continue
            val date = a.startLocal.take(10)
            val hour = a.startLocal.substring(11, 13).toInt()
            val rating = rateSession(ia, date, past)

            val readiness = analysis.readinessOn(date, if (hour >= 14) date else addDays(date, -1))
            val factors = readiness?.factors ?: emptyList()
            fun factor(key: String) = factors.firstOrNull { it.key == key }
            val previousHard = hardDays.filter { it < date }.lastOrNull()
            val context = mutableMapOf<String, Double>()
            factor("sleep")?.let { context["sleep"] = it.value / 3600 }
            factor("sleepScore")?.let { context["sleepScore"] = it.value }
            factor("hrv")?.let { context["hrv"] = it.z }
            factor("restingHr")?.let { f -> f.baseline?.let { context["restingHr"] = f.value - it } }
            factor("bodyBattery")?.let { context["bodyBattery"] = it.value }
            factor("stress")?.let { context["stress"] = it.value }
            factor("fatigue")?.let { context["fatigue"] = it.value }
            previousHard?.let { context["rest"] = dayDiff(date, it).toDouble() }
            a.minTemperature?.let { context["heat"] = it }
            context["hour"] = hour.toDouble()

            val efforts = ia.groups.filter { it.avgDuration <= MAX_REP_SECONDS }
            val work = (if (efforts.isNotEmpty()) efforts else ia.groups).flatMap { it.reps }
            out += SessionRecord(
                a.id, date, a.startLocal, ia.title, ia, rating.reps, rating.paceIndex, rating.hrDelta,
                work.sumOf { it.lap.distance!! } / work.sumOf { it.lap.duration!! }, context, factors,
            )
            for (g in ia.groups) for (r in g.reps) past += RepRecord(a.id, g.label, date, r.lap.duration!!, speedOf(r.lap), r.lap.averageHr)
        }
        return out
    }

    /* ---------- Statistics ---------- */

    private fun ranks(values: List<Double>): List<Double> {
        val order = values.mapIndexed { i, v -> v to i }.sortedBy { it.first }
        val result = DoubleArray(values.size)
        var i = 0
        while (i < order.size) {
            var j = i
            while (j + 1 < order.size && order[j + 1].first == order[i].first) j++
            for (k in i..j) result[order[k].second] = (i + j) / 2.0
            i = j + 1
        }
        return result.toList()
    }

    private fun pearson(xs: List<Double>, ys: List<Double>): Double {
        val mx = mean(xs)
        val my = mean(ys)
        var num = 0.0
        var dx = 0.0
        var dy = 0.0
        for (i in xs.indices) {
            num += (xs[i] - mx) * (ys[i] - my)
            dx += (xs[i] - mx) * (xs[i] - mx)
            dy += (ys[i] - my) * (ys[i] - my)
        }
        return if (dx != 0.0 && dy != 0.0) num / sqrt(dx * dy) else 0.0
    }

    /** Standard normal CDF (Abramowitz-Stegun erf approximation). */
    private fun phi(x: Double): Double {
        val t = 1 / (1 + 0.3275911 * (abs(x) / sqrt(2.0)))
        val erf = 1 - ((((1.061405429 * t - 1.453152027) * t + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-(x * x) / 2)
        return if (x >= 0) (1 + erf) / 2 else (1 - erf) / 2
    }

    /** Two-sided p-value of a Spearman rho, through the Fisher transform. */
    private fun pValue(rho: Double, n: Int) = if (n > 3) 2 * (1 - phi(abs(atanh(rho.coerceIn(-0.999, 0.999))) * sqrt(n - 3.0))) else 1.0

    private fun slopeOf(xs: List<Double>, ys: List<Double>): Double {
        val mx = mean(xs)
        val my = mean(ys)
        val den = xs.sumOf { (it - mx) * (it - mx) }
        return if (den != 0.0) xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / den else 0.0
    }

    /* ---------- Context variables ---------- */

    private fun signed(v: Double) = "${if (v > 0) "+" else if (v < 0) "−" else ""}${dec(abs(v))}"

    private val ctxSpecs = listOf(
        CtxSpec("sleep", "Sommeil", 1, { Txt.hours(it * 3600) }, { "tu dors moins de ${Txt.hours(it * 3600)}" }, { "tu dors plus de ${Txt.hours(it * 3600)}" }) { v, t ->
            // ~1 % per hour short, a bit more below 6 h; a long night helps a little
            if (v < t) max(-1 * (t - v) - (if (v < 6) 0.8 else 0.0), -4.0) else min(0.4 * (v - t), 0.8)
        },
        CtxSpec("sleepScore", "Score de sommeil", 1, { "${it.roundToInt()}/100" }, { "ton score de sommeil est sous ${it.roundToInt()}" }, { "ton score de sommeil dépasse ${it.roundToInt()}" }) { v, t ->
            if (v < t - 10) -0.04 * (t - 10 - v) else 0.0
        },
        CtxSpec("hrv", "VFC de la nuit", 1, { "${signed(it)} écart-type" }, { "ta VFC de la nuit est dans le bas de ta fourchette" }, { "ta VFC de la nuit est dans le haut de ta fourchette" }) { v, _ ->
            if (v < -1) max(0.9 * (v + 0.5), -3.0) else if (v > 1) 0.4 else 0.0
        },
        CtxSpec("restingHr", "FC au repos", -1, { "${signed(it)} bpm" }, { "ta FC au repos est à son niveau habituel" }, { "ta FC au repos dépasse ta moyenne de ${dec(max(it, 1.0), 0)} bpm ou plus" }) { v, _ ->
            if (v > 2) -0.3 * (v - 2) else 0.0
        },
        CtxSpec("bodyBattery", "Body Battery au réveil", 1, { "${it.roundToInt()}/100" }, { "ta Body Battery au réveil est sous ${it.roundToInt()}" }, { "ta Body Battery au réveil dépasse ${it.roundToInt()}" }) { v, t ->
            if (v < t - 15) -0.5 else 0.0
        },
        CtxSpec("stress", "Stress", -1, { "${it.roundToInt()}/100" }, { "ton stress moyen reste sous ${it.roundToInt()}" }, { "ton stress moyen dépasse ${it.roundToInt()}" }) { v, t ->
            if (v > t + 8) -0.4 else 0.0
        },
        CtxSpec("fatigue", "Fatigue d'entraînement", -1, { "${dec(it)}/10" }, { "ta fatigue est sous ${dec(it)}/10" }, { "ta fatigue dépasse ${dec(it)}/10" }) { v, _ ->
            if (v > 7) -0.6 * (v - 7) else 0.0
        },
        CtxSpec("rest", "Repos depuis la dernière séance dure", 1, { Txt.plural(it.roundToInt(), "jour") }, { "ta dernière séance dure date de ${Txt.plural(it.roundToInt(), "jour")} ou moins" }, { "ta dernière séance dure date de plus de ${Txt.plural(it.roundToInt(), "jour")}" }) { v, _ ->
            if (v <= 1) -1.5 else 0.0
        },
        CtxSpec("heat", "Température (montre)", -1, { "${it.roundToInt()} °C" }, { "la montre affiche moins de ${it.roundToInt()} °C" }, { "la montre affiche plus de ${it.roundToInt()} °C" }) { v, t ->
            // The watch reads a few degrees above the air (body heat): only the gap to the usual value counts
            if (v > max(t, 15.0) + 2) -0.3 * (v - max(t, 15.0) - 2) else 0.0
        },
        CtxSpec("hour", "Heure de la séance", 0, { "${it.roundToInt()} h" }, { "tu cours avant ${it.roundToInt()} h" }, { "tu cours après ${it.roundToInt()} h" }) { _, _ -> 0.0 },
    )

    private fun correlate(spec: CtxSpec, list: List<SessionRecord>, refSpeed: Double): Correlation? {
        val pairs = list.filter { it.paceIndex != null && it.context[spec.key] != null }.map { it.context[spec.key]!! to it.paceIndex!! }
        if (pairs.size < MIN_SESSIONS) return null
        val xs = pairs.map { it.first }
        val ys = pairs.map { it.second }
        val rho = pearson(ranks(xs), ranks(ys))
        val p = pValue(rho, pairs.size)
        val split = median(xs)
        val low = pairs.filter { it.first <= split }.map { it.second }
        val high = pairs.filter { it.first > split }.map { it.second }
        if (low.isEmpty() || high.isEmpty()) return null
        val diff = mean(high) - mean(low)
        // Physiology says which way it should go: a relation in the other direction is most likely chance
        val plausible = spec.sign == 0 || sign(diff).toInt() == spec.sign || p < 0.05
        val strength = if (plausible && abs(rho) >= 0.3 && p < 0.1) "nette" else if (plausible && abs(rho) >= 0.18 && p < 0.3) "légère" else "aucune"
        val worse = if (diff > 0) spec.below(split) else spec.above(split)
        val pct = abs(diff)
        val secs = ((1000 / refSpeed) * (pct / 100)).roundToInt()
        val text = if (strength == "aucune") "${spec.label} : pas de lien visible avec tes allures sur ${Txt.plural(pairs.size, "séance")}."
        else "Quand $worse, tes répétitions sont en moyenne ${dec(pct)} % plus lentes${if (secs != 0) " (environ $secs s/km)" else ""}, sur ${Txt.plural(pairs.size, "séance")}."
        return Correlation(spec.key, spec.label, pairs.size, (rho * 100).roundToInt() / 100.0, (p * 1000).roundToInt() / 1000.0, strength, -(pct * 10).roundToInt() / 10.0, text)
    }

    /* ---------- How the session went ---------- */

    private fun mainGroup(a: IntervalAnalysis): RepGroup {
        val efforts = a.groups.filter { it.avgDuration <= MAX_REP_SECONDS }
        return (if (efforts.isNotEmpty()) efforts else a.groups).reduce { best, g -> if (g.reps.size * g.avgDuration > best.reps.size * best.avgDuration) g else best }
    }

    private class Fade(val pct: Double, val worst: Int, val best: Int, val collapseAt: Int?)

    /** Pace of the last third of the reps versus the first third, % (negative = slowed down). */
    private fun fadeOf(g: RepGroup): Fade? {
        if (g.reps.size < 3) return null
        val speeds = g.reps.map { speedOf(it.lap) }
        val third = max(1, speeds.size / 3)
        val pct = (mean(speeds.takeLast(third)) / mean(speeds.take(third)) - 1) * 100
        // First rep from which every rep stays clearly slower than the start
        val start = mean(speeds.take(third))
        var collapseAt: Int? = null
        for (i in third until speeds.size) {
            if (speeds.drop(i).all { it < start * 0.98 }) {
                collapseAt = i + 1
                break
            }
        }
        return Fade(round1(pct), speeds.indexOf(speeds.min()) + 1, speeds.indexOf(speeds.max()) + 1, collapseAt)
    }

    /** Heart-rate drop during the first minute of each recovery (bpm). */
    private fun hrRecovery(a: IntervalAnalysis, allLaps: List<Lap>, samples: List<Sample>): HrRecovery? {
        val points = samples.filter { it.t != null && (it.hr ?: 0.0) > 0 }
        if (points.size < 20) return null
        val starts = mutableListOf<Double>()
        var t = 0.0
        for (l in allLaps) {
            starts += t
            t += l.duration ?: 0.0
        }
        fun hrAt(time: Double) = points.minBy { abs(it.t!! - time) }.hr!!
        val drops = mutableListOf<Double>()
        a.laps.forEachIndexed { i, info ->
            val next = a.laps.getOrNull(i + 1)
            if (info.role != LapRole.WORK || next?.role != LapRole.RECOVERY || (next.lap.duration ?: 0.0) < 50) return@forEachIndexed
            val idx = allLaps.indexOf(next.lap)
            if (idx < 0) return@forEachIndexed
            val end = starts[idx]
            val peak = points.filter { it.t!! >= end - 15 && it.t <= end }.maxOfOrNull { it.hr!! } ?: 0.0
            val after = hrAt(end + min(60.0, next.lap.duration!! - 2))
            if (peak > 0 && peak - after > 0) drops += peak - after
        }
        if (drops.size < 3) return null
        val half = drops.size / 2
        return HrRecovery(mean(drops).roundToInt(), mean(drops.take(half)).roundToInt(), mean(drops.takeLast(half)).roundToInt())
    }

    /* ---------- Verdict and text ---------- */

    private val verdictLabels = mapOf(
        "excellent" to "Excellente séance", "good" to "Séance réussie", "standard" to "Dans tes standards", "below" to "Un ton en dessous",
        "hard" to "Séance difficile", "first" to "Nouvelle référence", "controlled" to "Séance contrôlée",
    )
    private val verdictOrder = listOf("hard", "below", "standard", "good", "excellent")

    /** Pace index minus a small cost per bpm above the usual (capped: HR also depends on heat, recoveries). */
    private fun adjustedIndex(paceIndex: Double, hrDelta: Double?) = paceIndex - 0.12 * max(min(hrDelta ?: 0.0, 8.0), -8.0)

    /** Levels are relative to the athlete's own spread (robust SD of all session indices). */
    private fun verdictOf(paceIndex: Double?, hrDelta: Double?, fadePct: Double?, center: Double, spread: Double): String {
        if (paceIndex == null) return "first"
        val z = (adjustedIndex(paceIndex, hrDelta) - center) / spread
        // Slower, but with a clearly lower heart rate: an easier session by choice, not a failure
        if (z < -0.5 && hrDelta != null && hrDelta <= -4) return "controlled"
        var level = if (z >= 1.1) 4 else if (z >= 0.4) 3 else if (z > -0.6) 2 else if (z > -1.3) 1 else 0
        // A series that falls apart at the end is not a top session, whatever the average
        if (fadePct != null && fadePct <= -4 && level > 2) level -= 1
        return verdictOrder[level]
    }

    private fun groupName(g: RepGroup) = "${if (g.reps.size > 1) "${g.reps.size} × " else ""}${g.label}"

    private fun describePerformance(seed: Int, verdict: String, ia: IntervalAnalysis, s: SessionRecord): String {
        val g = mainGroup(ia)
        val name = groupName(g)
        val others = ia.groups.filter { it !== g }
        val gap = if (s.paceIndex == null) 0 else ((1000 / s.avgSpeed) * (abs(s.paceIndex) / 100)).roundToInt()
        val at = "à ${Txt.pace(g.avgSpeed)} de moyenne"
        val hr = s.hrDelta
        val hrClause = when {
            hr == null -> ""
            abs(hr) < 2 -> Txt.pick(seed + 1, listOf(", avec une FC comparable", ", pour une FC habituelle", ", cardio au même niveau que d'habitude"))
            hr < 0 -> Txt.pick(seed + 1, listOf(", avec ${dec(-hr, 0)} bpm de moins que d'habitude", " et une FC plus basse de ${dec(-hr, 0)} bpm"))
            else -> Txt.pick(seed + 1, listOf(", mais avec ${dec(hr, 0)} bpm de plus que d'habitude", ", au prix d'une FC plus haute de ${dec(hr, 0)} bpm"))
        }
        val extra = if (others.isNotEmpty()) " (plus ${Txt.join(others.map { groupName(it) })})" else ""

        if (verdict == "first") return "${Txt.capitalize(name)}$extra $at. Pas encore assez de répétitions de cette durée dans ton historique pour comparer : cette séance servira de référence."
        val faster = s.paceIndex!! >= 0
        val vs = if (gap == 0) "exactement à ton allure habituelle sur ce type de répétition" else "$gap s/km ${if (faster) "plus vite" else "plus lent"} que d'habitude sur ce format"
        return when (verdict) {
            "excellent" -> Txt.pick(seed, listOf("Très grosse séance. Tes $name$extra sont passés $at, $vs$hrClause.", "${Txt.capitalize(name)}$extra $at : c'est $vs$hrClause.${if (s.paceIndex >= 6) " Une de tes meilleures sur ce format." else ""}"))
            "good" -> Txt.pick(seed, listOf("Bonne séance : $name$extra $at, $vs$hrClause.", "Tes $name$extra sont passés $at, soit $vs$hrClause."))
            "standard" -> Txt.pick(seed, listOf("${Txt.capitalize(name)}$extra $at, $vs$hrClause. Une séance dans tes standards.", "Séance conforme à ce que tu fais d'habitude : $name$extra $at, $vs$hrClause."))
            "below" -> Txt.pick(seed, listOf("Séance un peu en dessous : $name$extra $at, $vs$hrClause.", "Tes $name$extra sont passés $at, $vs$hrClause. Pas une mauvaise séance, mais pas ton meilleur jour."))
            "controlled" -> "${Txt.capitalize(name)}$extra $at, $vs, mais ta FC aussi était plus basse (${dec(-(s.hrDelta ?: 0.0), 0)} bpm de moins). Plutôt une séance gérée qu'un jour sans : l'intensité était simplement plus basse."
            else -> Txt.pick(seed, listOf("Séance compliquée : $name$extra $at, $vs$hrClause.", "Jour sans sur ces $name$extra : $at, $vs$hrClause."))
        }
    }

    private fun describeCourse(seed: Int, ia: IntervalAnalysis, recovery: HrRecovery?): String? {
        val g = mainGroup(ia)
        val fade = fadeOf(g)
        val parts = mutableListOf<String>()
        if (fade != null) {
            val speeds = g.reps.map { speedOf(it.lap) }
            val spread = (1000 / speeds.min() - 1000 / speeds.max()).roundToInt()
            if (fade.pct <= -3) {
                parts += if (fade.collapseAt != null) Txt.pick(
                    seed + 2,
                    listOf(
                        "Ça s'est gâté à partir de la ${Txt.ordinal(fade.collapseAt)} répétition : la fin de série est ${dec(-fade.pct)} % plus lente que le début.",
                        "Le début était bon, mais à partir de la ${Txt.ordinal(fade.collapseAt)} tu n'as plus retrouvé ton allure (${dec(-fade.pct)} % plus lent sur la fin).",
                    ),
                ) else "Tu as perdu ${dec(-fade.pct)} % d'allure entre le début et la fin de la série."
            } else if (fade.pct >= 1.5) {
                parts += Txt.pick(
                    seed + 2,
                    listOf(
                        "Tu as fini plus vite que tu n'as commencé (${dec(fade.pct)} % plus rapide sur le dernier tiers), signe qu'il restait de la marge.",
                        "Belle gestion : le dernier tiers de la série est ${dec(fade.pct)} % plus rapide que le premier.",
                    ),
                )
            } else if (spread <= 4) {
                parts += Txt.pick(seed + 2, listOf("Régularité de métronome : $spread s/km seulement entre la plus rapide et la plus lente.", "Série très régulière, $spread s/km d'écart en tout."))
            }
        }
        if (recovery != null) {
            val trend = recovery.last - recovery.first
            // Only worth a sentence when it degrades: the average is already shown as a figure
            if (trend <= -6) parts += "Pendant les récups, ta FC redescendait de ${recovery.first} bpm en une minute au début, puis seulement de ${recovery.last} bpm sur la fin : la fatigue s'installait."
        }
        return if (parts.isNotEmpty()) parts.joinToString(" ") else null
    }

    private fun factorDetail(key: String, value: Double, typical: Double, factors: List<Factor>): String {
        val f = factors.firstOrNull { it.key == key }
        if (f != null) return if (f.baselineDisplay != null) "${f.display} (${f.note})" else f.display
        val spec = ctxSpecs.first { it.key == key }
        return "${spec.format(value)} (d'habitude ${spec.format(typical)})"
    }

    /** Estimated effect of each unusual context value on this session's pace. */
    private fun contributions(s: SessionRecord, list: List<SessionRecord>, correlations: List<Correlation>): List<Contribution> {
        val result = mutableListOf<Contribution>()
        for (spec in ctxSpecs) {
            val value = s.context[spec.key] ?: continue
            if (spec.key == "hour") continue
            // A value close to the usual one explains nothing, whatever the slope
            val factor = s.factors.firstOrNull { it.key == spec.key }
            if (factor != null && factor.baseline != null && abs(factor.z) < 0.5) continue
            val values = list.mapNotNull { it.context[spec.key] }
            val typical = if (values.size >= 5) median(values) else if (spec.key == "hrv" || spec.key == "restingHr") 0.0 else value
            val corr = correlations.firstOrNull { it.key == spec.key }
            var effect = spec.prior(value, typical)
            var source = "études"
            if (corr != null && corr.strength != "aucune") {
                // Personal slope, % of pace per unit, shrunk toward zero when the link is weak
                val pairs = list.filter { it.paceIndex != null && it.context[spec.key] != null }
                val slope = slopeOf(pairs.map { it.context[spec.key]!! }, pairs.map { it.paceIndex!! })
                val spread = sd(values).takeIf { it != 0.0 } ?: 1.0
                val deviation = (value - typical).coerceIn(-2.5 * spread, 2.5 * spread)
                effect = slope * deviation * (if (corr.strength == "nette") 1.0 else 0.6)
                source = "perso"
            }
            if (abs(effect) < 0.4) continue
            effect = round1(effect)
            result += Contribution(spec.key, spec.label, factorDetail(spec.key, value, typical, s.factors), effect, ((1000 / s.avgSpeed) * (-effect / 100)).roundToInt(), source)
        }
        return result.sortedByDescending { abs(it.effectPct) }
    }

    /** "une nuit de 5 h 40, 1 h 30 de moins que d'habitude" */
    private fun phraseOf(c: Contribution, s: SessionRecord): String {
        val f = s.factors.firstOrNull { it.key == c.key }
        val v = s.context[c.key]!!
        return when (c.key) {
            "sleep" -> if (f != null) "une nuit de ${f.display}, ${f.note}" else "une nuit courte"
            "sleepScore" -> if (f != null) "un score de sommeil de ${f.display}" else "un sommeil de mauvaise qualité"
            "hrv" -> if (f != null) "une VFC à ${f.display}, ${f.note}${if (f.z <= -1) " (sous ta fourchette normale)" else if (f.z >= 1) " (dans le haut de ta fourchette)" else ""}" else "une VFC basse"
            "restingHr" -> if (f != null) "une FC au repos à ${f.display}, ${f.note}" else "une FC au repos élevée"
            "bodyBattery" -> if (f != null) "une Body Battery à ${f.display} au réveil" else "une Body Battery basse"
            "stress" -> if (f == null) (if (c.effectPct < 0) "une journée stressante" else "une journée calme")
            else if (c.effectPct < 0) "une journée stressante (${f.display}, ${f.note})" else "une journée calme (stress à ${f.display})"
            "fatigue" -> "une fatigue d'entraînement à ${dec(v)}/10"
            "rest" -> if (v <= 1) (if (v == 0.0) "une autre séance dure le même jour" else "une séance dure la veille") else "${Txt.plural(v.roundToInt(), "jour")} de récup depuis la dernière séance dure"
            "heat" -> "de la chaleur (${v.roundToInt()} °C à la montre)"
            else -> c.label.lowercase()
        }
    }

    private fun lowerFirst(s: String) = s.replaceFirstChar { it.lowercase() }

    private fun describeWhy(seed: Int, verdict: String, s: SessionRecord, contribs: List<Contribution>, correlations: List<Correlation>): String {
        val negatives = contribs.filter { it.effectPct < 0 }
        val positives = contribs.filter { it.effectPct > 0 }
        fun personal(key: String) = correlations.firstOrNull { it.key == key && it.strength != "aucune" }
        val poor = verdict == "below" || verdict == "hard"
        val great = verdict == "excellent" || verdict == "good"
        val sentences = mutableListOf<String>()

        fun quantified(list: List<Contribution>): String {
            val secs = list.sumOf { abs(it.effectSecPerKm) }
            if (secs < 1) return ""
            return if (list.size > 1) " ${Txt.pick(seed + 5, listOf("À eux deux, ils pèsent", "Ensemble, ça représente"))} environ $secs s/km." else " Ça pèse à peu près $secs s/km."
        }

        if (poor && negatives.isNotEmpty()) {
            val top = negatives.take(2)
            val reasons = Txt.join(top.map { phraseOf(it, s) })
            sentences += Txt.pick(seed + 4, listOf("Le contexte explique une bonne partie de l'écart : $reasons.", "Ce n'est pas un hasard : $reasons.", "Il y a une raison : $reasons.")) + quantified(top)
            val link = top.mapNotNull { personal(it.key) }.firstOrNull()
            if (link != null) sentences += "Ce n'est pas qu'une impression : ${lowerFirst(link.text)}"
            else if (top.any { it.key == "sleep" }) sentences += "Les études sur le manque de sommeil vont dans le même sens : ce sont justement les efforts intenses et répétés qui en souffrent le plus, surtout en fin de journée."
        } else if (poor) {
            sentences += Txt.pick(
                seed + 4,
                listOf(
                    "Pourtant rien d'anormal côté récupération : sommeil, VFC et FC au repos étaient dans ta norme. Ça arrive, une séance moyenne isolée ne veut rien dire.",
                    "Côté récupération, rien ne l'explique : nuit, VFC et FC au repos étaient normales. Si ça se répète, c'est plutôt la charge des dernières semaines qu'il faudra regarder.",
                ),
            )
        } else if (great && negatives.isNotEmpty()) {
            val reasons = Txt.join(negatives.take(2).map { phraseOf(it, s) })
            sentences += Txt.pick(seed + 4, listOf("Et ça malgré $reasons.", "D'autant plus solide que le contexte n'aidait pas : $reasons."))
        } else if (great && positives.isNotEmpty()) {
            val reasons = Txt.join(positives.take(2).map { phraseOf(it, s) })
            sentences += Txt.pick(seed + 4, listOf("Le terrain était favorable : $reasons.", "Tu arrivais bien récupéré, avec $reasons."))
            val link = positives.mapNotNull { personal(it.key) }.firstOrNull()
            if (link != null) sentences += "Ça colle avec ton historique : ${lowerFirst(link.text)}"
        } else if (negatives.isNotEmpty()) {
            sentences += "À noter : ${Txt.join(negatives.take(2).map { phraseOf(it, s) })}, sans que ça t'empêche de tenir tes allures."
        }
        return sentences.joinToString(" ")
    }

    private fun advice(seed: Int, verdict: String, contribs: List<Contribution>, fade: Fade?): String? {
        val top = contribs.firstOrNull { it.effectPct < 0 }
        if ((verdict == "below" || verdict == "hard") && top != null) {
            return when (top.key) {
                "sleep", "sleepScore" -> Txt.pick(
                    seed + 6,
                    listOf(
                        "Après une nuit aussi courte, mieux vaut garder la séance mais enlever une ou deux répétitions, ou la décaler d'un jour.",
                        "Une piste simple : les veilles de fractionné, viser au moins ta durée de sommeil habituelle.",
                    ),
                )
                "hrv", "restingHr" -> "Quand la VFC sort de ta fourchette basse ou que la FC au repos grimpe, une séance plus courte ou un footing passe souvent mieux que le plan prévu."
                "rest" -> "Laisse au moins un jour facile entre deux séances dures : c'est souvent le plus rentable."
                "fatigue" -> "La charge des derniers jours était élevée : une semaine un peu plus légère te rendra tes allures."
                "heat" -> "Par forte chaleur, raisonne à l'effort (FC) plutôt qu'à l'allure, ou cours plus tôt."
                else -> null
            }
        }
        if (fade != null && fade.pct <= -4 && verdict != "hard") return "Pour la prochaine, pars 2 ou 3 s/km plus prudemment sur les premières : la série entière sera plus rapide."
        return null
    }

    /** Full analysis of an interval session, or null for a steady run. [samples] and [laps] are the ones of the activity. */
    fun analyze(activityId: Long, samples: List<Sample>, laps: List<Lap>): SessionAnalysis? {
        val session = sessions.firstOrNull { it.id == activityId } ?: return null
        val others = sessions.filter { it !== session }
        val refSpeed = median(sessions.map { it.avgSpeed })
        val correlations = ctxSpecs.mapNotNull { correlate(it, others, refSpeed) }.sortedByDescending { abs(it.rho) }

        val seed = Txt.seedOf(activityId)
        val contribs = contributions(session, others, correlations)
        val fade = fadeOf(mainGroup(session.analysis))
        val indices = sessions.filter { it.paceIndex != null }.map { adjustedIndex(it.paceIndex!!, it.hrDelta) }
        val center = if (indices.size >= 8) median(indices) else 0.0
        val spread = if (indices.size >= 8) max(1.4826 * median(indices.map { abs(it - center) }), 1.5) else 3.0
        val verdict = verdictOf(session.paceIndex, session.hrDelta, fade?.pct, center, spread)
        // Detected again on the laps passed in: the lap start times are looked up by object identity
        val ownAnalysis = Intervals.analyze(laps)
        val recovery = ownAnalysis?.let { hrRecovery(it, laps, samples) }

        val paragraphs = listOfNotNull(
            describePerformance(seed, verdict, session.analysis, session), describeCourse(seed, session.analysis, recovery),
            describeWhy(seed, verdict, session, contribs, correlations).takeIf { it.isNotEmpty() }, advice(seed, verdict, contribs, fade),
        )
        return SessionAnalysis(
            verdict, verdictLabels.getValue(verdict), session.paceIndex, session.paceIndex?.let { ((1000 / session.avgSpeed) * (-it / 100)).roundToInt() }, session.hrDelta, fade?.pct, recovery,
            session.reps, session.factors, contribs, correlations, others.count { it.paceIndex != null }, paragraphs, paragraphs[0],
        )
    }
}
