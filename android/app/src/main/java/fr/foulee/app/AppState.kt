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
private const val TITLES_VERSION = 5

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

    fun testStrava(url: String, key: String) {
        if (stravaBusy) return
        stravaBusy = true
        stravaMessage = "Test en cours…"
        thread {
            stravaMessage = try {
                StravaSync.test(url, key.ifBlank { settings.stravaKey }).also { stravaWorked() }
            } catch (e: Exception) {
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
    // In simulation nothing is remembered as sent: this keeps the same text from going again at every reload
    private val simulated = mutableSetOf<String>()

    /** New interval runs: their title and description go to the Strava server (only runs after the day the feature started). */
    private fun pushTexts() {
        val snap = snapshot ?: return
        if (settings.stravaUrl.isNullOrBlank() || !hasStravaKey || !pushing.compareAndSet(false, true)) return
        try {
            val since = settings.textsSince ?: run {
                settings.textsSince = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                return
            }
            val items = StravaTexts.pending(db, snap, since).filter { "${it.key}:${it.hash}" !in simulated }
            if (items.isEmpty()) return
            val outcome = StravaTexts.send(settings, db, items)
            stravaWorked()
            if (outcome.simulation) items.forEach { simulated += "${it.key}:${it.hash}" }
            textStatus = "Textes des nouveaux fractionnés : " + StravaTexts.summary(outcome)
        } catch (e: Exception) {
            textStatus = "Textes des nouveaux fractionnés : " + (e.message ?: e.javaClass.simpleName)
        } finally {
            pushing.set(false)
        }
    }

    /** "Envoyer à Strava" on one session, whatever its date. */
    fun sendText(a: ActivityRow) {
        val snap = snapshot ?: return
        if (!pushing.compareAndSet(false, true)) return
        textStatus = "Envoi en cours…"
        thread {
            textStatus = try {
                val item = StravaTexts.single(db, snap, a) ?: throw StravaError("Cette séance n'est pas encore associée à une sortie Strava.")
                StravaTexts.send(settings, db, listOf(item)).also { stravaWorked() }.let { StravaTexts.summary(it) }
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            } finally {
                pushing.set(false)
            }
        }
    }

    private fun refreshTitles() {
        if (settings.titlesVersion >= TITLES_VERSION) return
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
        if (settings.autoCheck) RunCheckWorker.schedule(context)
        sync("full", "login")
    }

    fun toggleAutoCheck(on: Boolean) {
        settings.autoCheck = on
        autoCheck = on
        if (on) RunCheckWorker.schedule(context) else RunCheckWorker.cancel(context)
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
        RunCheckWorker.cancel(context)
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
                message = "Erreur : ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }
    }
}
