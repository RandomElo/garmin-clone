package fr.foulee.app

import org.json.JSONObject
import java.time.LocalDate
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/** Pulls activities and daily wellness from Garmin into the local database. */
object Sync {
    private const val PAGE = 100
    private const val MAX_ACTIVITIES = 800
    const val WINDOW_DAYS = 365L // enough for the 60-night HRV baseline
    private const val DELAY_MS = 120L
    // Calls of one activity or one day already go in parallel inside: a few workers on top make the sync several times faster without a burst Garmin refuses
    private const val DETAIL_WORKERS = 3
    private const val DAY_WORKERS = 2

    private fun JSONObject?.dbl(key: String): Double? = if (this == null || !has(key) || isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }

    /** Runs the calls at the same time and returns their results in order. An authentication error from any of them is rethrown. */
    /** Runs [block] on every item with [workers] threads; the first failure (an expired session, say) stops the others and is thrown. */
    private fun <T> forEachParallel(items: List<T>, workers: Int, block: (T) -> Unit) {
        val queue = java.util.concurrent.ConcurrentLinkedQueue(items)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val threads = (1..minOf(workers, items.size).coerceAtLeast(1)).map {
            thread {
                while (failure.get() == null) {
                    val item = queue.poll() ?: break
                    try { block(item) } catch (t: Throwable) { failure.compareAndSet(null, t) }
                }
            }
        }
        threads.forEach { it.join() }
        failure.get()?.let { throw it }
    }

    private fun <T> inParallel(vararg blocks: () -> T): List<T> {
        val results = arrayOfNulls<Any?>(blocks.size)
        val failures = arrayOfNulls<Throwable>(blocks.size)
        val threads = blocks.mapIndexed { i, block -> thread { try { results[i] = block() } catch (t: Throwable) { failures[i] = t } } }
        threads.forEach { it.join() }
        failures.firstOrNull { it != null }?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return results.toList() as List<T>
    }

    /** Garmin uses negative values for "not enough data" */
    private fun JSONObject?.positive(key: String): Double? = dbl(key)?.takeIf { it >= 0 }

    /** Number of activities on Garmin. */
    fun garminTotal(auth: GarminAuth): Int = auth.get("/activitylist-service/activities/count").optInt("totalCount", 0)

    /** A light look at Garmin for the background check: the ids of the latest runs (one small call). */
    fun recentRunIds(auth: GarminAuth, count: Int = 5): List<Long> {
        val items = auth.get("/activitylist-service/activities/search/activities?start=0&limit=$count").getJSONArray("items")
        return (0 until items.length()).map { items.getJSONObject(it) }
            .filter { it.optJSONObject("activityType")?.optString("typeKey")?.contains("running") == true }
            .map { it.getLong("activityId") }
    }

    /** The latest activities, or the whole history (up to [MAX_ACTIVITIES]) when [all]. [progress] gets (done, total). */
    fun activities(auth: GarminAuth, db: Db, all: Boolean, total: Int = 0, progress: (Int, Int) -> Unit = { _, _ -> }) {
        val wanted = if (all) min(max(total, 1), MAX_ACTIVITIES) else 20
        var start = 0
        while (start < wanted) {
            val limit = if (all) PAGE else wanted
            val items = auth.get("/activitylist-service/activities/search/activities?start=$start&limit=$limit").getJSONArray("items")
            for (i in 0 until items.length()) {
                val a = items.getJSONObject(i)
                db.upsert(
                    ActivityRow(
                        a.getLong("activityId"), a.optString("startTimeLocal"), a.optJSONObject("activityType")?.optString("typeKey") ?: "other",
                        a.dbl("distance"), a.dbl("duration"), a.dbl("averageSpeed"), a.dbl("averageHR"), a.dbl("maxHR"), a.dbl("activityTrainingLoad"),
                        a.optString("activityName", "Activité"), a.dbl("elevationGain"),
                        (1..5).map { z -> a.dbl("hrTimeInZone_$z") }.let { zs -> if (zs.any { it != null }) zs.map { it ?: 0.0 } else null },
                        a.dbl("aerobicTrainingEffect"), a.dbl("anaerobicTrainingEffect"), a.optString("trainingEffectLabel").takeIf { it.isNotEmpty() && it != "null" },
                        a.dbl("averageRunningCadenceInStepsPerMinute"), a.dbl("avgStrideLength"),
                        minTemperature = a.dbl("minTemperature"), vo2Max = a.dbl("vO2MaxValue"),
                    ),
                )
            }
            start += items.length()
            progress(min(start, wanted), wanted)
            if (items.length() < limit) break
            Thread.sleep(DELAY_MS)
        }
    }

