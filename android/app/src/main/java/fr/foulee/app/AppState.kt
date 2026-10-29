package fr.foulee.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.LocalDate
import kotlin.concurrent.thread

class Week(val start: LocalDate, val distance: Double, val duration: Double, val elevation: Double, val count: Int)

/** Everything the screens show, computed once per reload. */
class Snapshot(
    val activities: List<ActivityRow>, val daily: List<DailyRow>, val analysis: Analysis, val weeks: List<Week>,
    val weekKm: Double, val lastWeekKm: Double, val weekRuns: Int, val runs: List<RunRecord>, val garminTotal: Int,
    /** Interval sessions with their history, for the analysis (computed on first use) */
    val history: SessionHistory,
    val compare: LoadComparison,
    val fitness: List<FitnessRow>,
    /** Strava outings with no Garmin match (weight training…): shown in the lists only, never in the load, the profile or the detection */
    val stravaOnly: List<ActivityRow> = emptyList(),
)

private const val DATA_VERSION = 7

/** Raise it when the interval detection changes: the titles stored in the base are rebuilt from the stored laps. */
private const val TITLES_VERSION = 13

class AppState(private val context: Context) {
    private val settings = Settings(context)
    val auth = GarminAuth(context)
    val db = Db(context)

    var loggedIn by mutableStateOf(settings.loggedIn)

    /** The introduction is for someone who opens the app for the first time: whoever is already signed in never sees it. */
    var onboardingDone by mutableStateOf(settings.onboardingDone || settings.loggedIn)
    fun finishOnboarding() {
        settings.onboardingDone = true
        onboardingDone = true
    }
    var snapshot by mutableStateOf<Snapshot?>(null)

    /** Login, reset and other actions that are not a sync (a sync is in [SyncEngine.current]) */
    var busy by mutableStateOf(false)
    var message by mutableStateOf("")
    var needsMfa by mutableStateOf(false)
    var firstName by mutableStateOf(settings.firstName)
    var photo by mutableStateOf(settings.photoUrl)
    var autoCheck by mutableStateOf(settings.autoCheck)

    val syncing get() = SyncEngine.current != null

    /** The home Strava server (optional): address, whether a key is set, and the result of the last test. */
    var stravaUrl by mutableStateOf(settings.stravaUrl ?: "")
    var hasStravaKey by mutableStateOf(!settings.stravaKey.isNullOrBlank())
    var stravaMessage by mutableStateOf("")
    var stravaBusy by mutableStateOf(false)

    /** Keeps the address and the key (an empty key field keeps the one already stored). An empty address turns the server off. */
    fun saveStrava(url: String, key: String) {
        val address = StravaSync.normalize(url)
        // Another server has other ids and dates: start again from the beginning
        val changed = address != settings.stravaUrl
        if (changed) {
            settings.stravaCursor = null
            settings.stravaStatus = null
        }
        settings.stravaUrl = address
        stravaUrl = address ?: ""
        if (key.isNotBlank()) settings.stravaKey = key.trim()
        if (address == null) {
            settings.stravaKey = null
            settings.stravaCursor = null
        }
        hasStravaKey = !settings.stravaKey.isNullOrBlank()
        refreshStravaProblem()
        if (address == null) { stravaProblem = ""; checkMessage = ""; textStatus = "" }
        stravaMessage = if (address == null) "Serveur Strava retiré." else "Enregistré."
    }

    /** The Strava server is set up (address and key): everything that talks to it only shows then. */
    val stravaConfigured get() = stravaUrl.isNotBlank() && hasStravaKey

    /** A Strava exchange went well: the errors left by earlier failures are no longer true, wherever they were shown. */
    private fun stravaWorked() {
        settings.stravaStatus = null
        stravaProblem = ""
        stravaMessage = ""
        checkMessage = ""
        textStatus = ""
    }

