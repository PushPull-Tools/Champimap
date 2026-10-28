package fr.pushpull.champimap

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.settings.AppSettings
import fr.pushpull.champimap.core.ui.StartupWarningScreen
import fr.pushpull.champimap.core.ui.StartupWarningState
import fr.pushpull.champimap.core.ui.theme.ChampimapTheme
import fr.pushpull.champimap.feature.reglages.LockGate
import fr.pushpull.champimap.nav.AppNav

/**
 * Activité unique. FragmentActivity (et non ComponentActivity) car le verrouillage
 * par empreinte (BiometricPrompt) en a besoin.
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingFile(intent)
        setContent { Root() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingFile(intent)
    }

    /** Fichier de spot partagé (.champi) reçu : on le mémorise pour l'écran d'import. */
    private fun handleIncomingFile(intent: Intent?) {
        val uri: Uri = intent?.data ?: return
        if (intent.action == Intent.ACTION_VIEW) PendingImport.uri = uri
    }
}

/** Fichier partagé en attente d'import (lu par l'écran d'import du module groupe). */
object PendingImport {
    @Volatile var uri: Uri? = null
}

@Composable
private fun Root() {
    val settings by AppGraph.settings.flow.collectAsState(initial = null)
    val s: AppSettings = settings ?: return // attend la lecture des réglages (quelques ms)
    // Avertissement « Prudence » : une fois par lancement à froid (état du processus).
    var warningShown by remember { mutableStateOf(StartupWarningState.shown) }
    ChampimapTheme(s) {
        LockGate {
            if (!warningShown) {
                // Après le code de verrouillage, avant l'accueil / le premier lancement.
                // Un fichier .champi reçu reste dans PendingImport : l'import s'ouvre juste après.
                StartupWarningScreen(onDone = {
                    StartupWarningState.shown = true
                    warningShown = true
                })
            } else {
                AppNav(startOnboarding = !s.onboardingDone, importPending = PendingImport.uri != null)
            }
        }
    }
}
