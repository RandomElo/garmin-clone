package fr.foulee.app

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Role of a lap in an interval session */
enum class LapRole(val label: String) { WARMUP("Échauffement"), WORK("Effort"), RECOVERY("Récup"), COOLDOWN("Retour au calme"), OTHER("–") }

/** [kind]: index in [IntervalAnalysis.recoveryKinds], set on a recovery lap and on the effort that precedes it. */
class LapInfo(val lap: Lap, val role: LapRole, var group: Int? = null, var rep: Int? = null, var kind: Int? = null)

/** One type of recovery (90 s, 200 m…): a session can have several. [measured]: counted in distance rather than time. */
class RecoveryKind(val label: String, val measured: Boolean, val laps: List<LapInfo>)

/** [measured]: counted in distance (true), in time (false), or both kinds in one group (null). */
class Recovery(val duration: Double, val distance: Double, val speed: Double, val label: String, val measured: Boolean? = null)

class RepGroup(
    val label: String, val timeBased: Boolean, val reps: List<LapInfo>, val avgSpeed: Double, val avgDuration: Double, val avgDistance: Double,
    val avgHr: Int?, val bestSpeed: Double, val worstSpeed: Double, val recovery: Recovery?,
)

class IntervalAnalysis(val title: String, val structured: Boolean, val groups: List<RepGroup>, val laps: List<LapInfo>, val recoveryKinds: List<RecoveryKind> = emptyList())

/**
 * Detects an interval session from the watch laps and groups the repetitions by type
 * (e.g. 3 × 1000 m + 4 × 200 m), with averages per type. Null for a steady run. Port of the former backend (intervals.ts).
 */
object Intervals {
    // Minimum speed ratio between efforts and recoveries for manual laps (auto-lap runs stay below)
    private const val MIN_GAP = 1.12
    // Reps of the same type: distances within 15 %
    private const val SAME_DISTANCE = 0.15
    private const val NBSP = " "

    private fun mean(v: List<Double>) = v.sum() / v.size
    private fun median(v: List<Double>): Double {
        val s = v.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2
    }
    private fun cv(v: List<Double>): Double {
        val m = mean(v)
        return if (m != 0.0) sqrt(mean(v.map { (it - m) * (it - m) })) / m else 0.0
    }

    private fun roundStep(d: Double) = if (d >= 2950) 500 else if (d >= 950) 100 else if (d >= 150) 50 else 10
    private fun roundDistance(d: Double): Double = (d / roundStep(d)).roundToInt() * roundStep(d).toDouble()

    private fun nominalDistance(d: Double): String {
        val r = roundDistance(d)
        return if (r >= 3000) "${Insights.dec(r / 1000, 1)}${NBSP}km" else "${r.roundToInt()}${NBSP}m"
    }

    /** Distance reps land close to a round distance (1000 m, 400 m…), timed reps usually do not. */
    private fun onRoundDistance(d: Double) = abs(d - roundDistance(d)) / roundDistance(d) <= 0.025

    private fun nominalDuration(s: Double): String {
        val rounded = (s / 5).roundToInt() * 5
        val m = rounded / 60
        val sec = rounded % 60
        if (m == 0) return "$sec${NBSP}s"
        return if (sec != 0) "$m${NBSP}min${NBSP}${sec.toString().padStart(2, '0')}" else "$m${NBSP}min"
    }

    private fun isTiny(l: Lap) = (l.distance ?: 0.0) < 30 && (l.duration ?: 0.0) < 15
    private fun speedOf(l: Lap) = if ((l.duration ?: 0.0) > 0) (l.distance ?: 0.0) / l.duration!! else 0.0

    /** Roles from Garmin's tags, when the watch ran a structured workout. */
    private fun structuredRoles(laps: List<Lap>): List<LapRole>? {
        if (laps.none { it.intensityType != null }) return null
        val roles = laps.map { l ->
            if (isTiny(l)) LapRole.OTHER else when (l.intensityType) {
                "WARMUP" -> LapRole.WARMUP
                "COOLDOWN" -> LapRole.COOLDOWN
                "REST", "RECOVERY" -> LapRole.RECOVERY
                "ACTIVE", "INTERVAL" -> LapRole.WORK
                else -> LapRole.OTHER
            }
        }
        if (roles.count { it == LapRole.WORK } < 2) return null
        // Some watches tag the pause between two efforts "cooldown" (or "warmup"): between two efforts it is a recovery
        val first = roles.indexOf(LapRole.WORK)
        val last = roles.lastIndexOf(LapRole.WORK)
        return roles.mapIndexed { i, r -> if (i in (first + 1) until last && (r == LapRole.COOLDOWN || r == LapRole.WARMUP)) LapRole.RECOVERY else r }
    }

