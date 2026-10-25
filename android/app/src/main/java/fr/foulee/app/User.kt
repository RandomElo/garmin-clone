package fr.foulee.app

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The runner's page: who they are as a runner (strengths, things to watch, key figures), then the sync. */
@Composable
fun UserScreen(state: AppState, snap: Snapshot?, onOpen: (ActivityRow) -> Unit = {}) {
    val pal = LocalPal.current
    val report by produceState<ProfileReport?>(null, snap) {
        value = if (snap == null) null else withContext(Dispatchers.Default) { Profile.compute(snap) }
    }
    var openDimension by remember { mutableStateOf<Dimension?>(null) }
    openDimension?.let { DimensionDialog(it) { openDimension = null } }
    Page {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(state.photo, state.firstName, 56.dp)
            Column(Modifier.padding(start = 14.dp)) {
                Text(state.firstName ?: "Utilisateur", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = pal.text)
                Muted("Ton profil de coureur", size = 14)
            }
        }
        report?.let { r ->
            r.summary?.let { Card(highlight = true) { Text(it, color = pal.text, fontSize = 15.sp, lineHeight = 22.sp) } }
            if (r.dimensions.isNotEmpty()) {
                val weakest = r.weakest
                Card(title = "Ton profil") {
                    r.dimensions.forEach { d ->
                        val low = d === weakest
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { openDimension = d }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(d.label, Modifier.width(104.dp), color = pal.text, fontSize = 14.sp, fontWeight = if (low) FontWeight.SemiBold else FontWeight.Normal)
                            ProgressBar(d.score / 100f, if (low) pal.warn else pal.accent, Modifier.weight(1f))
                            Text("${d.score}", Modifier.width(36.dp), textAlign = TextAlign.End, color = if (low) pal.warn else pal.muted, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    Muted("Touche une ligne pour le détail.", Modifier.padding(top = 4.dp))
                }
                weakest?.let { w ->
                    Card(title = "Point faible", onClick = { openDimension = w }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(w.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = pal.text)
                            Text("${w.score} / 100", Modifier.padding(start = 10.dp), color = pal.warn, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        }
                        Text(
                            w.detail.replaceFirstChar { it.uppercase() } + if (w.score >= 85) ". Rien d'inquiétant : c'est ton score le plus bas, mais il reste bon." else ". ${w.advice}",
                            Modifier.padding(top = 6.dp), color = pal.muted, fontSize = 14.sp, lineHeight = 20.sp,
                        )
                    }
                }
            }
            if (r.figures.isNotEmpty()) FoldCard(title = "Chiffres clés", initiallyOpen = true) {
                r.figures.forEachIndexed { i, f ->
                    if (i > 0) Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(1.dp).background(pal.border))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(f.label, color = pal.text, fontSize = 14.sp)
                            f.caption?.let { Muted(it) }
                        }
                        Text(f.value, Modifier.padding(start = 12.dp), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = pal.text)
                    }
                }
            }
            val strengths = r.findings.filter { it.good }
            val watch = r.findings.filter { !it.good }
            if (strengths.isNotEmpty()) FindingsCard("Tes points forts", strengths, pal.good)
            if (watch.isNotEmpty()) FindingsCard("Alertes", watch, pal.warn)
            if (strengths.isEmpty() && watch.isEmpty() && r.figures.isNotEmpty()) Card { Muted("Pas assez d'historique récent pour une analyse détaillée.", size = 14) }
        }
        // A problem with the server stays visible even while the card is folded, and goes away as soon as an exchange works again
        if (state.stravaProblem.isNotEmpty()) Text(state.stravaProblem, color = pal.warn, fontSize = 14.sp, lineHeight = 20.sp)
        StravaCard(state, onOpen)
        FoldSection("Synchronisation", "État de la synchronisation avec Garmin Connect", openWhen = SyncEngine.current != null) { SyncSections(state, snap) }
    }
}

