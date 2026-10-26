package fr.pushpull.champimap.feature.reglages

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import fr.pushpull.champimap.core.ErrorLogger
import fr.pushpull.champimap.core.ui.ChampiButton
import fr.pushpull.champimap.core.ui.ChampiCard
import fr.pushpull.champimap.core.ui.ChampiOutlinedButton
import fr.pushpull.champimap.core.ui.ChampiScreen
import fr.pushpull.champimap.core.ui.SectionTitle
import fr.pushpull.champimap.core.ui.VSpace
import fr.pushpull.champimap.feature.bonus.champiText
import fr.pushpull.champimap.nav.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Sauvegarde et export : copie complète (base + photos), chiffrée par mot de passe ou non,
 * restauration (avec confirmation, puis redémarrage), export CSV des récoltes, accès à la corbeille.
 * Les fichiers sont choisis par l'utilisateur via le sélecteur Android (aucun accès disque large).
 */

/** Sauvegarde et export. Signature appelée par AppNav. */
@Composable
fun SauvegardeScreen(nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var password2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var stepLabel by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    var importNeedsPassword by remember { mutableStateOf(false) }
    var importPassword by remember { mutableStateOf("") }

    val reporter = BackupManager.Progress { f, l ->
        scope.launch { progress = f; stepLabel = l }
    }

    /** Lance une opération longue avec avancement et message final. */
    fun launchTask(okMessage: String, failMessage: String, block: suspend () -> Unit) {
        busy = true; message = null; progress = 0f
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { block() }.onFailure { ErrorLogger.log("Sauvegarde", failMessage, it) }
            }
            busy = false
            message = if (ok.isSuccess) okMessage else when (ok.exceptionOrNull()) {
                is BackupCrypto.BadPasswordException -> "Mot de passe incorrect (ou fichier abîmé)."
                else -> failMessage
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            val pwd = password.takeIf { it.isNotEmpty() }?.toCharArray()
            launchTask("Sauvegarde terminée. Garde-la en lieu sûr (et le mot de passe !).", "La sauvegarde n'a pas fonctionné.") {
                BackupManager.export(context, uri, pwd, reporter)
            }
        }
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) launchTask("Export CSV terminé.", "L'export CSV n'a pas fonctionné.") { BackupManager.exportCsv(context, uri) }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val enc = withContext(Dispatchers.IO) { runCatching { BackupManager.isEncrypted(context, uri) }.getOrNull() }
                if (enc == null) { message = "Ce fichier est illisible."; return@launch }
                importNeedsPassword = enc
                importPassword = ""
                pendingImport = uri
            }
        }
    }

    val passwordsOk = password == password2
    ChampiScreen(title = "Sauvegarde et export", onBack = { nav.popBackStack() }) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (busy) {
                ChampiCard(Modifier.fillMaxWidth()) {
                    Text(champiText("En cours : $stepLabel"), style = MaterialTheme.typography.titleMedium)
                    VSpace(8.dp)
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text("${(progress * 100).toInt()} %", style = MaterialTheme.typography.labelMedium)
                }
            }
            message?.let {
                ChampiCard(Modifier.fillMaxWidth()) { Text(champiText(it), style = MaterialTheme.typography.bodyLarge) }
            }

            SectionTitle("Sauvegarde complète")
            ChampiCard(Modifier.fillMaxWidth()) {
                Text(
                    champiText("Spots, récoltes, sorties, photos et réglages de la base dans un seul fichier. Avec un mot de passe, il est chiffré (AES-256) : sans lui, personne ne peut l'ouvrir, pas même nous."),
                    style = MaterialTheme.typography.bodyMedium,
                )
                VSpace(8.dp)
                PasswordField(password, { password = it }, "Mot de passe (facultatif)")
                if (password.isNotEmpty()) {
                    VSpace(6.dp)
                    PasswordField(password2, { password2 = it }, "Confirme le mot de passe", isError = !passwordsOk)
                }
                VSpace(10.dp)
                ChampiButton(
                    if (password.isEmpty()) "Sauvegarder (non chiffré)" else "Sauvegarder (chiffré)",
                    enabled = !busy && passwordsOk,
                    onClick = { exportLauncher.launch(BackupManager.suggestedName(password.isNotEmpty())) },
                )
            }

            SectionTitle("Restaurer")
            ChampiCard(Modifier.fillMaxWidth()) {
                Text(
                    champiText("Remplace TOUTES les données actuelles par celles d'une sauvegarde. L'appli redémarre aussitôt après."),
                    style = MaterialTheme.typography.bodyMedium,
                )
                VSpace(10.dp)
                ChampiOutlinedButton("Choisir une sauvegarde", enabled = !busy, onClick = { importLauncher.launch(arrayOf("*/*")) })
            }

            SectionTitle("Export et corbeille")
            ChampiCard(Modifier.fillMaxWidth()) {
                Text(champiText("Export CSV : toutes tes récoltes dans un tableau (date, espèce, poids, spot)."), style = MaterialTheme.typography.bodyMedium)
                VSpace(8.dp)
                ChampiOutlinedButton("Exporter en CSV", enabled = !busy, onClick = { csvLauncher.launch("champimap-recoltes.csv") })
                VSpace(12.dp)
                Text(champiText("Corbeille : éléments supprimés depuis moins de 30 jours."), style = MaterialTheme.typography.bodyMedium)
                VSpace(8.dp)
                ChampiOutlinedButton("Ouvrir la corbeille", onClick = { nav.navigate(Routes.CORBEILLE) })
            }
            VSpace(16.dp)
        }
    }

    // Confirmation de restauration (avec mot de passe si le fichier est chiffré).
    pendingImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Remplacer toutes tes données ?", textAlign = TextAlign.Center) },
            text = {
                Column {
                    Text(champiText("Tes spots, récoltes, sorties et photos actuels seront remplacés par la sauvegarde. Pense à sauvegarder avant si besoin."))
                    if (importNeedsPassword) {
                        VSpace(8.dp)
                        PasswordField(importPassword, { importPassword = it }, "Mot de passe de la sauvegarde")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !importNeedsPassword || importPassword.isNotEmpty(),
                    onClick = {
                        pendingImport = null
                        val pwd = if (importNeedsPassword) importPassword.toCharArray() else null
                        busy = true; message = null
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { runCatching { BackupManager.restore(context, uri, pwd, reporter) } }
                            busy = false
                            // Base fermée : on redémarre tout de suite (aucun écran ne doit la relire).
                            if (r.isSuccess || BackupManager.dbClosed) BackupManager.restartApp(context)
                            else {
                                ErrorLogger.log("Sauvegarde", "Restauration impossible", r.exceptionOrNull())
                                message = if (r.exceptionOrNull() is BackupCrypto.BadPasswordException) "Mot de passe incorrect (ou fichier abîmé)."
                                else "La restauration n'a pas fonctionné. Tes données n'ont pas été modifiées."
                            }
                        }
                    },
                ) { Text("Remplacer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("Annuler") } },
        )
    }
}

/** Champ mot de passe masqué. */
@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String, isError: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = isError,
        shape = RoundedCornerShape(14.dp),
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
}