    /** Watch laps of one activity. They are optional: an activity without them still gets its curves. */
    private fun fetchLaps(auth: GarminAuth, id: Long): List<Lap> = try {
        val dtos = auth.get("/activity-service/activity/$id/splits").optJSONArray("lapDTOs")
        (0 until (dtos?.length() ?: 0)).map {
            val l = dtos!!.getJSONObject(it)
            Lap(l.dbl("distance"), l.dbl("duration"), l.dbl("averageSpeed"), l.dbl("averageHR"), l.dbl("maxHR"), l.dbl("elevationGain"),
                l.optString("intensityType").takeIf { t -> t.isNotEmpty() && t != "null" }, if (l.has("wktStepIndex") && !l.isNull("wktStepIndex")) l.optInt("wktStepIndex") else null)
        }
    } catch (e: AuthException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    /** Laps only, for older runs: enough to detect interval sessions and feed the history of the analysis. */
    private fun lapsOnly(auth: GarminAuth, db: Db, id: Long) {
        val laps = fetchLaps(auth, id)
        db.saveLaps(id, laps)
        db.setIntervalTitle(id, Intervals.analyze(laps, db.recoveryModesOf(id))?.title)
    }

    /** Curves, GPS track and watch laps of one activity, stored locally; also sets its interval title and TRIMP. */
    fun detail(auth: GarminAuth, db: Db, id: Long, refs: HrRefs = Insights.refs(db), known: ActivityRow? = null): ActivityDetail {
        // The laps come from another call: ask for both at once
        var laps: List<Lap> = emptyList()
        var lapsFailure: Throwable? = null
        val lapsThread = thread { try { laps = fetchLaps(auth, id) } catch (t: Throwable) { lapsFailure = t } }
        val json = try {
            auth.get("/activity-service/activity/$id/details?maxChartSize=600&maxPolylineSize=1500")
        } finally {
            lapsThread.join()
        }
        lapsFailure?.let { throw it }
        val descriptors = json.optJSONArray("metricDescriptors")
        fun column(key: String): Int? = descriptors?.let { d -> (0 until d.length()).map { d.getJSONObject(it) }.firstOrNull { it.optString("key") == key }?.optInt("metricsIndex") }
        val iDist = column("sumDistance")
        val iTime = column("sumDuration")
        val iHr = column("directHeartRate")
        val iSpeed = column("directSpeed")
        val iEle = column("directElevation")
        fun org.json.JSONArray.at(i: Int?): Double? = if (i == null || i >= length() || isNull(i)) null else optDouble(i).takeIf { !it.isNaN() }
        val rows = json.optJSONArray("activityDetailMetrics")
        val samples = (0 until (rows?.length() ?: 0)).map {
            val m = rows!!.getJSONObject(it).optJSONArray("metrics") ?: org.json.JSONArray()
            Sample(m.at(iDist), m.at(iTime), m.at(iHr), m.at(iSpeed), m.at(iEle))
        }
        val polyline = json.optJSONObject("geoPolylineDTO")?.optJSONArray("polyline")
        val track = (0 until (polyline?.length() ?: 0)).mapNotNull {
            val p = polyline!!.getJSONObject(it)
            if (p.has("lat") && p.has("lon")) p.getDouble("lat") to p.getDouble("lon") else null
        }
        // A lap the watch was not split at (an effort swallowed by the lap after it) is cut out of the curve before anything is computed from the laps
        val fixed = Intervals.repairLaps(laps, samples) ?: laps
        val detail = ActivityDetail(samples, track, fixed)
        db.saveDetail(id, detail)
        db.setIntervalTitle(id, Intervals.analyze(fixed, db.recoveryModesOf(id))?.title)
        val activity = known ?: db.activities().firstOrNull { it.id == id }
        db.setTrimp(id, Insights.trimpFromSamples(samples, activity?.averageHr, activity?.duration, refs))
        return detail
    }

    /**
     * Fetches the curves, track and laps of the [limit] most recent activities that have none yet, then (when [lapsLimit] > 0)
     * only the laps of older runs. Returns how many activities were fetched.
     */
    private class DetailsTodo(val full: List<ActivityRow>, val laps: List<ActivityRow>) { val size get() = full.size + laps.size }

    private fun detailsTodo(db: Db, limit: Int, lapsLimit: Int): DetailsTodo {
        val full = db.detailIds()
        val withLaps = db.lapIds()
        val candidates = db.activities().filter { (it.distance ?: 0.0) >= 1000 }.sortedByDescending { it.startLocal }
        val todoFull = candidates.filter { it.id !in full }.take(limit)
        val fullIds = todoFull.map { a -> a.id }.toSet()
        val todoLaps = if (lapsLimit > 0) candidates.filter { it.isRun && it.id !in withLaps && it.id !in fullIds }.take(lapsLimit) else emptyList()
        return DetailsTodo(todoFull, todoLaps)
    }

    /** How many activities [details] would fetch now: for the time estimate. */
    fun detailsPlan(db: Db, limit: Int, lapsLimit: Int = 0): Int = detailsTodo(db, limit, lapsLimit).size

    fun details(auth: GarminAuth, db: Db, limit: Int, lapsLimit: Int = 0, progress: (Int, Int) -> Unit = { _, _ -> }): Int {
        val refs = Insights.refs(db)
        val todo = detailsTodo(db, limit, lapsLimit)
        val total = todo.size
        val done = java.util.concurrent.atomic.AtomicInteger()
        fun step(block: () -> Unit) {
            try {
                block()
            } catch (e: AuthException) {
                throw e
            } catch (e: Exception) {
                // One activity without curves must not stop the others
            }
            progress(done.incrementAndGet(), total)
            Thread.sleep(DELAY_MS)
        }
        val tasks: List<() -> Unit> = todo.full.map { a -> { detail(auth, db, a.id, refs, a); Unit } } + todo.laps.map { a -> { lapsOnly(auth, db, a.id) } }
        forEachParallel(tasks, DETAIL_WORKERS) { task -> step(task) }
        return total
    }

    /** The days to fetch: the [recent] last ones (refreshed, the day is not over) plus up to [backfillMax] missing days of the window. */
    fun wantedDays(db: Db, recent: Int, backfillMax: Int): List<String> {
        val today = LocalDate.now()
        val have = db.dailyDates()
        return ((0 until recent).map { today.minusDays(it.toLong()).toString() } +
            (0 until WINDOW_DAYS).map { today.minusDays(it).toString() }.filter { it !in have }.take(backfillMax)).distinct()
    }

    /** Wellness rows for [days]. [progress] gets (done, total). Returns how many days were stored. */
    fun daily(auth: GarminAuth, db: Db, displayName: String, days: List<String>, progress: (Int, Int) -> Unit = { _, _ -> }): Int {
        val stored = java.util.concurrent.atomic.AtomicInteger()
        val done = java.util.concurrent.atomic.AtomicInteger()
        forEachParallel(days, DAY_WORKERS) { day ->
            val (summary, sleep, hrv) = inParallel(
                { optional { auth.get("/usersummary-service/usersummary/daily/$displayName?calendarDate=$day") } },
                { optional { auth.get("/sleep-service/sleep/dailySleepData?date=$day") } },
                { optional { auth.get("/hrv-service/hrv/$day") } },
            )
            // Nothing at all came back (network trouble): do not store an empty row, it would never be retried
            if (summary != null || sleep != null || hrv != null) {
                val dto = sleep?.optJSONObject("dailySleepDTO")
                db.upsert(
                    DailyRow(
                        day, summary.dbl("restingHeartRate"), summary.positive("averageStressLevel"), summary.dbl("bodyBatteryHighestValue"),
                        dto.dbl("sleepTimeSeconds"), dto?.optJSONObject("sleepScores")?.optJSONObject("overall").dbl("value"),
                        hrv?.optJSONObject("hrvSummary").dbl("lastNightAvg") ?: sleep.dbl("avgOvernightHrv"),
                        summary.dbl("totalSteps"), summary.dbl("bodyBatteryLowestValue"),
                        dto.dbl("deepSleepSeconds"), dto.dbl("lightSleepSeconds"), dto.dbl("remSleepSeconds"), dto.dbl("awakeSleepSeconds"),
                    ),
                )
                stored.incrementAndGet()
            }
            progress(done.incrementAndGet(), days.size)
            Thread.sleep(DELAY_MS)
        }
        return stored.get()
    }

    /** Training status, VO2 max, acute / chronic load and race predictions for today. */
    fun fitness(auth: GarminAuth, db: Db, displayName: String) {
        val day = LocalDate.now().toString()
        val status = optional { auth.get("/metrics-service/metrics/trainingstatus/aggregated/$day") }
        val race = optional { auth.get("/metrics-service/metrics/racepredictions/latest/$displayName") }
        if (status == null && race == null) return
        // Several devices can report: keep the primary training device
        fun primary(map: JSONObject?): JSONObject? {
            map ?: return null
            val values = map.keys().asSequence().mapNotNull { map.optJSONObject(it) }.toList()
            return values.firstOrNull { it.optBoolean("primaryTrainingDevice") } ?: values.firstOrNull()
        }
        val ts = primary(status?.optJSONObject("mostRecentTrainingStatus")?.optJSONObject("latestTrainingStatusData"))
        val acute = ts?.optJSONObject("acuteTrainingLoadDTO")
        val balance = primary(status?.optJSONObject("mostRecentTrainingLoadBalance")?.optJSONObject("metricsTrainingLoadBalanceDTOMap"))
        val vo2 = status?.optJSONObject("mostRecentVO2Max")?.optJSONObject("generic")
        db.upsert(
            FitnessRow(
                day, vo2.dbl("vo2MaxPreciseValue") ?: vo2.dbl("vo2MaxValue"), ts?.optString("trainingStatusFeedbackPhrase")?.takeIf { it.isNotEmpty() && it != "null" }?.replace(Regex("_\\d+$"), ""),
                acute.dbl("dailyTrainingLoadAcute"), acute.dbl("dailyTrainingLoadChronic"), acute.dbl("minTrainingLoadChronic"), acute.dbl("maxTrainingLoadChronic"),
                acute.dbl("dailyAcuteChronicWorkloadRatio"), acute?.optString("acwrStatus")?.takeIf { it.isNotEmpty() && it != "null" },
                balance.dbl("monthlyLoadAerobicLow"), balance.dbl("monthlyLoadAerobicHigh"), balance.dbl("monthlyLoadAnaerobic"),
                balance?.optString("trainingBalanceFeedbackPhrase")?.takeIf { it.isNotEmpty() && it != "null" },
                race.dbl("time5K"), race.dbl("time10K"), race.dbl("timeHalfMarathon"), race.dbl("timeMarathon"),
            ),
        )
    }

    /** A day without sleep tracking or HRV is normal; only an authentication problem must stop the sync. */
    private fun optional(block: () -> JSONObject): JSONObject? = try {
        block()
    } catch (e: AuthException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