@Composable
private fun FindingsCard(title: String, findings: List<Finding>, color: androidx.compose.ui.graphics.Color) {
    val pal = LocalPal.current
    FoldCard(title = title, initiallyOpen = true) {
        findings.forEachIndexed { i, f ->
            Row(Modifier.fillMaxWidth().padding(top = if (i > 0) 14.dp else 0.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 7.dp).size(10.dp).clip(CircleShape).background(color))
                Column(Modifier.padding(start = 12.dp)) {
                    Text(f.title, fontWeight = FontWeight.SemiBold, color = pal.text, fontSize = 15.sp)
                    Text(f.text, Modifier.padding(top = 2.dp), color = pal.muted, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
        }
    }
}

/** One line of the profile: what is measured, the target, and what to do. */
@Composable
private fun DimensionDialog(d: Dimension, onClose: () -> Unit) {
    val pal = LocalPal.current
    val low = d.score < 85
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = pal.card, titleContentColor = pal.text, textContentColor = pal.text,
        confirmButton = { TextButton(onClose) { Text("Fermer") } },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.label, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Text("${d.score} / 100", color = if (low) pal.warn else pal.accent, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ProgressBar(d.score / 100f, if (low) pal.warn else pal.accent, Modifier.fillMaxWidth())
                d.dots?.let { (filled, total) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        for (i in 0 until total) Box(Modifier.size(12.dp).clip(CircleShape).background(if (i < filled) (if (low) pal.warn else pal.accent) else pal.muted.copy(alpha = 0.25f)))
                    }
                }
                if (d.detail.isNotEmpty()) Text(d.detail.replaceFirstChar { it.uppercase() }, color = pal.text, fontSize = 14.sp, lineHeight = 20.sp)
                if (d.how.isNotEmpty()) Muted(d.how, size = 13)
                if (low) Text(d.advice, color = pal.text, fontSize = 14.sp, lineHeight = 20.sp)
            }
        },
    )
}

/** The home Strava server: optional, the detection of the kind of run works without it. */
@Composable
private fun StravaCard(state: AppState, onOpen: (ActivityRow) -> Unit) {
    val pal = LocalPal.current
    var url by rememberSaveable { mutableStateOf(state.stravaUrl) }
    // Not rememberSaveable: the key must never be written into the saved instance state
    var key by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    state.diagReport?.let { DiagnosticDialog(it) { state.diagReport = null } }
    state.checkReport?.let { LabelReviewDialog(state, it, onOpen) { state.closeReview() } }
    FoldCard(title = "Serveur Strava") {
        Muted("Facultatif. Le serveur donne le nom, la description et les labels de tes sorties Strava, qui aident à reconnaître une course. Sans lui, la détection reste locale. Pour mettre en place le serveur, contacte le développeur.", Modifier.padding(bottom = 12.dp), size = 14)
        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Adresse du serveur") }, placeholder = { Text("strava.home") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(
            key, { key = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text(if (state.hasStravaKey) "Clé (déjà enregistrée)" else "Clé") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ state.saveStrava(url, key); key = "" }, Modifier.weight(1f)) { Text("Enregistrer") }
            if (state.stravaUrl.isNotBlank()) OutlinedButton({ state.testStrava(url, key) }, Modifier.weight(1f), enabled = !state.stravaBusy && url.isNotBlank()) { Text("Tester") }
        }
        if (state.stravaMessage.isNotEmpty()) Muted(state.stravaMessage, Modifier.padding(top = 8.dp), size = 14)
        if (state.stravaUrl.isNotBlank()) Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ state.diagnoseStrava(url) }, Modifier.weight(1f), enabled = !state.diagBusy) { Text(if (state.diagBusy) "Analyse…" else "Diagnostic réseau") }
            // The app only shows the setting: the change is made by the user, in Android
            OutlinedButton({ context.startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }, Modifier.weight(1f)) { Text("Réglages Wi-Fi") }
        }
        if (state.stravaConfigured) {
            Box(Modifier.padding(vertical = 12.dp).fillMaxWidth().height(1.dp).background(pal.border))
            Text("Labels des sorties", fontWeight = FontWeight.SemiBold, color = pal.text, fontSize = 15.sp)
            Muted("Analyse toutes tes sorties et propose les labels Strava (compétition, entraînement, sortie longue). Rien n'est modifié sans ta validation, sortie par sortie. Les titres et descriptions ne concernent que les prochaines séances.", Modifier.padding(top = 2.dp), size = 13)
            OutlinedButton({ state.checkStrava() }, Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !state.checkBusy) { Text(if (state.checkBusy) "En cours…" else "Analyser les labels") }
            if (state.checkMessage.isNotEmpty()) Muted(state.checkMessage, Modifier.padding(top = 8.dp), size = 14)
        }
        state.stravaSummary()?.let { Muted(it, Modifier.padding(top = 4.dp), size = 14) }
        Muted("Le trafic en clair n'est accepté que pour strava.home. La clé est stockée chiffrée.", Modifier.padding(top = 8.dp))
    }
}

