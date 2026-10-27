package fr.pushpull.champimap.feature.reglages

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextAlign
import fr.pushpull.champimap.core.util.estPluriel
import fr.pushpull.champimap.feature.bonus.champiText

/*
 * Fenêtre affichée après avoir coché un (ou des) nouveau(x) département(s) sans carte :
 * « Synchroniser maintenant » lance la synchro Champimap Connect (qui ne télécharge que les nouveautés),
 * « Plus tard » rappelle le chemin pour la lancer soi-même.
 */

/** Chemin affiché pour lancer la synchro plus tard. */
private const val CHEMIN_SYNCHRO = "Paramètres › Champimap Connect › Lancer la synchro"

/**
 * @param codes départements nouvellement cochés dont la carte manque.
 * @param onSyncNow « Synchroniser maintenant ».
 * @param onLater « Plus tard » (ou fermeture de la fenêtre).
 */
@Composable
fun NouvelleCarteDialog(codes: List<String>, onSyncNow: () -> Unit, onLater: () -> Unit) {
    val plusieurs = estPluriel(codes.size.toLong())
    val noms = codes.map { Departements.get(it)?.libelle ?: it }.joinToString(", ")
    val titre = if (plusieurs) "Nouvelles cartes à télécharger" else "Nouvelle carte à télécharger"
    val texte = (if (plusieurs) "$noms : ces cartes ne sont pas encore sur ton téléphone." else "$noms : la carte n'est pas encore sur ton téléphone.") +
        " Synchronise maintenant avec Champimap Connect : seuls les nouveaux départements sont téléchargés, " +
        "tes cartes actuelles ne bougent pas.\n\nTu préfères plus tard ? $CHEMIN_SYNCHRO."
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(champiText(titre), textAlign = TextAlign.Center) },
        text = { Text(champiText(texte)) },
        confirmButton = { TextButton(onClick = onSyncNow) { Text(champiText("Synchroniser maintenant")) } },
        dismissButton = { TextButton(onClick = onLater) { Text(champiText("Plus tard")) } },
    )
}