    /** Manual laps: efforts are the laps clearly faster than the rest (largest speed gap). */
    private fun inferredRoles(laps: List<Lap>): List<LapRole>? {
        val candidates = laps.filter { !isTiny(it) && speedOf(it) > 0 }
        val speeds = candidates.map { speedOf(it) }.distinct().sortedDescending()
        var threshold = 0.0
        var bestGap = 0.0
        for (i in 0 until speeds.size - 1) {
            val gap = speeds[i] / speeds[i + 1]
            if (gap > bestGap) {
                bestGap = gap
                threshold = (speeds[i] + speeds[i + 1]) / 2
            }
        }
        val fast = candidates.count { speedOf(it) > threshold }
        if (bestGap < MIN_GAP || fast < 2 || fast == candidates.size) return null

        val roles = laps.map { if (isTiny(it)) LapRole.OTHER else if (speedOf(it) > threshold) LapRole.WORK else LapRole.RECOVERY }
        val first = roles.indexOf(LapRole.WORK)
        val last = roles.lastIndexOf(LapRole.WORK)
        return roles.mapIndexed { i, r -> if (r != LapRole.RECOVERY) r else if (i < first) LapRole.WARMUP else if (i > last) LapRole.COOLDOWN else LapRole.RECOVERY }
    }

    private fun nearestMultiple(v: Double, step: Int) = (v / step).roundToInt() * step.toDouble()

    /** A timed effort ends a second or so from a round time (30, 45, 60 s…), whatever distance it covered. */
    private const val TIMED_TOLERANCE = 1.5
    private fun timedKey(l: Lap): Double? {
        val d = l.duration ?: return null
        val key = nearestMultiple(d, 15)
        return key.takeIf { d >= 15 && abs(d - key) <= TIMED_TOLERANCE }
    }

    /**
     * A session of timed efforts (6 × 1 min + 6 × 45 s + 6 × 30 s): nearly every effort ends on a round time and few sit on a round
     * distance. The watch cut the laps by time, so the distances (300, 200, 150 m…) only follow the pace and must not make the types.
     */
    private fun isTimedSession(work: List<LapInfo>): Boolean {
        if (work.size < 4 || work.count { timedKey(it.lap) != null } < work.size * 0.9) return false
        val clusters = work.filter { timedKey(it.lap) != null }.groupBy { timedKey(it.lap)!! }
        // Several round times; and in each one the distances wander (a few %): 1000 m run in 4:00 flat have steady distances, those are distance reps
        return clusters.size >= 2 && clusters.values.filter { it.size >= 3 }.all { cv(it.map { l -> l.lap.distance ?: 0.0 }) >= 0.025 }
    }

    /** Several efforts of one type that all end on a round time while their distances wander: timed efforts (6 × 1 min, 6 × 45 s). */
    private fun timedReps(durations: List<Double>, distances: List<Double>) =
        durations.size > 1 && durations.all { abs(it - nearestMultiple(it, 15)) <= TIMED_TOLERANCE } && cv(durations) < cv(distances) && !distances.all { onRoundDistance(it) }

    /** The watch ends a timed step on a round number of seconds, a measured one on a round distance. */
    private fun isRoundTime(t: Double) = t >= 15 && abs(t - nearestMultiple(t, 15)) <= 3.5

    /** A recovery distance is written in steps of 50 m ("100 m"): the lap ends a little after the mark, so slightly over is expected. */
    private fun nearest50(d: Double) = maxOf(50.0, (d / 50).roundToInt() * 50.0)
    private fun isRoundMeasure(d: Double) = d >= 70 && (d - nearest50(d)) / nearest50(d) in -0.1..0.15

