package fr.foulee.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

class ActivityRow(
    val id: Long,
    val startLocal: String, // "2026-10-24 07:30:12"
    val type: String,
    val distance: Double?, // m
    val duration: Double?, // s
    val averageSpeed: Double?, // m/s
    val averageHr: Double?,
    val maxHr: Double?,
    val trainingLoad: Double?,
    val name: String,
    val elevation: Double?, // m
    val hrZones: List<Double>? = null, // seconds in zones 1..5
    val aerobicTe: Double? = null,
    val anaerobicTe: Double? = null,
    val teLabel: String? = null,
    val cadence: Double? = null,
    val strideLength: Double? = null, // cm
    /** Detected from the laps, e.g. "3 × 1000 m (r : 2 min 30)". Computed locally, never sent by the activity list. */
    val intervalTitle: String? = null,
    /** Kind of session chosen by hand: always wins over the detection */
    val manualCategory: String? = null,
    /** Heart-rate TRIMP from the curve, when the curve is stored */
    val trimp: Double? = null,
    /** Lowest temperature read by the watch, °C */
    val minTemperature: Double? = null,
    /** VO2 max estimated by the watch for this activity */
    val vo2Max: Double? = null,
    /** What Strava says about the same outing (name, description, labels), when the home server gave it and it matched */
    val strava: StravaActivity? = null,
    /** Timed (false) or measured (true) recoveries chosen by hand, by label of the efforts ("200 m"). Empty: the rule decides. */
    val recoveryModes: Map<String, Boolean> = emptyMap(),
    /** Race pace the reps were run at, chosen by hand or accepted from the proposal: "5 km", "10 km", "semi", "marathon", or "none" (refused). Null: not decided. */
    val speciality: String? = null,
) {
    val isRun get() = type.contains("running")
    val category: String get() = Categories.of(this)
    val title: String get() = intervalTitle ?: name
}

class Sample(val d: Double?, val t: Double?, val hr: Double?, val speed: Double?, val ele: Double?)

class Lap(
    val distance: Double?, val duration: Double?, val averageSpeed: Double?, val averageHr: Double?, val maxHr: Double?,
    val elevationGain: Double?, val intensityType: String?, val stepIndex: Int?,
)

/** Heart-rate / pace / altitude curves (about 600 points), the GPS track and the watch laps of one activity. */
class ActivityDetail(val samples: List<Sample>, val track: List<Pair<Double, Double>>, val laps: List<Lap>)

class RunRecord(val startedAt: Long, val finishedAt: Long, val mode: String, val trigger: String, val status: String, val error: String?, val newActivities: Int, val days: Int)

class DailyRow(
    val date: String,
    val restingHr: Double?,
    val avgStress: Double?,
    val bodyBatteryHigh: Double?,
    val sleepSeconds: Double?,
    val sleepScore: Double?,
    val hrvNight: Double?,
    val steps: Double? = null,
    val bodyBatteryLow: Double? = null,
    val deepSleep: Double? = null,
    val lightSleep: Double? = null,
    val remSleep: Double? = null,
    val awakeSleep: Double? = null,
)

/** What Garmin says about the form: VO2 max, training status, acute / chronic load, load balance, race predictions. */
class FitnessRow(
    val date: String, val vo2Max: Double?, val status: String?, val acuteLoad: Double?, val chronicLoad: Double?, val chronicMin: Double?, val chronicMax: Double?,
    val acwr: Double?, val acwrStatus: String?, val loadAerobicLow: Double?, val loadAerobicHigh: Double?, val loadAnaerobic: Double?, val loadBalance: String?,
    val race5k: Double?, val race10k: Double?, val raceHalf: Double?, val raceMarathon: Double?,
)

class CheckRow(val at: Long, val source: String, val result: String, val newRuns: Int, val ms: Long)