private fun statusLabel(status: String) = when (status) {
    "inchange" -> "déjà bon"
    "a-corriger" -> "à corriger"
    "planifie" -> "écriture planifiée"
    "introuvable" -> "introuvable sur Strava"
    "ambigu" -> "plusieurs sorties possibles"
    "conflit" -> "label plus important déjà posé, gardé"
    "ignore" -> "ignoré (musculation, autre sport)"
    "invalide" -> "invalide"
    else -> status
}

/**
 * The labels the server would set, to validate one by one: tick the ones to send, change the proposed kind, or refuse.
 * Nothing is written before "Envoyer", and only what is ticked goes.
 */
@Composable
private fun LabelReviewDialog(state: AppState, report: CheckReport, onOpen: (ActivityRow) -> Unit, onClose: () -> Unit) {
    val pal = LocalPal.current
    val proposals = remember(report) { report.results.filter { it.status == "a-corriger" || it.status == "planifie" } }
    val problems = remember(report) { report.results.filter { it.status == "introuvable" || it.status == "ambigu" } }
    val counts = report.results.groupingBy { it.status }.eachCount()
    val ticked = state.reviewTicked
    val chosen = state.reviewChosen
    val refused = state.reviewRefused
    var compared by remember { mutableStateOf<CheckResult?>(null) }
    compared?.let { r -> r.nearest?.let { CompareDialog(r.item.activity, it, { compared = null; onOpen(r.item.activity) }) { compared = null } } }

    val shown = proposals.filter { it.item.key !in refused }
    val selected = shown.filter { ticked[it.item.key] == true }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = pal.bg) {
            Box(Modifier.fillMaxSize()) {
              Column(Modifier.fillMaxSize()) {
                Column(Modifier.statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp)) {
                    Text("Labels à valider", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = pal.text)
                    Muted("${shown.size} proposition(s) sur ${report.results.size} sorties" + (counts["inchange"]?.let { " · $it déjà bonnes" } ?: ""), size = 13)
                    if (report.simulation) Text(
                        "Le serveur est en simulation : rien ne sera écrit dans Strava.",
                        Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(pal.warn.copy(alpha = 0.15f)).padding(10.dp), color = pal.text, fontSize = 13.sp,
                    )
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Tout cocher", false) { shown.forEach { ticked[it.item.key] = true } }
                        Chip("Tout décocher", false) { ticked.clear() }
                    }
                }
                androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 190.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (shown.isEmpty()) item { Muted("Rien à valider.", size = 14) }
                    items(shown, key = { it.item.key }) { r ->
                        val key = r.item.key
                        val internal = chosen[key]
                        val after = if (internal != null) StravaCheck.labelsOf(internal) else StravaSync.single(r.after)
                        val before = r.before.joinToString(", ") { StravaSync.labelFr(it) }.ifEmpty { "aucun label" }
                        val afterText = after.joinToString(", ") { StravaSync.labelFr(it) }.ifEmpty { "aucun label" }
                        var menu by remember { mutableStateOf(false) }
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(pal.card).padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.Checkbox(ticked[key] == true, { ticked[key] = it })
                                Column(Modifier.weight(1f).clickable { r.item.activity.let { a -> if (a.id > 0) onOpen(a) } }) {
                                    Text(r.item.activity.title, color = pal.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2)
                                    Muted("${Fmt.date(r.item.activity.startLocal)} · ${Fmt.km(r.item.activity.distance)} · ${Categories.label(r.item.activity.category)}", size = 12)
                                }
                            }
                            Text("$before → $afterText", Modifier.padding(top = 6.dp, start = 4.dp), color = pal.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            if (r.before.size > 1) Text("Deux labels sur Strava : un seul sera gardé.", Modifier.padding(start = 4.dp), color = pal.warn, fontSize = 12.sp)
                            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box {
                                    OutlinedButton({ menu = true }) { Text("Changer le type", fontSize = 13.sp) }
                                    androidx.compose.material3.DropdownMenu(menu, { menu = false }) {
                                        listOf("competition", "interval", "long", "easy").forEach { c ->
                                            androidx.compose.material3.DropdownMenuItem({ Text(Categories.label(c)) }, { menu = false; chosen[key] = c; ticked[key] = true })
                                        }
                                    }
                                }
                                androidx.compose.material3.TextButton({ state.declineLabel(r.item); refused += key }) { Text("Refuser", color = pal.muted, fontSize = 13.sp) }
                            }
                        }
                    }
                    if (problems.isNotEmpty()) {
                        item { Text("Sorties introuvables sur Strava (${problems.size})", Modifier.padding(top = 8.dp), color = pal.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
                        items(problems.take(40), key = { "p" + it.item.key }) { r ->
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled = r.nearest != null) { compared = r }.background(pal.card).padding(14.dp)) {
                                Text(r.item.activity.title, color = pal.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2)
                                Muted("${Fmt.date(r.item.activity.startLocal)} · ${statusLabel(r.status)}", size = 12)
                                if (r.nearest != null) Text("Toucher pour comparer avec Strava ›", Modifier.padding(top = 2.dp), color = pal.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
              }
                Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(pal.card).navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 52.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClose, Modifier.weight(1f)) { Text("Annuler") }
                    Button(
                        {
                            val items = selected.map { r -> chosen[r.item.key]?.let { r.item.changedTo(StravaCheck.contractName(it)) } ?: r.item }
                            val manual = selected.mapNotNull { r -> chosen[r.item.key]?.takeIf { r.item.activity.id > 0 }?.let { r.item.activity.id to it } }.toMap()
                            state.applyLabels(items, manual)
                        },
                        Modifier.weight(2f), enabled = selected.isNotEmpty() && !state.checkBusy,
                    ) { Text(if (selected.isEmpty()) "Cocher pour envoyer" else "Envoyer ${selected.size} modification(s)") }
                }
            }
        }
    }
}