    /**
     * Timed or measured? Eloi's recoveries are timed far more often (50 of his 64 written sessions): measured only when the laps of a
     * group sit on round distances without sitting on round times, or when the distance is clearly the steadier of the two.
     * Tuned against what he writes in Strava, with scripts/analyse-recuperations.py (93 % right, against 73 % for the former rule).
     */
    private fun isMeasuredGroup(laps: List<Lap>): Boolean {
        // A single lap proves nothing, and timed is the usual case
        if (laps.size == 1) return false
        val timeFit = laps.count { isRoundTime(it.duration!!) }.toDouble() / laps.size
        val distFit = laps.count { isRoundMeasure(it.distance!!) }.toDouble() / laps.size
        if (distFit >= 0.75 && timeFit < 0.5) return true
        if (timeFit >= 0.5 && distFit < 0.75) return false
        if (laps.size >= 3) {
            val distances = laps.map { it.distance!! }
            return cv(distances) < 0.07 && cv(distances) < cv(laps.map { it.duration!! }) * 0.7
        }
        return false
    }

    /** The lap of a timed recovery ends a few seconds after the prescribed time: 2:38 is 2 min 30, 3:02 is 3 min. */
    private fun prescribedTime(t: Double) = if (t >= 45) floor((t + 5) / 15) * 15 else t

    /**
     * Recoveries that follow an effort, sorted into kinds. Each group of efforts decides once whether its recoveries are timed or
     * measured (a session can mix 2 min 30 and 100 m). Inside a mode, close values form one kind (GPS noise on 200 m must not
     * give 190 m and 210 m).
     */
    private fun classifyRecoveries(infos: List<LapInfo>, bucketLabels: List<String>, recoveryModes: Map<String, Boolean>): List<RecoveryKind> {
        val recoveries = infos.indices.filter { infos[it].role == LapRole.RECOVERY && infos.getOrNull(it - 1)?.role == LapRole.WORK }
        if (recoveries.isEmpty()) return emptyList()
        val byGroup = recoveries.groupBy { infos[it - 1].group }
        val measuredGroups = byGroup.mapValues { (g, idx) -> g?.let { recoveryModes[bucketLabels[it]] } ?: isMeasuredGroup(idx.map { infos[it].lap }) }
        val measured = recoveries.map { measuredGroups[infos[it - 1].group] == true }

        val kinds = mutableListOf<RecoveryKind>()
        for (mode in listOf(true, false)) {
            val members = recoveries.indices.filter { measured[it] == mode }.sortedBy { value(infos[recoveries[it]].lap, mode) }
            var cluster = mutableListOf<Int>()
            fun close() {
                if (cluster.isEmpty()) return
                val values = cluster.map { value(infos[recoveries[it]].lap, mode) }
                val median = median(values)
                val label = if (mode) nominalRecoveryDistance(median) else nominalDuration(prescribedTime(median))
                kinds += RecoveryKind(label, mode, cluster.map { infos[recoveries[it]] })
                cluster = mutableListOf()
            }
            for (m in members) {
                if (cluster.isNotEmpty() && value(infos[recoveries[m]].lap, mode) > value(infos[recoveries[cluster.last()]].lap, mode) * 1.15) close()
                cluster += m
            }
            close()
        }
        // Kinds in the order they appear in the session
        val ordered = kinds.sortedBy { k -> k.laps.minOf { infos.indexOf(it) } }
        ordered.forEachIndexed { ki, k ->
            for (info in k.laps) {
                info.kind = ki
                infos.getOrNull(infos.indexOf(info) - 1)?.kind = ki
            }
        }
        return ordered
    }

    /** The label of a type of efforts ("1000 m", "1 min 30"): timed when their durations are round and steadier than their distances. */
    private fun effortLabel(reps: List<LapInfo>, timedSession: Boolean): String {
        val durations = reps.map { it.lap.duration!! }
        val distances = reps.map { it.lap.distance!! }
        return if (timedSession || timedReps(durations, distances)) nominalDuration(mean(durations)) else nominalDistance(mean(distances))
    }

    private fun nominalRecoveryDistance(d: Double) = if (d < 950) "${nearest50(d).roundToInt()}${NBSP}m" else nominalDistance(d)

    private fun value(l: Lap, measured: Boolean) = if (measured) l.distance!! else l.duration!!