/** [delayMinutes]: from the end of the session to the moment the app saw it on Garmin. */
class DetectionRow(val activityId: Long, val endedAt: Long, val detectedAt: Long, val source: String) {
    val delayMinutes get() = (detectedAt - endedAt) / 60_000.0
}

/** Local copy of what the screens need: activities, curves, laps and one wellness row per day. */
class Db(context: Context) : SQLiteOpenHelper(context, "foulee.db", null, 13) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE activities (id INTEGER PRIMARY KEY, start_local TEXT NOT NULL, type TEXT NOT NULL, distance REAL, duration REAL, avg_speed REAL, avg_hr REAL, max_hr REAL, " +
                "training_load REAL, name TEXT NOT NULL DEFAULT '', elevation REAL, hr_zones TEXT, aerobic_te REAL, anaerobic_te REAL, te_label TEXT, cadence REAL, stride REAL, " +
                "interval_title TEXT, manual_category TEXT, trimp REAL, tmin REAL, vo2 REAL, recovery_modes TEXT, speciality TEXT)",
        )
        db.execSQL("CREATE INDEX activities_start ON activities(start_local)")
        db.execSQL("CREATE TABLE daily (date TEXT PRIMARY KEY, resting_hr REAL, avg_stress REAL, bb_high REAL, sleep_seconds REAL, sleep_score REAL, hrv_night REAL, steps REAL, bb_low REAL, deep REAL, light REAL, rem REAL, awake REAL, ver INTEGER NOT NULL DEFAULT 2)")
        createFitness(db)
        createRuns(db)
        db.execSQL("CREATE TABLE activity_details (id INTEGER PRIMARY KEY, samples TEXT NOT NULL, track TEXT NOT NULL, laps TEXT)")
        createStrava(db)
        createStravaSent(db)
        createDetection(db)
        createLog(db)
    }

    /** How fast a new run is seen: every background check, and for each run the end of the session against the moment it was detected. */
    private fun createLog(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE app_log (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, level TEXT NOT NULL, tag TEXT NOT NULL, msg TEXT NOT NULL)")
    }

    private fun createDetection(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE checks (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, source TEXT NOT NULL, result TEXT NOT NULL, new_runs INTEGER NOT NULL DEFAULT 0, ms INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE detections (activity_id INTEGER PRIMARY KEY, ended_at INTEGER NOT NULL, detected_at INTEGER NOT NULL, source TEXT NOT NULL)")
    }

    /** What the "Vérifier Strava" check has already sent for real: only the changes are sent again. Key: "s<stravaId>" or "g<garminId>". */
    private fun createStravaSent(db: SQLiteDatabase) =
        db.execSQL("CREATE TABLE strava_sent (key TEXT PRIMARY KEY, category TEXT NOT NULL, choice TEXT NOT NULL)")

    private fun createStrava(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE strava_activities (strava_id TEXT PRIMARY KEY, sport TEXT NOT NULL, name TEXT NOT NULL DEFAULT '', description TEXT NOT NULL DEFAULT '', labels TEXT NOT NULL DEFAULT '[]', " +
                "start_local TEXT NOT NULL, total_s REAL, moving_s REAL, distance REAL, elevation REAL, modified_at TEXT, garmin_id INTEGER)",
        )
        db.execSQL("CREATE INDEX strava_garmin ON strava_activities(garmin_id)")
    }

    private fun createFitness(db: SQLiteDatabase) = db.execSQL(
        "CREATE TABLE fitness (date TEXT PRIMARY KEY, vo2 REAL, status TEXT, acute REAL, chronic REAL, cmin REAL, cmax REAL, acwr REAL, acwr_status TEXT, lb_low REAL, lb_high REAL, lb_an REAL, balance TEXT, r5k REAL, r10k REAL, rhalf REAL, rmar REAL)",
    )

    private fun createRuns(db: SQLiteDatabase) =
        db.execSQL("CREATE TABLE sync_runs (id INTEGER PRIMARY KEY AUTOINCREMENT, started INTEGER NOT NULL, finished INTEGER NOT NULL, mode TEXT NOT NULL, trigger TEXT NOT NULL, status TEXT NOT NULL, error TEXT, new_activities INTEGER NOT NULL, days INTEGER NOT NULL)")

    /** The tables are only a cache of Garmin: an old schema just starts over, a recent one gets its new columns. */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            for (t in listOf("activities", "daily", "sync_runs", "activity_details")) db.execSQL("DROP TABLE IF EXISTS $t")
            onCreate(db)
            return
        }
        if (oldVersion < 3) createRuns(db)
        if (oldVersion < 4) db.execSQL("CREATE TABLE activity_details (id INTEGER PRIMARY KEY, samples TEXT NOT NULL, track TEXT NOT NULL, laps TEXT)")
        if (oldVersion < 5) {
            for (col in listOf("hr_zones TEXT", "aerobic_te REAL", "anaerobic_te REAL", "te_label TEXT", "cadence REAL", "stride REAL", "interval_title TEXT", "manual_category TEXT", "trimp REAL")) {
                db.execSQL("ALTER TABLE activities ADD COLUMN $col")
            }
            if (oldVersion >= 4) db.execSQL("ALTER TABLE activity_details ADD COLUMN laps TEXT")
        }
        if (oldVersion < 6) db.execSQL("ALTER TABLE activities ADD COLUMN tmin REAL")
        if (oldVersion < 7) {
            db.execSQL("ALTER TABLE activities ADD COLUMN vo2 REAL")
            for (col in listOf("steps REAL", "bb_low REAL", "deep REAL", "light REAL", "rem REAL", "awake REAL")) db.execSQL("ALTER TABLE daily ADD COLUMN $col")
            // Rows stored before this version miss the new columns: ver 1 means "fetch again"
            db.execSQL("ALTER TABLE daily ADD COLUMN ver INTEGER NOT NULL DEFAULT 1")
            createFitness(db)
        }
        if (oldVersion < 8) createStrava(db)
        if (oldVersion < 9) createStravaSent(db)
        if (oldVersion < 10) db.execSQL("ALTER TABLE activities ADD COLUMN recovery_modes TEXT")
        if (oldVersion < 11) db.execSQL("ALTER TABLE activities ADD COLUMN speciality TEXT")
        if (oldVersion < 12) createDetection(db)
        if (oldVersion < 13) createLog(db)
    }

    /** Updates what the Garmin list sends and keeps the locally computed columns (interval title, manual kind, TRIMP). */
    fun upsert(a: ActivityRow) {
        val values = ContentValues().apply {
            put("start_local", a.startLocal); put("type", a.type)
            put("distance", a.distance); put("duration", a.duration); put("avg_speed", a.averageSpeed)
            put("avg_hr", a.averageHr); put("max_hr", a.maxHr); put("training_load", a.trainingLoad); put("name", a.name); put("elevation", a.elevation)
            put("hr_zones", a.hrZones?.let { JSONArray(it).toString() })
            put("aerobic_te", a.aerobicTe); put("anaerobic_te", a.anaerobicTe); put("te_label", a.teLabel); put("cadence", a.cadence); put("stride", a.strideLength); put("tmin", a.minTemperature); put("vo2", a.vo2Max)
        }
        if (writableDatabase.update("activities", values, "id = ?", arrayOf(a.id.toString())) == 0) {
            values.put("id", a.id)
            writableDatabase.insert("activities", null, values)
        }
    }

    /** Updates what the server sent and keeps the match with Garmin. */
    fun upsertStrava(list: List<StravaActivity>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (s in list) {
                val values = ContentValues().apply {
                    put("sport", s.sport); put("name", s.name); put("description", s.description); put("labels", JSONArray(s.labels).toString())
                    put("start_local", s.startLocal); put("total_s", s.totalSeconds); put("moving_s", s.movingSeconds)
                    put("distance", s.distance); put("elevation", s.elevation); put("modified_at", s.modifiedAt)
                }
                if (db.update("strava_activities", values, "strava_id = ?", arrayOf(s.id)) == 0) {
                    values.put("strava_id", s.id)
                    db.insert("strava_activities", null, values)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun setStravaMatch(stravaId: String, garminId: Long?) {
        writableDatabase.update("strava_activities", ContentValues().apply { put("garmin_id", garminId) }, "strava_id = ?", arrayOf(stravaId))
    }

    fun matchedGarminIds(): Set<Long> = readableDatabase.rawQuery("SELECT garmin_id FROM strava_activities WHERE garmin_id IS NOT NULL", null).use { c -> generateSequence { if (c.moveToNext()) c.getLong(0) else null }.toSet() }

    fun stravaActivities(): List<StravaActivity> = readableDatabase.query("strava_activities", null, null, null, null, null, "start_local ASC").use { c ->
        generateSequence {
            if (!c.moveToNext()) null else StravaActivity(
                c.str("strava_id")!!, c.str("sport") ?: "", c.str("name") ?: "", c.str("description") ?: "",
                c.str("labels")?.let { l -> JSONArray(l).let { a -> (0 until a.length()).map { a.getString(it) } } } ?: emptyList(),
                c.str("start_local")!!, c.dbl("total_s"), c.dbl("moving_s"), c.dbl("distance"), c.dbl("elevation"), c.str("modified_at"),
                c.getColumnIndexOrThrow("garmin_id").let { if (c.isNull(it)) null else c.getLong(it) },
            )
        }.toList()
    }

    /** category and choice already sent for each key */
    fun stravaSent(): Map<String, Pair<String, String>> = readableDatabase.rawQuery("SELECT key, category, choice FROM strava_sent", null).use { c ->
        generateSequence { if (c.moveToNext()) c.getString(0) to (c.getString(1) to c.getString(2)) else null }.toMap()
    }

    fun markStravaSent(entries: List<Triple<String, String, String>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((key, category, choice) in entries) {
                db.insertWithOnConflict("strava_sent", null, ContentValues().apply { put("key", key); put("category", category); put("choice", choice) }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Strava outings that no Garmin activity matches (weight training, a run without the watch): rows for the lists only, with a negative id
     * (minus the Strava id) so they can never collide with a Garmin id. They have no curves, no laps and no load.
     */
    fun stravaOnlyRows(): List<ActivityRow> = stravaActivities().filter { it.garminId == null }.mapNotNull { s ->
        val id = s.id.toLongOrNull() ?: return@mapNotNull null
        val seconds = s.totalSeconds ?: s.movingSeconds
        val speed = if ((s.distance ?: 0.0) > 0 && (s.movingSeconds ?: s.totalSeconds ?: 0.0) > 0) s.distance!! / (s.movingSeconds ?: s.totalSeconds!!) else null
        ActivityRow(-id, s.startLocal, StravaSync.garminType(s.sport), s.distance, seconds, speed, null, null, null, s.name, s.elevation, strava = s)
    }

    fun stravaCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM strava_activities", null).use { it.moveToFirst(); it.getInt(0) }

    fun setIntervalTitle(id: Long, title: String?) {
        writableDatabase.update("activities", ContentValues().apply { put("interval_title", title) }, "id = ?", arrayOf(id.toString()))
    }

    fun setTrimp(id: Long, trimp: Double?) {
        writableDatabase.update("activities", ContentValues().apply { put("trimp", trimp) }, "id = ?", arrayOf(id.toString()))
    }

    private fun parseModes(json: String?): Map<String, Boolean> = json?.let { j ->
        runCatching { JSONObject(j).let { o -> o.keys().asSequence().associateWith { o.getBoolean(it) } } }.getOrNull()
    } ?: emptyMap()

    fun recoveryModesOf(id: Long): Map<String, Boolean> = readableDatabase.rawQuery("SELECT recovery_modes FROM activities WHERE id = ?", arrayOf(id.toString())).use { c ->
        if (c.moveToFirst()) parseModes(c.str("recovery_modes")) else emptyMap()
    }

    /** One choice, by label of the efforts: true = measured, false = timed, null = back to the automatic rule. */
    fun setRecoveryMode(id: Long, key: String, measured: Boolean?) {
        val modes = recoveryModesOf(id).toMutableMap()
        if (measured == null) modes.remove(key) else modes[key] = measured
        val json = if (modes.isEmpty()) null else JSONObject(modes as Map<*, *>).toString()
        writableDatabase.update("activities", ContentValues().apply { put("recovery_modes", json) }, "id = ?", arrayOf(id.toString()))
    }

    fun setSpeciality(id: Long, speciality: String?) {
        writableDatabase.update("activities", ContentValues().apply { put("speciality", speciality) }, "id = ?", arrayOf(id.toString()))
    }

    fun setManualCategory(id: Long, category: String?) {
        writableDatabase.update("activities", ContentValues().apply { put("manual_category", category) }, "id = ?", arrayOf(id.toString()))
    }

    fun upsert(d: DailyRow) {
        val values = ContentValues().apply {
            put("date", d.date); put("resting_hr", d.restingHr); put("avg_stress", d.avgStress); put("bb_high", d.bodyBatteryHigh)
            put("sleep_seconds", d.sleepSeconds); put("sleep_score", d.sleepScore); put("hrv_night", d.hrvNight)
            put("steps", d.steps); put("bb_low", d.bodyBatteryLow); put("deep", d.deepSleep); put("light", d.lightSleep); put("rem", d.remSleep); put("awake", d.awakeSleep)
            put("ver", 2)
        }
        writableDatabase.insertWithOnConflict("daily", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun Cursor.dbl(name: String): Double? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getDouble(it) }
    private fun Cursor.str(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }

    fun activities(): List<ActivityRow> = activitiesWith(stravaActivities().filter { it.garminId != null }.associateBy { it.garminId!! })

    private fun activitiesWith(strava: Map<Long, StravaActivity>): List<ActivityRow> = readableDatabase.query("activities", null, null, null, null, null, "start_local ASC").use { c ->
        generateSequence {
            if (!c.moveToNext()) null else ActivityRow(
                c.getLong(c.getColumnIndexOrThrow("id")), c.str("start_local")!!, c.str("type")!!, c.dbl("distance"), c.dbl("duration"), c.dbl("avg_speed"), c.dbl("avg_hr"), c.dbl("max_hr"),
                c.dbl("training_load"), c.str("name") ?: "", c.dbl("elevation"),
                c.str("hr_zones")?.let { z -> JSONArray(z).let { a -> (0 until a.length()).map { a.getDouble(it) } } },
                c.dbl("aerobic_te"), c.dbl("anaerobic_te"), c.str("te_label"), c.dbl("cadence"), c.dbl("stride"), c.str("interval_title")?.takeIf { c.str("type")!!.contains("running") }, c.str("manual_category"), c.dbl("trimp"), c.dbl("tmin"), c.dbl("vo2"),
                strava[c.getLong(c.getColumnIndexOrThrow("id"))], parseModes(c.str("recovery_modes")), c.str("speciality"),
            )
        }.toList()
    }

    fun dailyRows(): List<DailyRow> = readableDatabase.query("daily", null, null, null, null, null, "date ASC").use { c ->
        generateSequence { if (c.moveToNext()) DailyRow(c.getString(0), c.dbl("resting_hr"), c.dbl("avg_stress"), c.dbl("bb_high"), c.dbl("sleep_seconds"), c.dbl("sleep_score"), c.dbl("hrv_night"), c.dbl("steps"), c.dbl("bb_low"), c.dbl("deep"), c.dbl("light"), c.dbl("rem"), c.dbl("awake")) else null }.toList()
    }

    /** Days stored with the current columns (older rows are fetched again). */
    fun dailyDates(): Set<String> = readableDatabase.rawQuery("SELECT date FROM daily WHERE ver >= 2", null).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toSet() }

    fun isEmpty(): Boolean = readableDatabase.rawQuery("SELECT 1 FROM activities LIMIT 1", null).use { !it.moveToFirst() }

    fun addRun(r: RunRecord) {
        val values = ContentValues().apply {
            put("started", r.startedAt); put("finished", r.finishedAt); put("mode", r.mode); put("trigger", r.trigger)
            put("status", r.status); put("error", r.error); put("new_activities", r.newActivities); put("days", r.days)
        }
        writableDatabase.insert("sync_runs", null, values)
        writableDatabase.execSQL("DELETE FROM sync_runs WHERE id NOT IN (SELECT id FROM sync_runs ORDER BY id DESC LIMIT 50)")
    }

    fun runs(limit: Int = 15): List<RunRecord> = readableDatabase.rawQuery("SELECT started, finished, mode, trigger, status, error, new_activities, days FROM sync_runs ORDER BY id DESC LIMIT $limit", null).use { c ->
        generateSequence { if (c.moveToNext()) RunRecord(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4), if (c.isNull(5)) null else c.getString(5), c.getInt(6), c.getInt(7)) else null }.toList()
    }

    fun activityCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM activities", null).use { it.moveToFirst(); it.getInt(0) }

    fun dailyCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM daily", null).use { it.moveToFirst(); it.getInt(0) }

    fun saveDetail(id: Long, detail: ActivityDetail) {
        val samples = JSONArray()
        for (s in detail.samples) samples.put(JSONArray().apply { listOf(s.d, s.t, s.hr, s.speed, s.ele).forEach { put(it ?: JSONObject.NULL) } })
        val track = JSONArray()
        for ((lat, lon) in detail.track) track.put(JSONArray().put(lat).put(lon))
        val laps = JSONArray()
        for (l in detail.laps) laps.put(JSONArray().apply { listOf(l.distance, l.duration, l.averageSpeed, l.averageHr, l.maxHr, l.elevationGain, l.intensityType, l.stepIndex).forEach { put(it ?: JSONObject.NULL) } })
        val values = ContentValues().apply { put("id", id); put("samples", samples.toString()); put("track", track.toString()); put("laps", laps.toString()) }
        writableDatabase.insertWithOnConflict("activity_details", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** The GPS track alone (null when none is stored), cheaper than [detail] for a thumbnail. */
    fun track(id: Long): List<Pair<Double, Double>>? = readableDatabase.rawQuery("SELECT track FROM activity_details WHERE id = ? AND track != '[]'", arrayOf(id.toString())).use { c ->
        if (!c.moveToFirst()) return null
        val track = JSONArray(c.getString(0))
        (0 until track.length()).map { val r = track.getJSONArray(it); r.getDouble(0) to r.getDouble(1) }
    }

    fun detail(id: Long): ActivityDetail? = readableDatabase.rawQuery("SELECT samples, track, laps FROM activity_details WHERE id = ? AND laps IS NOT NULL AND samples != '[]'", arrayOf(id.toString())).use { c ->
        if (!c.moveToFirst()) return null
        val samples = JSONArray(c.getString(0))
        val track = JSONArray(c.getString(1))
        val laps = JSONArray(c.getString(2))
        fun JSONArray.num(i: Int): Double? = if (isNull(i)) null else getDouble(i)
        ActivityDetail(
            (0 until samples.length()).map { val r = samples.getJSONArray(it); Sample(r.num(0), r.num(1), r.num(2), r.num(3), r.num(4)) },
            (0 until track.length()).map { val r = track.getJSONArray(it); r.getDouble(0) to r.getDouble(1) },
            (0 until laps.length()).map { val r = laps.getJSONArray(it); Lap(r.num(0), r.num(1), r.num(2), r.num(3), r.num(4), r.num(5), if (r.isNull(6)) null else r.getString(6), if (r.isNull(7)) null else r.getInt(7)) },
        )
    }

    /** Activities whose curves and laps are both stored (a detail with only laps, or without laps, counts as missing). */
    /** Forgets the curves and laps of one activity: the next sync fetches them again. */
    fun deleteDetail(id: Long) {
        writableDatabase.delete("activity_details", "id = ?", arrayOf(id.toString()))
    }

    fun detailIds(): Set<Long> = readableDatabase.rawQuery("SELECT id FROM activity_details WHERE laps IS NOT NULL AND samples != '[]'", null).use { c -> generateSequence { if (c.moveToNext()) c.getLong(0) else null }.toSet() }

    /** Activities whose laps are stored, with or without curves. */
    fun lapIds(): Set<Long> = readableDatabase.rawQuery("SELECT id FROM activity_details WHERE laps IS NOT NULL", null).use { c -> generateSequence { if (c.moveToNext()) c.getLong(0) else null }.toSet() }

    /** Laps only (older activities: no curves, but enough to detect interval sessions). Keeps the curves if they exist. */
    fun saveLaps(id: Long, laps: List<Lap>) {
        val json = JSONArray()
        for (l in laps) json.put(JSONArray().apply { listOf(l.distance, l.duration, l.averageSpeed, l.averageHr, l.maxHr, l.elevationGain, l.intensityType, l.stepIndex).forEach { put(it ?: JSONObject.NULL) } })
        val values = ContentValues().apply { put("laps", json.toString()) }
        if (writableDatabase.update("activity_details", values, "id = ?", arrayOf(id.toString())) == 0) {
            values.put("id", id); values.put("samples", "[]"); values.put("track", "[]")
            writableDatabase.insert("activity_details", null, values)
        }
    }

    /** Laps of the given activities (for the interval history). */
    fun lapsOf(ids: Collection<Long>): Map<Long, List<Lap>> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<Long, List<Lap>>()
        readableDatabase.rawQuery("SELECT id, laps FROM activity_details WHERE laps IS NOT NULL AND id IN (${ids.joinToString(",")})", null).use { c ->
            while (c.moveToNext()) {
                val laps = JSONArray(c.getString(1))
                fun JSONArray.num(i: Int): Double? = if (isNull(i)) null else getDouble(i)
                out[c.getLong(0)] = (0 until laps.length()).map { val r = laps.getJSONArray(it); Lap(r.num(0), r.num(1), r.num(2), r.num(3), r.num(4), r.num(5), if (r.isNull(6)) null else r.getString(6), if (r.isNull(7)) null else r.getInt(7)) }
            }
        }
        return out
    }

    fun upsert(f: FitnessRow) {
        val values = ContentValues().apply {
            put("date", f.date); put("vo2", f.vo2Max); put("status", f.status); put("acute", f.acuteLoad); put("chronic", f.chronicLoad); put("cmin", f.chronicMin); put("cmax", f.chronicMax)
            put("acwr", f.acwr); put("acwr_status", f.acwrStatus); put("lb_low", f.loadAerobicLow); put("lb_high", f.loadAerobicHigh); put("lb_an", f.loadAnaerobic); put("balance", f.loadBalance)
            put("r5k", f.race5k); put("r10k", f.race10k); put("rhalf", f.raceHalf); put("rmar", f.raceMarathon)
        }
        writableDatabase.insertWithOnConflict("fitness", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Fitness snapshots, oldest first. */
    fun fitnessRows(): List<FitnessRow> = readableDatabase.query("fitness", null, null, null, null, null, "date ASC").use { c ->
        generateSequence {
            if (!c.moveToNext()) null else FitnessRow(
                c.str("date")!!, c.dbl("vo2"), c.str("status"), c.dbl("acute"), c.dbl("chronic"), c.dbl("cmin"), c.dbl("cmax"), c.dbl("acwr"), c.str("acwr_status"),
                c.dbl("lb_low"), c.dbl("lb_high"), c.dbl("lb_an"), c.str("balance"), c.dbl("r5k"), c.dbl("r10k"), c.dbl("rhalf"), c.dbl("rmar"),
            )
        }.toList()
    }

    fun clear() {
        writableDatabase.execSQL("DELETE FROM fitness")
        writableDatabase.execSQL("DELETE FROM activity_details")
        writableDatabase.execSQL("DELETE FROM sync_runs")
        writableDatabase.execSQL("DELETE FROM activities")
        writableDatabase.execSQL("DELETE FROM daily")
        writableDatabase.execSQL("DELETE FROM strava_activities")
        writableDatabase.execSQL("DELETE FROM strava_sent")
        writableDatabase.execSQL("DELETE FROM checks")
        writableDatabase.execSQL("DELETE FROM detections")
    }

    /** One line of the journal of the app: what happened, when, with which result. Never a password, a token or a key. */
    fun addLog(level: String, tag: String, msg: String) {
        android.util.Log.println(if (level == "E") android.util.Log.ERROR else if (level == "W") android.util.Log.WARN else android.util.Log.INFO, "Foulee/$tag", msg)
        try {
            val db = writableDatabase
            db.insert("app_log", null, ContentValues().apply { put("at", System.currentTimeMillis()); put("level", level); put("tag", tag); put("msg", msg.take(600)) })
            // Two weeks and 3000 lines at most
            db.execSQL("DELETE FROM app_log WHERE at < ? OR id NOT IN (SELECT id FROM app_log ORDER BY id DESC LIMIT 3000)", arrayOf<Any>(System.currentTimeMillis() - 14L * 86_400_000))
        } catch (_: Exception) {
        }
    }

    class LogRow(val at: Long, val level: String, val tag: String, val msg: String)

    fun logs(limit: Int = 150, onlyProblems: Boolean = false): List<LogRow> = readableDatabase.rawQuery(
        "SELECT at, level, tag, msg FROM app_log ${if (onlyProblems) "WHERE level != 'I'" else ""} ORDER BY id DESC LIMIT $limit", null,
    ).use { c -> generateSequence { if (c.moveToNext()) LogRow(c.getLong(0), c.getString(1), c.getString(2), c.getString(3)) else null }.toList() }

    fun addCheck(at: Long, source: String, result: String, newRuns: Int, ms: Long) {
        val db = writableDatabase
        db.insert("checks", null, ContentValues().apply { put("at", at); put("source", source); put("result", result); put("new_runs", newRuns); put("ms", ms) })
        // The journal only needs the recent past
        db.execSQL("DELETE FROM checks WHERE id NOT IN (SELECT id FROM checks ORDER BY id DESC LIMIT 1000)")
    }

    fun checks(limit: Int = 10): List<CheckRow> = readableDatabase.rawQuery("SELECT at, source, result, new_runs, ms FROM checks ORDER BY id DESC LIMIT $limit", null).use { c ->
        generateSequence { if (c.moveToNext()) CheckRow(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3), c.getLong(4)) else null }.toList()
    }

    fun checkCountSince(since: Long): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM checks WHERE at >= ? AND source != 'garmin-notif'", arrayOf(since.toString())).use { it.moveToFirst(); it.getInt(0) }

    fun addDetection(activityId: Long, endedAt: Long, detectedAt: Long, source: String) {
        writableDatabase.insertWithOnConflict("detections", null, ContentValues().apply { put("activity_id", activityId); put("ended_at", endedAt); put("detected_at", detectedAt); put("source", source) }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun detections(limit: Int = 30): List<DetectionRow> = readableDatabase.rawQuery("SELECT activity_id, ended_at, detected_at, source FROM detections ORDER BY detected_at DESC LIMIT $limit", null).use { c ->
        generateSequence { if (c.moveToNext()) DetectionRow(c.getLong(0), c.getLong(1), c.getLong(2), c.getString(3)) else null }.toList()
    }
}
