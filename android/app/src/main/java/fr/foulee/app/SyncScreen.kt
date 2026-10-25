package fr.foulee.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.min

@Composable
private fun StatusPill(status: String) {
    val pal = LocalPal.current
    val (label, color) = when (status) {
        "running" -> "En cours" to pal.accent
        "success" -> "Réussie" to pal.good
        "partial" -> "Partielle" to pal.warn
        else -> "Échec" to pal.bad
    }
    Text(label, Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 2.dp), color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun StepRow(step: Step) {
    val pal = LocalPal.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            when (step.status) {
                "running" -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = pal.accent)
                "success" -> Icon(Icons.Filled.CheckCircle, null, tint = pal.good)
                "error" -> Icon(Icons.Filled.Cancel, null, tint = pal.bad)
                "skipped" -> Icon(Icons.Filled.Block, null, tint = pal.neutral)
                else -> Icon(Icons.Filled.RadioButtonUnchecked, null, tint = pal.track)
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(step.label, fontWeight = FontWeight.Medium, color = pal.text)
                val detail = when {
                    step.status == "pending" || step.status == "skipped" -> null
                    step.total > 0 -> "${step.count} / ${step.total}"
                    step.status == "success" -> "à jour"
                    else -> null
                }
                if (detail != null) Muted(detail, size = 14)
            }
            step.error?.let { Text(it, color = pal.bad, fontSize = 13.sp, lineHeight = 18.sp) }
        }
    }
}

/** 270° gauge, open at the bottom, with the value in the middle. */
@Composable
fun Gauge(percent: Int, label: String, diameter: androidx.compose.ui.unit.Dp = 170.dp) {
    val pal = LocalPal.current
    Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(diameter)) {
            val stroke = 14.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(pal.track, 135f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            if (percent > 0) drawArc(pal.accent, 135f, 270f * percent / 100f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$percent %", fontSize = (diameter.value / 5).sp, fontWeight = FontWeight.SemiBold, color = pal.text)
            Muted(label)
        }
    }
}

@Composable
private fun Labeled(label: String, value: String, fraction: Float) {
    val pal = LocalPal.current
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = pal.text, fontSize = 14.sp)
            Muted(value, size = 14)
        }
        ProgressBar(fraction, pal.accent, Modifier.padding(top = 4.dp).fillMaxWidth())
    }
}

private fun kind(trigger: String, mode: String): String {
    val base = when (trigger) { "auto" -> "Auto"; "login" -> "Connexion"; else -> "Manuelle" }
    return if (mode == "today") "$base (jour)" else base
}

@Composable
fun SyncScreen(state: AppState, snap: Snapshot?, onOpen: (ActivityRow) -> Unit = {}) {
    if (!state.loggedIn) LoginScreen(state) else UserScreen(state, snap, onOpen)
}

@Composable
private fun LoginScreen(state: AppState) {
    var email by rememberSaveable { mutableStateOf("") }
    // Not rememberSaveable: the password and the code must never be written into the saved instance state
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    Page {
        Section("Synchronisation", "Connecte ton compte Garmin Connect")
        Card {
            Muted("Tout est calculé sur le téléphone. Le mot de passe n'est jamais conservé : il sert une fois à ouvrir la session, seuls les jetons de session sont gardés, chiffrés.", Modifier.padding(bottom = 12.dp), size = 14)
            OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("E-mail Garmin") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Mot de passe") }, singleLine = true,
                visualTransformation = if (showPassword) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    androidx.compose.material3.IconButton({ showPassword = !showPassword }) {
                        Icon(if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (showPassword) "Masquer le mot de passe" else "Afficher le mot de passe")
                    }
                },
            )
            if (state.needsMfa) {
                OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Code de vérification") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Button({ state.verifyMfa(code.trim()); code = "" }, Modifier.fillMaxWidth().padding(top = 12.dp), enabled = !state.busy && code.isNotBlank()) { Text("Valider le code") }
            } else {
                Button({ state.login(email.trim(), password); password = "" }, Modifier.fillMaxWidth().padding(top = 12.dp), enabled = !state.busy && email.isNotBlank() && password.isNotEmpty()) { Text("Se connecter") }
            }
        }
        if (state.busy) Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Muted(state.message, Modifier.padding(start = 12.dp), size = 14)
        } else if (state.message.isNotEmpty()) Muted(state.message, size = 14)
    }
}