    /** Marks a lap the app cut itself out of a longer one (the watch was not split there). */
    const val APP_SPLIT = -7

    /**
     * The watch was not split at the end of an effort: its lap swallowed the next effort (a long "recovery" lap with a burst of speed inside).
     * The burst is found on the speed curve and cut out as an effort of its own, with the duration of the efforts it looks like.
     * Only laps of a session already seen as intervals are touched, and nothing about pauses or stops is used to change a pace.
     * Returns the new laps, or null when nothing had to be cut.
     */
    fun repairLaps(laps: List<Lap>, samples: List<Sample>): List<Lap>? {
        if (laps.any { it.distance == null || (it.duration ?: 0.0) <= 0 } || laps.any { it.stepIndex == APP_SPLIT }) return null
        val analysis = analyze(laps)?.takeIf { !it.structured } ?: return null
        val work = analysis.laps.filter { it.role == LapRole.WORK }.map { it.lap }
        if (work.size < 4) return null
        val medianDuration = median(work.map { it.duration!! })
        val threshold = 0.92 * median(work.map { speedOf(it) })
        val points = samples.filter { it.d != null && it.t != null && it.speed != null }
        if (points.size < 10) return null

        val out = mutableListOf<Lap>()
        var startD = 0.0
        var changed = false
        analysis.laps.forEach { info ->
            val lap = info.lap
            val endD = startD + lap.distance!!
            val long = info.role != LapRole.WORK && lap.duration!! >= maxOf(2.5 * medianDuration, 90.0)
            val bursts = if (long) burstsIn(points.filter { it.d!! > startD && it.d <= endD }, threshold, work) else emptyList()
            if (bursts.isEmpty()) {
                out += lap
            } else {
                changed = true
                var cursor = startD
                var restDuration = lap.duration!!
                for (b in bursts) {
                    val pre = b.startD - cursor
                    if (pre >= 20) {
                        val preDuration = lap.duration!! * pre / lap.distance
                        out += Lap(pre, preDuration, pre / preDuration, lap.averageHr, lap.maxHr, null, null, null)
                        restDuration -= preDuration
                    }
                    out += Lap(b.endD - b.startD, b.duration, (b.endD - b.startD) / b.duration, b.hr, b.maxHr, null, null, APP_SPLIT)
                    restDuration -= b.duration
                    cursor = b.endD
                }
                val rest = endD - cursor
                if (rest >= 20 && restDuration > 5) out += Lap(rest, restDuration, rest / restDuration, lap.averageHr, lap.maxHr, null, null, null)
            }
            startD = endD
        }
        return if (changed) out else null
    }

    private class Burst(val startD: Double, val endD: Double, val duration: Double, val hr: Double?, val maxHr: Double?)

    /** Stretches of the curve at the pace of the efforts, lasting about as long as one of them. */
    private fun burstsIn(points: List<Sample>, threshold: Double, work: List<Lap>): List<Burst> {
        val shortest = work.minOf { it.duration!! }
        val longest = work.maxOf { it.duration!! }
        val found = mutableListOf<Burst>()
        var from = -1
        fun close(toIndex: Int) {
            if (from < 0) return
            // The start and the end of an effort are slower than its middle: keep the neighbours that are close to the pace
            var first = from
            var to = toIndex
            while (first > 0 && toIndex - first < 40 && from - first < 2 && points[first - 1].speed!! >= 0.75 * threshold) first--
            while (to < points.lastIndex && to - toIndex < 2 && points[to + 1].speed!! >= 0.75 * threshold) to++
            val run = points.subList(first, to + 1)
            val span = run.last().t!! - run.first().t!!
            val step = if (run.size > 1) span / (run.size - 1) else 4.0
            val seconds = span + step
            from = -1
            if (seconds < maxOf(15.0, 0.6 * shortest) || seconds > 1.5 * longest) return
            // The duration is the one of the efforts it looks like (28 s on the curve: a 30 s effort)
            val like = work.map { it.duration!! }.minByOrNull { abs(it - seconds) }!!
            val duration = if (abs(like - seconds) / like <= 0.25) like else seconds
            val hrs = run.mapNotNull { it.hr }
            found += Burst(run.first().d!!, run.last().d!!, duration, hrs.takeIf { it.isNotEmpty() }?.average(), hrs.maxOrNull())
        }
        points.forEachIndexed { i, p ->
            val fast = p.speed!! >= threshold
            val gap = if (i > 0) p.t!! - points[i - 1].t!! else 0.0
            if (from >= 0 && (!fast || gap > 10)) close(i - 1)
            if (fast && from < 0) from = i
        }
        close(points.lastIndex)
        return found.take(3)
    }

