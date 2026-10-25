package fr.foulee.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    Tab("Accueil", Icons.Filled.Home),
    Tab("Activités", Icons.Filled.DirectionsRun),
    Tab("Forme", Icons.Filled.MonitorHeart),
    Tab("Utilisateur", Icons.Filled.Person),
)

private const val USER_TAB = 3

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val state = AppState(applicationContext)
        if (state.loggedIn) RunCheckWorker.schedule(this)
        setContent {
            FouleeTheme {
                LaunchedEffect(Unit) { state.start() }
                // Notifications are asked once the introduction is over, so the system dialog never hides it
                LaunchedEffect(state.onboardingDone) {
                    if (state.onboardingDone && Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                }
                App(state)
            }
        }
    }
}

@Composable
private fun App(state: AppState) {
    val pal = LocalPal.current
    if (!state.loggedIn && !state.onboardingDone) {
        OnboardingScreen { state.finishOnboarding() }
        return
    }
    var tab by rememberSaveable { mutableStateOf(if (state.loggedIn) 0 else USER_TAB) }
    var open by remember { mutableStateOf<ActivityRow?>(null) }
    var review by remember { mutableStateOf(false) }
    // The tab a session was opened from (the validation of the labels lives in the profile tab): back goes there, not to the list
    var openFrom by remember { mutableStateOf<Int?>(null) }
    fun closeDetail() {
        open = null
        openFrom?.let { tab = it }
        openFrom = null
    }
    BackHandler(open != null || review) { if (open != null) closeDetail() else review = false }
    val snap = state.snapshot
    Scaffold(
        containerColor = pal.bg,
        topBar = {
            Row(Modifier.fillMaxWidth().background(pal.card).padding(horizontal = 16.dp, vertical = 12.dp).padding(top = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.logo), null, Modifier.size(24.dp))
                Text("Foulée", Modifier.padding(start = 8.dp).weight(1f).padding(end = 8.dp), fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = pal.text)
                if (state.loggedIn) SyncBadge(state) { tab = USER_TAB; open = null; openFrom = null }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = pal.card) {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i; open = null; openFrom = null; review = false }, icon = { Icon(t.icon, null) }, label = { Text(if (i == USER_TAB) state.firstName ?: t.label else t.label, fontSize = 11.sp, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = pal.accent, selectedTextColor = pal.accent, indicatorColor = pal.accent.copy(alpha = 0.1f), unselectedIconColor = pal.muted, unselectedTextColor = pal.muted),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                tab == USER_TAB || !state.loggedIn -> SyncScreen(state, snap) { openFrom = USER_TAB; tab = 1; open = it }
                snap == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                open != null && (tab == 0 || tab == 1) -> ActivityDetailScreen(state, snap, open!!) { closeDetail() }
                tab == 0 -> HomeScreen(state, snap, { tab = 1 }) { open = it }
                tab == 1 && review -> ReviewScreen(state, snap, { review = false }) { open = it }
                tab == 1 -> ActivitiesScreen(snap, { review = true }) { open = it }
                else -> FormeScreen(snap)
            }
        }
    }
}

/** Health and training load share one tab: a switch on top picks which one is shown. */
@Composable
private fun FormeScreen(snap: Snapshot) {
    var showLoad by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Santé", !showLoad) { showLoad = false }
            Chip("Charge", showLoad) { showLoad = true }
        }
        Box(Modifier.weight(1f)) { if (showLoad) LoadScreen(snap) else HealthScreen(snap) }
    }
}

/** Pill in the top bar, like the web one: progress while syncing, otherwise the state of the last sync. Tap to open the sync page. */
@Composable
private fun SyncBadge(state: AppState, onClick: () -> Unit) {
    val pal = LocalPal.current
    val current = SyncEngine.current
    val last = state.snapshot?.runs?.firstOrNull()
    val (label, color) = when {
        current != null -> "Synchronisation… ${current.progress} %" to pal.accent
        last?.status == "error" -> "Échec de la dernière synchro" to pal.bad
        last?.status == "partial" -> "Synchro partielle" to pal.warn
        else -> Settings(androidx.compose.ui.platform.LocalContext.current).lastSuccessAt.let { at -> (if (at <= 0) "Pas encore synchronisé" else "Synchronisé ${Fmt.relative(at)}") to pal.good }
    }
    Row(
        Modifier.clip(RoundedCornerShape(50)).border(1.dp, pal.border, RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (current != null) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = color)
        else Icon(if (last?.status == "error" || last?.status == "partial") Icons.Filled.Warning else Icons.Filled.CheckCircle, null, Modifier.size(14.dp), tint = color)
        Text(label, Modifier.padding(start = 6.dp), color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}
