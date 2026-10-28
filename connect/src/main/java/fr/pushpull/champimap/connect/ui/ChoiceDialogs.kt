package fr.pushpull.champimap.connect.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import fr.pushpull.champimap.connect.download.IaModelDownloader
import fr.pushpull.champimap.connect.download.IaModelDownloader.IaRelease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Fenêtres affichées par Connect AVANT de lancer une synchro :
 *  - MeteredDialog : « Tu es sur les données mobiles » (taille estimée, Attendre le Wi-Fi / Continuer) ;
 *  - IaVersionDialog : choix de la version du modèle d'identification (la plus récente ou une version précise),
 *    avec les statistiques publiées de chaque version.
 */

/** Texte de rappel affiché sous les boutons de synchro. */
const val WIFI_REMINDER = "Conseillé : en Wi-Fi (cartes et modèle d'identification volumineux)"

/** Données mobiles : la synchro ne part qu'après un choix explicite. Retour = « Attendre le Wi-Fi ». */
@Composable
fun MeteredDialog(estimate: String, onWait: () -> Unit, onContinue: () -> Unit) {
    AlertDialog(
        onDismissRequest = onWait,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text("Tu es sur les données mobiles") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("À télécharger : $estimate.")
                Text(
                    "Les cartes et le modèle d'identification sont volumineux : le Wi-Fi est conseillé " +
                        "pour ne pas entamer ton forfait.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onWait) { Text("Attendre le Wi-Fi") } },
        dismissButton = { TextButton(onClick = onContinue) { Text("Continuer quand même") } },
    )
}

/** État de la liste des versions publiées. */
private sealed interface ReleasesState {
    data object Loading : ReleasesState
    data class Ready(val list: List<IaRelease>) : ReleasesState
    data class Failed(val message: String) : ReleasesState
}

/**
 * Choix de la version du modèle.
 * @param installed version installée dans l'appli (marquée « installé »), null si aucune
 * @param onPick version choisie ; `latest = true` pour « La plus récente » (suivra les nouvelles versions)
 */
@Composable
fun IaVersionDialog(installed: String?, onPick: (release: IaRelease, latest: Boolean) -> Unit, onCancel: () -> Unit) {
    // Liste publique des releases GitHub (GET non authentifié), lue une seule fois.
    val state by produceState<ReleasesState>(initialValue = ReleasesState.Loading) {
        value = withContext(Dispatchers.IO) {
            runCatching { IaModelDownloader.listReleases() }.fold(
                onSuccess = { if (it.isEmpty()) ReleasesState.Failed("Aucun modèle publié pour le moment.") else ReleasesState.Ready(it) },
                onFailure = {
                    ReleasesState.Failed(
                        if (it is java.net.UnknownHostException) "Pas de connexion Internet." else "Liste des versions indisponible.",
                    )
                },
            )
        }
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Version du modèle d'identification") },
        text = {
            when (val s = state) {
                ReleasesState.Loading -> CircularProgressIndicator(Modifier.size(32.dp))
                is ReleasesState.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error)
                is ReleasesState.Ready -> Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val latest = s.list.first()
                    VersionOption(
                        title = "La plus récente (conseillé)",
                        detail = latest.summary() + if (latest.tag == installed) " · installé" else "",
                        highlighted = true,
                        onClick = { onPick(latest, true) },
                    )
                    Text(
                        "Ou une version précise (pour revenir à une ancienne version) :",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    s.list.forEach { r ->
                        VersionOption(
                            title = r.tag + if (r.tag == installed) " · installé" else "",
                            detail = r.summary(),
                            highlighted = false,
                            onClick = { onPick(r, false) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text("Annuler") } },
    )
}

/** Une ligne de choix (carte cliquable : titre + statistiques). */
@Composable
private fun VersionOption(title: String, detail: String, highlighted: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
