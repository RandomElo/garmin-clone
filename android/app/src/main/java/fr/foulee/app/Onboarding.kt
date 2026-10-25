package fr.foulee.app

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private class OnboardingPage(val icon: ImageVector, val title: String, val intro: String, val points: List<String>)

private val pages = listOf(
    OnboardingPage(
        Icons.Filled.DirectionsRun, "Bienvenue dans Foulée", "Ton suivi de course à pied, à partir de ta montre Garmin.",
        listOf("Tes séances et leur analyse, fractionnés compris", "Ta charge d'entraînement et ta forme du jour", "Un profil de coureur : points forts et points faibles"),
    ),
    OnboardingPage(
        Icons.Filled.PhoneAndroid, "Comment ça marche", "Foulée récupère tes données sur Garmin Connect, puis calcule tout sur ton téléphone.",
        listOf("Il faut ton compte Garmin Connect, le même que celui de ta montre", "La première synchro peut prendre quelques minutes", "Strava est facultatif : un serveur perso permet d'y envoyer titres et labels"),
    ),
    OnboardingPage(
        Icons.Filled.Lock, "Tes données restent chez toi", "Rien n'est envoyé à un serveur Foulée.",
        listOf(
            "Tout est stocké sur ton téléphone",
            "Ton mot de passe Garmin n'est jamais conservé : il sert une seule fois à ouvrir la session. Seuls des jetons de session sont gardés, chiffrés",
            "Tu peux te déconnecter à tout moment : les données du téléphone sont alors effacées",
        ),
    ),
)

/** First launch: three short screens, then the Garmin sign-in. Shown once, only to someone who is not signed in. */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val pal = LocalPal.current
    var page by rememberSaveable { mutableStateOf(0) }
    val last = page == pages.lastIndex
    Column(
        Modifier.fillMaxSize().background(pal.bg).statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp)
            // Swipe left for the next screen, right for the previous one
            .pointerInput(page) {
                var drag = 0f
                detectHorizontalDragGestures(
                    onDragStart = { drag = 0f },
                    onDragEnd = { if (drag < -80 && page < pages.lastIndex) page++ else if (drag > 80 && page > 0) page-- },
                ) { _, amount -> drag += amount }
            },
    ) {
        Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.End) {
            if (!last) TextButton(onDone) { Text("Passer", color = pal.muted) }
        }
        androidx.compose.animation.Crossfade(page, Modifier.weight(1f), label = "onboarding") { index ->
          val current = pages[index]
          Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(pal.accent.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(current.icon, null, Modifier.size(36.dp), tint = pal.accent)
            }
            Text(current.title, Modifier.padding(top = 24.dp), color = pal.text, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold)
            Text(current.intro, Modifier.padding(top = 12.dp), color = pal.muted, fontSize = 17.sp, lineHeight = 24.sp)
            current.points.forEach { point ->
                Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 8.dp).size(8.dp).clip(CircleShape).background(pal.accent))
                    Text(point, Modifier.padding(start = 14.dp), color = pal.text, fontSize = 16.sp, lineHeight = 23.sp)
                }
            }
          }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center) {
            pages.indices.forEach { i ->
                Box(Modifier.padding(horizontal = 4.dp).size(if (i == page) 10.dp else 8.dp).clip(CircleShape).background(if (i == page) pal.accent else pal.border))
            }
        }
        Button({ if (last) onDone() else page++ }, Modifier.fillMaxWidth().height(54.dp)) {
            Text(if (last) "Se connecter à Garmin" else "Suivant", fontSize = 17.sp, fontWeight = FontWeight.Medium)
        }
    }
}
