package fr.pushpull.champimap.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.pushpull.champimap.R
import fr.pushpull.champimap.core.ui.theme.ChampiColors
import fr.pushpull.champimap.core.ui.theme.SafetyTheme

/*
 * Avertissement « Prudence » plein écran, affiché à chaque lancement à froid de l'appli
 * (nouveau processus), jamais au simple retour d'arrière-plan :
 *  - après le verrouillage par code (s'il est activé), avant l'accueil ou le premier lancement ;
 *  - barre fine qui se remplit en 4 s, puis passage automatique ;
 *  - bouton « J'ai compris » actif après 2 s pour passer plus vite.
 * Écran de sécurité : SafetyTheme (clair/sombre normal, lisible même en mode party).
 */

/** Mémoire de l'avertissement pour la vie du processus (remise à zéro au lancement à froid seulement). */
object StartupWarningState {
    /** Vrai une fois l'avertissement vu : rotation ou retour d'arrière-plan ne le réaffichent pas. */
    @Volatile var shown: Boolean = false
}

/** Durée totale d'affichage avant passage automatique (ms). */
private const val DUREE_MS = 4_000

/** Part de la durée après laquelle « J'ai compris » devient actif (2 s sur 4 s). */
private const val BOUTON_ACTIF_A = 0.5f

/** Texte validé de l'avertissement. */
private const val TEXTE_PRUDENCE =
    "Champimap t'aide à noter et à reconnaître tes champignons, mais ne remplace pas un expert.\n\n" +
        "Ne consomme jamais un champignon identifié seulement par l'appli.\n\n" +
        "En cas de doute : pharmacien ou société mycologique.\n\n" +
        "Intoxication : 15 ou centre antipoison."

/**
 * Écran « Prudence ».
 * @param onDone appelé une seule fois (fin des 4 s ou appui sur « J'ai compris »).
 */
@Composable
fun StartupWarningScreen(onDone: () -> Unit) {
    // Progression de 0 à 1, linéaire sur 4 s ; à la fin → passage automatique.
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, animationSpec = tween(durationMillis = DUREE_MS, easing = LinearEasing))
        onDone()
    }
    val buttonEnabled = progress.value >= BOUTON_ACTIF_A

    SafetyTheme {
        Surface(
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            ) {
                // Barre de progression fine tout en haut.
                LinearProgressIndicator(
                    progress = { progress.value },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                // Contenu centré (défile si la police système est très grande).
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
                ) {
                    // Logo de l'appli : même dessin que l'icône (épingle sur fond vert mousse).
                    Box(
                        Modifier.size(104.dp).background(ChampiColors.Mousse, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_launcher_foreground),
                            contentDescription = "Logo Champimap",
                            modifier = Modifier.size(104.dp),
                        )
                    }
                    Text(
                        "Prudence",
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        TEXTE_PRUDENCE,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                    )
                }
                // Bouton pour passer, actif après 2 s.
                Box(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                    ChampiButton("J'ai compris", onClick = onDone, enabled = buttonEnabled)
                }
            }
        }
    }
}