/** One row of the comparison: the Garmin value, the Strava value, and a mark when they differ. */
@Composable
private fun CompareRow(label: String, garmin: String, strava: String, differs: Boolean) {
    val pal = LocalPal.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, Modifier.width(64.dp), color = pal.muted, fontSize = 12.sp)
        Text(garmin, Modifier.weight(1f), color = pal.text, fontSize = 13.sp)
        Text(strava, Modifier.weight(1f), color = if (differs) pal.warn else pal.text, fontSize = 13.sp, fontWeight = if (differs) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** Side by side: the Garmin outing and the closest Strava one, so the reason it was not matched can be seen at a glance. */
@Composable
private fun CompareDialog(a: ActivityRow, near: Nearest, onOpenGarmin: () -> Unit, onClose: () -> Unit) {
    val pal = LocalPal.current
    val s = near.strava
    val gap = near.startGapSeconds
    val strava = s.totalSeconds ?: s.movingSeconds
    val gapText = when {
        gap == 0L -> "même heure"
        Math.abs(gap) % 3600 == 0L -> "${if (gap > 0) "+" else "−"}${Math.abs(gap) / 3600} h pile"
        else -> "${if (gap > 0) "+" else "−"}${Math.abs(gap) / 60} min ${Math.abs(gap) % 60} s"
    }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = pal.card, titleContentColor = pal.text, textContentColor = pal.text,
        confirmButton = { TextButton(onOpenGarmin) { Text("Ouvrir la séance Garmin") } },
        dismissButton = { TextButton(onClose) { Text("Fermer") } },
        title = { Text("Garmin / Strava", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.width(64.dp))
                    Text("Garmin", Modifier.weight(1f), color = pal.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("Strava", Modifier.weight(1f), color = pal.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                CompareRow("Nom", a.title, s.name.ifBlank { "sans nom" }, false)
                CompareRow("Jour", Fmt.date(a.startLocal), Fmt.date(s.startLocal), a.startLocal.take(10) != s.startLocal.take(10))
                CompareRow("Départ", a.startLocal.drop(11).take(8), s.startLocal.drop(11).take(8), gap != 0L)
                CompareRow("Durée", Fmt.clock(a.duration), Fmt.clock(strava), (near.durationGapPercent ?: 0) > 2)
                CompareRow("Distance", Fmt.km(a.distance), Fmt.km(s.distance), (near.distanceGapPercent ?: 0) > 2)
                CompareRow("Dénivelé", a.elevation?.let { "${Math.round(it)} m" } ?: "n/d", s.elevation?.let { "${Math.round(it)} m" } ?: "n/d", false)
                Box(Modifier.fillMaxWidth().height(1.dp).background(pal.border))
                Text(
                    "Écart de départ : $gapText" + (near.durationGapPercent?.let { ". Écart de durée : $it %." } ?: "."),
                    color = pal.text, fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium,
                )
                Muted(
                    when {
                        Math.abs(gap) > 6 * 3600 -> "Strava n'a rien d'approchant : la séance n'est peut-être pas sur Strava."
                        Math.abs(gap) % 3600 < 120 && Math.abs(gap) >= 3600 -> "Un décalage d'une heure pile vient d'un souci d'heure d'hiver ou d'été sur l'une des deux."
                        Math.abs(gap) > 120 -> "Le départ dépasse la tolérance de 2 minutes : l'appli ne devine pas."
                        else -> "Le départ est bon : c'est la durée qui diffère trop pour trancher."
                    }, size = 13,
                )
            }
        },
    )
}

/** The step-by-step network report, then what to change. */
@Composable
private fun DiagnosticDialog(report: DiagReport, onClose: () -> Unit) {
    val pal = LocalPal.current
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = pal.card, titleContentColor = pal.text, textContentColor = pal.text,
        confirmButton = { TextButton(onClose) { Text("Fermer") } },
        title = { Text("Diagnostic réseau", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    report.conclusion,
                    Modifier.clip(RoundedCornerShape(10.dp)).background((if (report.good) pal.good else pal.warn).copy(alpha = 0.15f)).padding(10.dp),
                    color = pal.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp,
                )
                report.lines.forEach { l ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            when (l.ok) { true -> "✓"; false -> "✗"; null -> "·" }, Modifier.width(20.dp),
                            color = when (l.ok) { true -> pal.good; false -> pal.bad; null -> pal.muted }, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        )
                        Column {
                            Text(l.title, color = pal.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(l.detail, color = pal.muted, fontSize = 13.sp, lineHeight = 18.sp)
                        }
                    }
                }
            }
        },
    )
}
