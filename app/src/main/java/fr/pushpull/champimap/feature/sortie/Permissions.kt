package fr.pushpull.champimap.feature.sortie

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import fr.pushpull.champimap.feature.bonus.champiText

/*
 * Demande des autorisations de la sortie, avec une explication AVANT la fenêtre d'Android :
 *  - position précise (trace, spots, retour à la voiture) ;
 *  - notifications (Android 13+ : notification « Sortie en cours », alertes nuit/batterie).
 * Si la position a été refusée définitivement, propose d'ouvrir les paramètres de l'appli.
 */

/** Permissions nécessaires à une sortie. */
private fun sortiePermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

/**
 * Rien à demander ? Position précise accordée suffit (les notifications sont demandées en même temps
 * la première fois ; un refus des notifications ne bloque jamais la sortie).
 */
private fun nothingToAsk(context: Context): Boolean = LocationUtils.hasFineLocation(context)

/** Demandeur d'autorisations : `request { positionAccordée -> … }`. */
class PermissionRequester internal constructor(private val start: ((Boolean) -> Unit) -> Unit) {
    fun request(then: (Boolean) -> Unit) = start(then)
}

/**
 * Prépare la demande d'autorisations (affiche ses propres fenêtres d'explication).
 * Le rappel reçoit `true` si la position précise est accordée.
 */
@Composable
fun rememberSortiePermissions(): PermissionRequester {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    var showRationale by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true || LocationUtils.hasFineLocation(context)
        if (!fine) {
            // Refus définitif : Android n'affichera plus sa fenêtre, on propose les paramètres.
            val activity = context.findActivity()
            val canAskAgain = activity != null &&
                ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)
            if (!canAskAgain) { showSettings = true; return@rememberLauncherForActivityResult }
        }
        pending?.invoke(fine)
        pending = null
    }

    if (showRationale) {
        AlertDialog(
            onDismissRequest = { showRationale = false; pending?.invoke(false); pending = null },
            title = { Text(champiText("Position et notifications")) },
            text = {
                Text(
                    champiText(
                        "Champimap a besoin de ta position précise pour enregistrer ta trace, tes spots et te ramener " +
                            "à ta voiture. Elle reste sur ton téléphone : l'appli n'a pas accès à Internet.\n\n" +
                            "Les notifications affichent « Sortie en cours » (Marquer un spot, Terminer) " +
                            "et les alertes de nuit et de batterie.",
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { showRationale = false; launcher.launch(sortiePermissions()) }) { Text(champiText("Continuer")) }
            },
            dismissButton = {
                TextButton(onClick = { showRationale = false; pending?.invoke(false); pending = null }) { Text(champiText("Pas maintenant")) }
            },
        )
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false; pending?.invoke(false); pending = null },
            title = { Text(champiText("Position refusée")) },
            text = {
                Text(
                    champiText(
                        "Sans la position, pas de trace, pas de spot précis ni de flèche vers la voiture. " +
                            "Tu peux l'autoriser dans les paramètres : Autorisations → Position → « Autoriser seulement " +
                            "si l'appli est en cours d'utilisation », avec « Position exacte ».",
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showSettings = false
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                    pending?.invoke(false)
                    pending = null
                }) { Text(champiText("Ouvrir les paramètres")) }
            },
            dismissButton = {
                TextButton(onClick = { showSettings = false; pending?.invoke(false); pending = null }) { Text(champiText("Continuer sans")) }
            },
        )
    }

    return remember(context) {
        PermissionRequester { then ->
            if (nothingToAsk(context)) then(true)
            else {
                pending = then
                showRationale = true
            }
        }
    }
}