    /**
     * Remarks about what the watch recorded, for the session card: efforts the app had to cut out of a long lap, and a watch that was not
     * stopped after the session (a pause, then another run at a calmer pace in the same recording). Nothing here changes any figure.
     */
    fun notes(laps: List<Lap>, samples: List<Sample>): List<String> {
        val out = mutableListOf<String>()
        val cut = laps.filter { it.stepIndex == APP_SPLIT }
        if (cut.isNotEmpty()) out += "La montre n'a pas été découpée à ${if (cut.size > 1) "${cut.size} efforts" else "un effort"} (${cut.joinToString(", ") { nominalDuration(it.duration!!) }}) : ${if (cut.size > 1) "retrouvés" else "retrouvé"} sur la courbe d'allure et ajouté${if (cut.size > 1) "s" else ""} aux répétitions."
        val analysis = analyze(laps) ?: return out
        val work = analysis.laps.filter { it.role == LapRole.WORK }
        if (work.isEmpty()) return out
        val lastWork = work.last()
        val endD = analysis.laps.takeWhile { it !== lastWork }.sumOf { it.lap.distance ?: 0.0 } + (lastWork.lap.distance ?: 0.0)
        val points = samples.filter { it.d != null && it.t != null && it.speed != null && it.d > endD }
        // A pause (speed at zero for several seconds) after at least a minute of cooldown, then a real run of 4 minutes or more
        val stopAt = points.indices.firstOrNull { i ->
            val j = (i until points.size).firstOrNull { points[it].speed!! >= 0.3 } ?: points.size
            points[i].speed!! < 0.3 && (i == 0 || points[i - 1].speed!! >= 0.3) && j > i + 1 && points[i].t!! - points.first().t!! >= 60 && j < points.size &&
                points.last().t!! - points[j].t!! >= 240 && points.subList(j, points.size).count { it.speed!! >= 1.5 } >= (points.size - j) * 0.9
        } ?: return out
        val after = points.subList((stopAt until points.size).first { points[it].speed!! >= 0.3 }, points.size)
        val meters = after.last().d!! - after.first().d!!
        val seconds = after.last().t!! - after.first().t!!
        if (meters > 500 && seconds > 0) out += "La montre n'a pas été arrêtée après le fractionné : après le retour au calme et une pause, ${Fmt.km(meters)} ont continué à ${Fmt.pace(meters / seconds)}. C'est une autre sortie dans le même enregistrement."
        return out
    }