/** The sync cards, below the profile on the user page. */
@Composable
fun SyncSections(state: AppState, snap: Snapshot?) {
    val pal = LocalPal.current
    val current = SyncEngine.current
    val last = snap?.runs?.firstOrNull()
    val settings = Settings(androidx.compose.ui.platform.LocalContext.current)
    var confirming by remember { mutableStateOf(false) }
    // Ticks every second while a sync runs, so the remaining time moves
    val now by produceState(System.currentTimeMillis(), current) { while (current != null) { value = System.currentTimeMillis(); delay(1000) } }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(
            title = if (current != null) "Synchronisation en cours" else "Dernière synchronisation",
            action = { (current?.let { "running" } ?: last?.status)?.let { StatusPill(it) } },
        ) {
            if (current != null) {
                val eta = if (now > 0) current.etaSeconds() else null
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Progression", color = pal.text, fontSize = 14.sp)
                    Muted("${current.progress} %" + (eta?.let { " · encore ~${Fmt.eta(it)}" } ?: ""), size = 14)
                }
                ProgressBar(current.progress / 100f, pal.accent, Modifier.padding(top = 4.dp, bottom = 12.dp).fillMaxWidth())
                current.steps.filter { it.key != "strava" || it.status != "skipped" || it.error != null }.forEach { StepRow(it) }
                Muted("${kind(current.trigger, current.mode)} · ne ferme pas l'appli, tu peux changer d'onglet.", Modifier.padding(top = 8.dp))
            } else if (last != null) {
                Text(
                    if (last.status == "error") "La synchronisation a échoué." else "${last.newActivities} nouvelle${if (last.newActivities > 1) "s" else ""} activité${if (last.newActivities > 1) "s" else ""}, ${last.days} jour${if (last.days > 1) "s" else ""} de santé.",
                    color = pal.text,
                )
                last.error?.let { Text(it, Modifier.padding(top = 4.dp), color = pal.bad, fontSize = 14.sp, lineHeight = 20.sp) }
                Muted("${kind(last.trigger, last.mode)}, ${Fmt.relative(last.startedAt)} · durée ${Fmt.eta((last.finishedAt - last.startedAt) / 1000.0)}", Modifier.padding(top = 8.dp))
            } else {
                Muted("Aucune synchronisation pour l'instant.", size = 14)
            }

            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ state.sync("today") }, Modifier.weight(1f), enabled = current == null) {
                    Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
                    Text(if (current != null) "En cours…" else "Aujourd'hui", Modifier.padding(start = 6.dp))
                }
                OutlinedButton({ state.sync("full") }, Modifier.weight(1f), enabled = current == null) { Text("Synchro complète") }
            }

            Column(Modifier.padding(top = 16.dp)) {
                listOf(
                    "Source" to "Garmin Connect",
                    "Dernière réussite" to Fmt.relative(settings.lastSuccessAt),
                    "Vérification auto" to if (state.autoCheck) "toutes les 15 min" else "désactivée",
                    "Dernière vérification" to if (state.autoCheck) Fmt.relative(settings.lastCheckAt) else "–",
                ).forEach { (l, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Muted(l, size = 14)
                        Text(v, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = pal.text)
                    }
                }
            }
        }

        if (snap != null) {
            val acts = snap.activities.size
            val actsTotal = if (snap.garminTotal > 0) min(snap.garminTotal, 800) else acts
            val actsRatio = if (actsTotal > 0) min(acts.toFloat() / actsTotal, 1f) else 1f
            val days = snap.daily.size
            val daysRatio = min(days / Sync.WINDOW_DAYS.toFloat(), 1f)
            Card(title = "Données récupérées") {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Gauge(((actsRatio + daysRatio) / 2 * 100).toInt(), "de l'historique en local")
                }
                Labeled("Activités", "$acts / $actsTotal", actsRatio)
                Labeled("Santé (${Sync.WINDOW_DAYS} jours)", "$days / ${Sync.WINDOW_DAYS}", daysRatio)
                if (actsRatio < 1f || daysRatio < 1f) Muted("Il manque encore des données : « Synchro complète » les récupère.", Modifier.padding(top = 8.dp))
            }
        }

        Card(title = "Notification de fin de course") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Checkbox(state.autoCheck, { state.toggleAutoCheck(it) })
                Column(Modifier.padding(start = 4.dp, top = 12.dp)) {
                    Text("Vérification automatique", fontWeight = FontWeight.Medium, color = pal.text)
                    Muted("Toutes les 15 minutes, l'appli regarde s'il y a une nouvelle course sur Garmin et envoie la notification (distance, allure, forme, fatigue). Décoché, rien ne se lance tout seul.", size = 14)
                }
            }
            OutlinedButton({ state.test() }, Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !state.busy) { Text("Tester avec la dernière course") }
            if (state.message.isNotEmpty()) Muted(state.message, Modifier.padding(top = 8.dp), size = 14)
        }

        Card(title = "Historique") {
            if (snap == null || snap.runs.isEmpty()) {
                Muted("Pas encore d'historique.", size = 14)
            } else {
                snap.runs.forEachIndexed { i, run ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("${Fmt.dateTime(run.startedAt)} · ${kind(run.trigger, run.mode)}", fontSize = 14.sp, color = pal.text)
                            StatusPill(run.status)
                        }
                        Muted(
                            run.error ?: "${run.newActivities} nouvelle(s), ${run.days} jour(s) de santé · ${Fmt.eta((run.finishedAt - run.startedAt) / 1000.0)}",
                            Modifier.padding(top = 2.dp),
                        )
                    }
                    if (i < snap.runs.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(pal.border))
                }
            }
        }

        Card(title = "Réinitialiser") {
            Muted("Supprime les données locales (activités, santé, historique) puis récupère tout à nouveau depuis Garmin. La connexion est conservée.", size = 14)
            if (confirming) {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ confirming = false }, Modifier.weight(1f)) { Text("Annuler") }
                    Button({ confirming = false; state.reset() }, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = pal.bad)) { Text("Confirmer") }
                }
            } else {
                OutlinedButton({ confirming = true }, Modifier.fillMaxWidth().padding(top = 12.dp), enabled = current == null, colors = ButtonDefaults.outlinedButtonColors(contentColor = pal.bad)) { Text("Réinitialiser les données") }
            }
        }

        OutlinedButton({ state.logout() }, Modifier.fillMaxWidth(), enabled = current == null, colors = ButtonDefaults.outlinedButtonColors(contentColor = pal.bad)) { Text("Se déconnecter de Garmin") }
    }
}