    /** The validation list of the labels: what is ticked, what kind was changed, what was refused. It outlives the dialog (a session can be opened and come back). */
    val reviewTicked = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    val reviewChosen = androidx.compose.runtime.mutableStateMapOf<String, String>()
    val reviewRefused = androidx.compose.runtime.mutableStateListOf<String>()

    fun closeReview() {
        checkReport = null
        reviewTicked.clear(); reviewChosen.clear(); reviewRefused.clear()
    }

    /** The race pace of the reps of one session: "5 km", "10 km", "semi", "marathon" or "none". The text follows at once. */
    fun setSpeciality(id: Long, speciality: String?) = thread {
        db.setSpeciality(id, speciality)
        reload()
    }

    /** The "Vérifier Strava" check: its answer (shown in a dialog), a message when there is nothing to show, and whether a call is running. */
    var checkReport by mutableStateOf<CheckReport?>(null)
    var checkMessage by mutableStateOf("")
    var checkBusy by mutableStateOf(false)

    /** Labels: a preview of what the server would change on every run (nothing is written), then Eloi validates what he wants. */
    fun checkStrava() {
        val snap = snapshot ?: return
        if (checkBusy) return
        checkBusy = true
        checkMessage = "Analyse en cours…"
        thread {
            checkMessage = try {
                val items = StravaCheck.candidates(db, snap.activities, true)
                if (items.isEmpty()) "Rien à proposer : tout est déjà validé ou refusé."
                else {
                    val report = StravaCheck.run(settings, db, items, apply = false)
                    stravaWorked()
                    reviewTicked.clear(); reviewChosen.clear(); reviewRefused.clear()
                    checkReport = report
                    ""
                }
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            checkBusy = false
        }
    }

    /** "Refuser": this proposal is not shown again until the kind of the outing changes. */
    fun declineLabel(item: CheckItem) = thread { db.markStravaSent(listOf(Triple(item.key, item.category, item.choice))) }

    /** Sends what was validated. [manual]: the kinds changed by hand (Garmin id to kind), kept in the app as well. */
    fun applyLabels(items: List<CheckItem>, manual: Map<Long, String>) {
        if (checkBusy || items.isEmpty()) return
        checkBusy = true
        checkMessage = "Envoi en cours…"
        thread {
            checkMessage = try {
                manual.forEach { (id, category) -> db.setManualCategory(id, category) }
                val report = StravaCheck.run(settings, db, items, apply = true) { done, total -> checkMessage = "Envoi en cours : $done / $total" }
                stravaWorked()
                closeReview()
                val done = report.results.count { it.status in setOf("a-corriger", "planifie", "ecrit") }
                val problems = report.results.count { it.status !in setOf("a-corriger", "planifie", "ecrit", "inchange", "ignore", "conflit") }
                (if (report.simulation) "Simulation : $done modification(s) prévue(s), rien n'a été écrit dans Strava (le serveur est en simulation)."
                else "$done modification(s) envoyée(s) à Strava.") + if (problems > 0) " $problems sortie(s) en erreur ou introuvable(s)." else ""
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            checkBusy = false
            reload()
        }
    }

    /** The network diagnostic for the Strava server: its report is shown in a dialog. */
    var diagReport by mutableStateOf<DiagReport?>(null)
    var diagBusy by mutableStateOf(false)

    fun diagnoseStrava(url: String) {
        if (diagBusy) return
        diagBusy = true
        thread {
            diagReport = try {
                NetworkDiagnostic.run(context, url)
            } catch (e: Exception) {
                DiagReport(listOf(DiagLine(false, "Diagnostic", e.message ?: e.javaClass.simpleName)), "Le diagnostic n'a pas pu aller au bout.", false)
            }
            diagBusy = false
        }
    }

    /** State of the link with the server, for the small tile of the profile: "unknown", "checking", "ok", "session" (server up, Strava session lost) or "down". */
    var stravaLink by mutableStateOf("unknown")
    var stravaLinkDetail by mutableStateOf("")
    var stravaLinkAt by mutableStateOf(0L)
    var stravaEtat by mutableStateOf<StravaSync.Etat?>(null)

    /** A silent check of the saved address and key: only the tile changes. */
    fun pingStrava() {
        if (!stravaConfigured || stravaLink == "checking") return
        stravaLink = "checking"
        StravaSync.settingsLog = { db.addLog("W", "strava", it) }
        thread {
            try {
                val answer = StravaSync.test(stravaUrl, settings.stravaKey)
                stravaEtat = StravaSync.etat(settings)
                stravaLink = if (answer.startsWith("Serveur joint")) "ok" else "session"
                stravaLinkDetail = answer
                if (stravaLink == "ok") stravaWorked()
            } catch (e: Exception) {
                stravaLink = "down"
                stravaLinkDetail = e.message ?: e.javaClass.simpleName
            }
            stravaLinkAt = System.currentTimeMillis()
            db.addLog(if (stravaLink == "ok") "I" else "W", "strava", "test du serveur : $stravaLink ($stravaLinkDetail)" + (stravaEtat?.let { " · v${it.version}, écriture ${if (it.writing) "active" else "en simulation"}, session ${it.session}" } ?: ""))
        }
    }

    fun testStrava(url: String, key: String) {
        if (stravaBusy) return
        stravaBusy = true
        stravaMessage = "Test en cours…"
        StravaSync.settingsLog = { db.addLog("W", "strava", it) }
        thread {
            stravaMessage = try {
                StravaSync.test(url, key.ifBlank { settings.stravaKey }).also {
                    // The pill in the card header follows this test too, so the two never disagree
                    stravaLink = if (it.startsWith("Serveur joint")) "ok" else "session"
                    stravaLinkDetail = it
                    stravaLinkAt = System.currentTimeMillis()
                    if (stravaLink == "ok") stravaWorked()
                }
            } catch (e: Exception) {
                stravaLink = "down"
                stravaLinkDetail = e.message ?: e.javaClass.simpleName
                stravaLinkAt = System.currentTimeMillis()
                e.message ?: e.javaClass.simpleName
            }
            stravaBusy = false
        }
    }

    /** The problem of the last exchange with the server, if any, with its age: empty when all went well. */
    var stravaProblem by mutableStateOf("")

    fun refreshStravaProblem() {
        stravaProblem = settings.stravaStatus?.takeIf { it.isNotBlank() && !settings.stravaUrl.isNullOrBlank() }?.let { "$it (${Fmt.relative(settings.stravaStatusAt)})" } ?: ""
    }

    fun stravaSummary(): String? {
        val snap = snapshot ?: return null
        val matched = snap.activities.count { it.strava != null }
        val total = db.stravaCount()
        return if (total == 0) null else "$total activités Strava, $matched associées à Garmin."
    }

    /** On app start: fill the screens from the local base, then bring it up to date. */
    fun start() {
        refreshStravaProblem()
        if (!loggedIn) return
        reload()
        // New columns (zones, laps, kinds…): fetch the lists again once after an update
        sync(if (settings.initialized && !db.isEmpty() && settings.dataVersion >= DATA_VERSION) "today" else "full")
    }

    fun reload() = thread {
        refreshTitles()
        snapshot = build(db.activities().sortedByDescending { it.startLocal })
        pushTexts()
    }

    /** What the last sending of titles and descriptions did, shown in the Strava card. */
    var textStatus by mutableStateOf("")
    private val pushing = java.util.concurrent.atomic.AtomicBoolean(false)

    /** New runs: their title and description go to the Strava server (only runs after the day the feature started). */
    private fun pushTexts() {
        try {
            StravaTexts.push(settings, db)?.let { stravaWorked(); textStatus = it }
        } catch (e: Exception) {
            db.addLog("E", "strava", "envoi des textes : ${e.javaClass.simpleName}: ${e.message}")
            textStatus = "Textes des nouvelles séances : " + (e.message ?: e.javaClass.simpleName)
        }
    }

    var easyTexts by mutableStateOf(settings.easyTexts)
    fun chooseEasyTexts(on: Boolean) { settings.easyTexts = on; easyTexts = on; db.addLog("I", "strava", "textes automatiques des footings : ${if (on) "activés" else "désactivés"}") }

    /** The text of the latest session already matched with its Strava outing, for the test of the modification. Null when there is none. */
    fun textTestCandidate(): TextItem? {
        val snap = snapshot ?: return null
        return snap.activities.filter { it.id > 0 && it.isRun && it.strava != null }.take(12).firstNotNullOfOrNull { StravaTexts.single(db, snap, it) }
    }

    /** "Envoyer à Strava" on one session, whatever its date. */
    fun sendText(a: ActivityRow) {
        if (!pushing.compareAndSet(false, true)) return
        textStatus = "Envoi en cours…"
        thread {
            textStatus = try {
                StravaSync.settingsLog = { db.addLog("W", "strava", it) }
                // The outing may have appeared on Strava since the last sync: ask the server again before giving up
                runCatching { StravaSync.run(settings, db) }
                val fresh = db.activities().firstOrNull { it.id == a.id } ?: a
                val item = snapshot?.let { StravaTexts.single(db, it, fresh) }
                    ?: db.activities().firstOrNull { it.id == a.id }?.let { row -> StravaTexts.single(db, build(db.activities().sortedByDescending { it.startLocal }), row) }
                if (item == null) {
                    if (fresh.strava == null) {
                        StravaFollowUp.start(context, listOf(a.id))
                        "Cette course n'est pas encore sur Strava : je réessaie chaque minute pendant 45 min et j'envoie le texte dès qu'elle y est."
                    } else "Cette séance n'a pas de texte à envoyer (ni fractionné, ni footing, ni sortie longue)."
                } else {
                    val outcome = StravaTexts.send(settings, db, listOf(item)).also { stravaWorked() }
                    StravaTexts.summary(outcome) + StravaTexts.verifySummary(StravaTexts.verify(settings, db, outcome))
                }
            } catch (e: Exception) {
                db.addLog("E", "strava", "envoi de ${a.id} : ${e.javaClass.simpleName}: ${e.message}")
                e.message ?: e.javaClass.simpleName
            } finally {
                pushing.set(false)
            }
        }
    }

    private fun refreshTitles() {
        if (settings.titlesVersion >= TITLES_VERSION) return
        // Version 12 cut an effort too short on one session: its laps are fetched again from Garmin by the next sync
        if (settings.titlesVersion == 12) for (id in db.detailIds()) if (db.detail(id)?.laps?.any { it.stepIndex == Intervals.APP_SPLIT } == true) db.deleteDetail(id)
        // Sessions already on the phone: cut the efforts the watch did not split, once
        for (id in db.detailIds()) {
            val d = db.detail(id) ?: continue
            Intervals.repairLaps(d.laps, d.samples)?.let { db.saveLaps(id, it) }
        }
        val laps = db.lapsOf(db.lapIds())
        for ((id, l) in laps) db.setIntervalTitle(id, Intervals.analyze(l, db.recoveryModesOf(id))?.title)
        settings.titlesVersion = TITLES_VERSION
    }

    private fun build(activities: List<ActivityRow>): Snapshot {
        val today = LocalDate.now()
        val monday = today.minusDays(today.dayOfWeek.value - 1L)
        val runs = activities.filter { it.isRun }
        fun between(from: LocalDate, to: LocalDate) = runs.filter { it.startLocal.take(10) >= from.toString() && it.startLocal.take(10) <= to.toString() }
        val weeks = (25 downTo 0).map { i ->
            val start = monday.minusWeeks(i.toLong())
            val list = between(start, start.plusDays(6))
            Week(start, list.sumOf { it.distance ?: 0.0 }, list.sumOf { it.duration ?: 0.0 }, list.sumOf { it.elevation ?: 0.0 }, list.size)
        }
        val analysis = Insights.analyze(db)
        return Snapshot(
            activities, db.dailyRows(), analysis, weeks, weeks.last().distance / 1000, weeks[weeks.size - 2].distance / 1000, weeks.last().count,
            db.runs(), settings.garminTotal, SessionHistory(activities.sortedBy { it.startLocal }, db, analysis), LoadCompare.compute(activities, analysis), db.fitnessRows(),
            db.stravaOnlyRows(),
        )
    }

    /** Kind of session chosen by hand (null: back to the automatic detection). Lists, filters and load use it at once. */
    fun setCategory(id: Long, category: String?) = thread {
        db.setManualCategory(id, category)
        reload()
    }

    /** Timed or measured recoveries for one type of efforts, chosen by hand (null: back to the automatic rule). The title follows at once. */
    fun setRecoveryMode(id: Long, key: String, measured: Boolean?) = thread {
        db.setRecoveryMode(id, key, measured)
        db.lapsOf(listOf(id))[id]?.let { db.setIntervalTitle(id, Intervals.analyze(it, db.recoveryModesOf(id))?.title) }
        reload()
    }

    /** "today": latest data. "full": whole history and the whole wellness window. */
    fun sync(mode: String, trigger: String = "manual") {
        if (syncing) return
        thread {
            val status = SyncEngine.run(context, mode, trigger)
            refreshStravaProblem()
            if (stravaConfigured && stravaProblem.isEmpty()) { stravaMessage = ""; checkMessage = ""; textStatus = "" }
            // The runs already on Garmin at the first sync do not notify
            if (status != null && status != "error" && !settings.initialized) RunChecker.markAllSeen(context)
            if (status != null && status != "error") settings.dataVersion = DATA_VERSION
            if (settings.firstName == null) runCatching { auth.refreshProfile() }
            firstName = settings.firstName
            photo = settings.photoUrl
            reload()
        }
    }

    fun login(email: String, password: String) = action("Connexion à Garmin…") {
        when (val result = auth.login(email, password)) {
            is GarminAuth.Login.NeedsMfa -> {
                needsMfa = true
                message = "Garmin demande un code (${result.method}). Saisis-le puis valide."
            }
            GarminAuth.Login.Done -> afterLogin()
        }
    }

    fun verifyMfa(code: String) = action("Vérification du code…") {
        auth.verifyMfa(code)
        needsMfa = false
        afterLogin()
    }

    private fun afterLogin() {
        loggedIn = true
        message = ""
        if (settings.autoCheck) Detection.schedule(context)
        sync("full", "login")
    }

    fun toggleAutoCheck(on: Boolean) {
        settings.autoCheck = on
        autoCheck = on
        if (on) Detection.schedule(context) else Detection.cancel(context)
    }

    fun test() = action("Envoi de la notification de test…") {
        val run = RunChecker.notifyLatest(context)
        message = if (run == null) "Aucune course en local." else "Notification envoyée : ${run.summary}"
    }

    /** Empties the local copy and fetches everything again. The Garmin login is kept. */
    fun reset() {
        if (syncing) return
        db.clear()
        settings.seenIds = emptySet()
        settings.initialized = false
        settings.stravaCursor = null
        snapshot = null
        reload()
        sync("full")
    }

    fun logout() {
        Detection.cancel(context)
        db.clear()
        settings.clear()
        loggedIn = false
        snapshot = null
        firstName = null
        photo = null
        message = ""
    }

    private fun action(label: String, block: () -> Unit) {
        if (busy) return
        busy = true
        message = label
        thread {
            try {
                block()
            } catch (e: Exception) {
                db.addLog("E", "action", "$label : ${e.javaClass.simpleName}: ${e.message}")
                message = "Erreur : ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }
    }
}