    /**
     * [recoveryModes]: what Eloi chose by hand, by label of the efforts ("200 m", "1 min"): true = the recoveries of that type are
     * measured in distance, false = timed. Without a choice the rule in [isMeasuredGroup] decides.
     */
    fun analyze(laps: List<Lap>, recoveryModes: Map<String, Boolean> = emptyMap()): IntervalAnalysis? {
        val usable = laps.filter { it.distance != null && (it.duration ?: 0.0) > 0 }
        if (usable.size < 3) return null

        val tagged = structuredRoles(usable)
        val roles = tagged ?: inferredRoles(usable) ?: return null
        val infos = usable.mapIndexed { i, lap -> LapInfo(lap, roles[i]) }
        // Efforts of 5 km or more only are a long steady run cut in a few pieces, not an interval session
        if (infos.filter { it.role == LapRole.WORK }.all { (it.lap.distance ?: 0.0) >= 5000 }) return null

        val timedSession = tagged == null && isTimedSession(infos.filter { it.role == LapRole.WORK })
        // Group efforts: by workout step when known, otherwise by similar distance (or by round time in a timed session)
        class Bucket(val step: Int?, val roundKey: Double?, val timedKey: Double?, val distances: MutableList<Double> = mutableListOf(), val reps: MutableList<LapInfo> = mutableListOf())
        // Reps on a round distance are the same type only on the same round distance (1000 m and 1100 m are not); the others by similarity
        fun sameType(b: Bucket, d: Double): Boolean {
            if (b.roundKey != null && onRoundDistance(d)) return roundDistance(d) == b.roundKey
            return abs(d - mean(b.distances)) / mean(b.distances) < SAME_DISTANCE
        }
        val buckets = mutableListOf<Bucket>()
        for (info in infos) {
            if (info.role != LapRole.WORK) continue
            val d = info.lap.distance!!
            val step = if (tagged != null) info.lap.stepIndex else null
            // Efforts that end on a round time are one type per time (6 × 1 min, 6 × 45 s), even when the distances differ by 10 %
            val timed = if (step == null && timedSession) timedKey(info.lap) else null
            var bucket = if (step != null) buckets.firstOrNull { it.step == step }
            else buckets.firstOrNull { it.step == null && if (timed != null) it.timedKey == timed else it.timedKey == null && sameType(it, d) }
            if (bucket == null) {
                bucket = Bucket(step, if (onRoundDistance(d)) roundDistance(d) else null, timed)
                buckets += bucket
            }
            bucket.distances += d
            bucket.reps += info
            info.group = buckets.indexOf(bucket)
            info.rep = bucket.reps.size
        }

        // Efforts are grouped first: the recoveries of one group follow the same rule (timed or measured)
        val recoveryKinds = classifyRecoveries(infos, buckets.map { effortLabel(it.reps, timedSession) }, recoveryModes)

        fun describeRecovery(laps: List<LapInfo>, kinds: List<RecoveryKind>): Recovery {
            val durations = laps.map { it.lap.duration!! }
            val distances = laps.map { it.lap.distance!! }
            val labels = laps.mapNotNull { it.kind?.let { k -> kinds[k].label } }.distinct()
            val fallback = nominalDuration(nearestMultiple(median(durations), 15))
            val modes = laps.mapNotNull { it.kind?.let { k -> kinds[k].measured } }.distinct()
            return Recovery(mean(durations), mean(distances), distances.sum() / durations.sum(), if (labels.isNotEmpty()) labels.joinToString(" / ") else fallback, modes.singleOrNull())
        }

        fun buildGroup(reps: List<LapInfo>): RepGroup {
            val durations = reps.map { it.lap.duration!! }
            val distances = reps.map { it.lap.distance!! }
            // Timed reps (e.g. 30 s / 30 s): durations sit on round values and vary less than distances
            val timeBased = timedSession || timedReps(durations, distances)
            val speeds = reps.map { speedOf(it.lap) }
            val hrs = reps.mapNotNull { it.lap.averageHr }
            // Recovery = the recovery lap right after each rep
            val recoveries = reps.mapNotNull { infos.getOrNull(infos.indexOf(it) + 1)?.takeIf { next -> next.role == LapRole.RECOVERY } }
            return RepGroup(
                if (timeBased) nominalDuration(mean(durations)) else nominalDistance(mean(distances)), timeBased, reps,
                distances.sum() / durations.sum(), mean(durations), mean(distances), if (hrs.isNotEmpty()) mean(hrs).roundToInt() else null,
                speeds.max(), speeds.min(), if (recoveries.isNotEmpty()) describeRecovery(recoveries, recoveryKinds) else null,
            )
        }

        // Some watches store each rep of a workout as its own step: merge groups that end up with the same label
        val groups = mutableListOf<RepGroup>()
        for (group in buckets.map { buildGroup(it.reps) }) {
            val twin = groups.indexOfFirst { it.label == group.label && it.timeBased == group.timeBased }
            if (twin == -1) groups += group
            else groups[twin] = buildGroup((groups[twin].reps + group.reps).sortedBy { infos.indexOf(it) })
        }
        groups.forEachIndexed { gi, g -> g.reps.forEachIndexed { ri, r -> r.group = gi; r.rep = ri + 1 } }

        val title = groups.joinToString(" + ") { g -> "${if (g.reps.size > 1) "${g.reps.size} × " else ""}${g.label}${g.recovery?.let { " (r$NBSP: ${it.label})" } ?: ""}" }
        return IntervalAnalysis(title, tagged != null, groups, infos, recoveryKinds)
    }
}
