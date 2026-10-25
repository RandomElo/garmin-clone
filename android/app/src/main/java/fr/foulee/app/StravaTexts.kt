package fr.foulee.app

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** A title and a description ready to go to Strava for one interval session. */
class TextItem(val activity: ActivityRow, val text: StravaText, val key: String, val hash: String, val json: JSONObject)

/**
 * Titles and descriptions of the new interval sessions, sent to the home server (POST /api/seances). Only runs that started after the
 * feature was switched on go: the history is never touched. A text that changed since the last real sending (a recovery corrected by hand)
 * goes again, which the server allows because the description it replaces carries the Foulée signature.
 */
object StravaTexts {
    private const val CHUNK = 5
    private const val TIMEOUT_MS = 120_000

    /** "Forme du jour" of the day of the session: the night before for a morning run, the day itself for an afternoon one. */
    fun readinessOf(a: ActivityRow, analysis: Analysis): Int? {
        val hour = a.startLocal.drop(11).take(2).toIntOrNull() ?: 0
        val date = a.startLocal.take(10)
        return analysis.readinessOn(date, if (hour >= 14) date else LocalDate.parse(date).minusDays(1).toString())?.score
    }

    /** The text of one session, with the two form figures of its day. Null when the laps do not make an interval session. */
    fun textOf(a: ActivityRow, analysis: Analysis, laps: List<Lap>?): StravaText? {
        val iv = laps?.let { Intervals.analyze(it, a.recoveryModes) } ?: return null
        return StravaTextBuilder.build(iv, analysis.weekLoadOn(a), readinessOf(a, analysis), a.speciality)
    }

    private fun itemOf(a: ActivityRow, text: StravaText): TextItem {
        val hash = (text.name + "\n" + text.description).hashCode().toString(16)
        val json = JSONObject().put("stravaId", a.strava!!.id).put("nom", text.name).put("description", text.description).put("ecraser", false)
        return TextItem(a, text, "t${a.id}", hash, json)
    }

    /** Interval runs started after [since], already matched with their Strava outing, whose text was not really sent yet in this form. */
    fun pending(db: Db, snap: Snapshot, since: String): List<TextItem> {
        val sent = db.stravaSent()
        val candidates = snap.activities.filter { it.id > 0 && it.isRun && it.category == "interval" && it.strava != null && it.startLocal > since }
        if (candidates.isEmpty()) return emptyList()
        val laps = db.lapsOf(candidates.map { it.id })
        return candidates.mapNotNull { a -> textOf(a, snap.analysis, laps[a.id])?.let { itemOf(a, it) } }.filter { sent[it.key]?.first != it.hash }
    }

    /** The text of one session, to send by hand whatever its date. */
    fun single(db: Db, snap: Snapshot, a: ActivityRow): TextItem? {
        if (a.strava == null) return null
        return textOf(a, snap.analysis, db.lapsOf(listOf(a.id))[a.id])?.let { itemOf(a, it) }
    }

    class Outcome(val simulation: Boolean, val counts: Map<String, Int>, val items: List<Pair<TextItem, String>>)

    fun send(settings: Settings, db: Db, items: List<TextItem>): Outcome {
        val base = StravaSync.normalize(settings.stravaUrl) ?: throw StravaError("Pas d'adresse de serveur Strava.")
        val key = settings.stravaKey ?: throw StravaError("Pas de clé pour le serveur Strava.")
        var simulation = false
        val statuses = mutableListOf<Pair<TextItem, String>>()
        for (chunk in items.chunked(CHUNK)) {
            val body = JSONObject().put("activites", JSONArray(chunk.map { it.json })).toString()
            val json = StravaSync.post(base, key, "/api/seances", body, TIMEOUT_MS)
            if (!json.has("simulation") || json.optBoolean("simulation", true)) simulation = true
            val answers = json.optJSONArray("resultats") ?: throw StravaError("Réponse inattendue du serveur Strava.")
            if (answers.length() != chunk.size) throw StravaError("Le serveur a répondu pour ${answers.length()} sorties sur ${chunk.size}.")
            val done = chunk.mapIndexed { i, item -> item to answers.getJSONObject(i).optString("statut") }
            statuses += done
            // Written, already identical, or protected (a text Eloi wrote himself): not asked again. The rest is retried later.
            db.markStravaSent(done.filter { !simulation && it.second in setOf("ecrit", "inchange", "protege") }.map { (item, _) -> Triple(item.key, item.hash, "texte") })
        }
        return Outcome(simulation, statuses.groupingBy { it.second }.eachCount(), statuses)
    }

    private fun label(status: String) = when (status) {
        "ecrit" -> "écrit"
        "planifie" -> "prévu"
        "inchange" -> "déjà à jour"
        "protege" -> "description à toi gardée"
        "introuvable" -> "introuvable sur Strava"
        "ambigu" -> "plusieurs sorties possibles"
        "invalide" -> "refusé par le serveur"
        else -> status
    }

    fun summary(o: Outcome) = (if (o.simulation) "Simulation, rien n'a été écrit dans Strava : " else "") +
        o.counts.entries.joinToString(", ") { "${it.value} ${label(it.key)}" }
}
