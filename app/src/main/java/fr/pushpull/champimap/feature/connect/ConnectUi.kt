package fr.pushpull.champimap.feature.connect

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import fr.pushpull.champimap.core.ErrorLogger
import fr.pushpull.champimap.feature.bonus.champiText
import kotlinx.coroutines.launch

/*
 * Briques d'interface partagées par les écrans Connect (premier lancement et réglages).
 */

/**
 * Couleur de texte par défaut lisible sur le fond de l'écran, dans tous les thèmes.
 * Sans cela, un Text posé directement sur le fond (hors carte) est noir, donc illisible en sombre.
 * Dans une ChampiCard (Surface), Material repasse automatiquement en onSurface.
 */
@Composable
fun ConnectContentColor(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground, content = content)
}

/** Exécute [action] à chaque retour de l'écran au premier plan (ON_RESUME). */
@Composable
fun OnResumeEffect(action: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    // Toujours la dernière version de l'action (elle lit des états de l'écran).
    val currentAction by rememberUpdatedState(action)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) currentAction()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

/** Rafraîchit l'état de Connect à chaque retour au premier plan (installation, synchro finie…). */
@Composable
fun ConnectResumeEffect() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) ConnectClient.onResume(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

/**
 * Renvoie une fonction qui lance la synchro dans Champimap Connect et traite son résultat.
 * @param refreshMaps true = « Actualiser les cartes » (tout retélécharger) ; false = seulement ce qui manque.
 * @param iaOnly true = seulement le modèle d'identification (version choisie dans le menu, sinon la plus récente).
 * @param iaListOnly true = seulement la liste des versions publiées du modèle (quelques Ko).
 */
@Composable
fun rememberConnectSync(refreshMaps: Boolean = false, iaOnly: Boolean = false, iaListOnly: Boolean = false): () -> Unit {
    val context = LocalContext.current
    val start = rememberConnectLaunch()
    return { start { ConnectClient.buildSyncIntent(context, refreshMaps = refreshMaps, iaOnly = iaOnly, iaListOnly = iaListOnly) } }
}

/** Renvoie une fonction « Réessayer » : relance dans Connect seulement les tâches en erreur données. */
@Composable
fun rememberConnectRetry(): (List<SyncItem>) -> Unit {
    val context = LocalContext.current
    val start = rememberConnectLaunch()
    return { failed -> start { ConnectClient.buildRetryIntent(context, failed) } }
}

/**
 * Lanceur commun : vérifie que Connect est installé, construit l'Intent (fonction [buildIntent] donnée
 * à l'appel), démarre l'activité Connect et traite son résultat.
 */
@Composable
private fun rememberConnectLaunch(): (suspend () -> Intent) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        ConnectClient.handleResult(context, result.resultCode, result.data)
    }
    return { buildIntent ->
        scope.launch {
            if (!ConnectClient.isInstalled(context)) {
                ConnectClient.markLaunchFailed("Le module Champimap Connect n'est pas installé.")
                return@launch
            }
            ConnectClient.ensureListening(context)
            val intent = buildIntent()
            ConnectClient.markStarted()
            try {
                launcher.launch(intent)
            } catch (e: ActivityNotFoundException) {
                ConnectClient.markLaunchFailed("Champimap Connect ne répond pas. Réinstalle-le.")
            } catch (e: SecurityException) {
                // Signature différente : la permission « CONNECT » est refusée par Android.
                ErrorLogger.log("Connect", "Lancement refusé (signature différente ?)", e)
                ConnectClient.markLaunchFailed(
                    "Ce module ne vient pas de la même source que Champimap. Désinstalle-le puis réinstalle-le depuis Champimap.",
                )
            }
        }
    }
}

/** Pastille ronde avec un chiffre parfaitement centré. */
@Composable
fun NumberBadge(n: Int) {
    Box(
        Modifier.size(28.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            n.toString(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/** Une étape numérotée (pastille + texte). */
@Composable
fun NumberedStep(n: Int, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        NumberBadge(n)
        Spacer(Modifier.width(12.dp))
        Text(
            champiText(text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(top = 3.dp),
        )
    }
}

/**
 * Icône d'état : ✓ (fait), cercle coché discret (déjà à jour), indicateur (en cours), horloge (en attente),
 * erreur, interdit (annulé / pas fait).
 */
@Composable
fun StatusIcon(status: ItemStatus) {
    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
        when (status) {
            ItemStatus.DONE -> Icon(Icons.Filled.Check, contentDescription = "Terminé", tint = MaterialTheme.colorScheme.primary)
            ItemStatus.SKIPPED -> Icon(Icons.Outlined.CheckCircle, contentDescription = "Déjà à jour", tint = MaterialTheme.colorScheme.primary)
            ItemStatus.CANCELLED -> Icon(Icons.Filled.Block, contentDescription = "Annulé", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            ItemStatus.RUNNING -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            ItemStatus.ERROR -> Icon(Icons.Filled.ErrorOutline, contentDescription = "Erreur", tint = MaterialTheme.colorScheme.error)
            ItemStatus.WAITING -> Icon(Icons.Filled.Schedule, contentDescription = "En attente", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Les trois familles affichées en premier (cahier des charges), puis la météo et l'antipoison. */
private val KIND_LABELS = listOf(
    ItemKind.CONTOUR to "Contours des départements",
    ItemKind.URGENCES to "Urgences les plus proches",
    ItemKind.CARTE to "Cartes",
    ItemKind.METEO to "Météo de tes sorties",
    ItemKind.ANTIPOISON to "Centres antipoison",
    ItemKind.IA to "Modèle d'identification",
)

/** État combiné d'une famille d'éléments. */
private fun groupStatus(items: List<SyncItem>, phase: SyncPhase): ItemStatus = when {
    items.isEmpty() -> if (phase == SyncPhase.DONE) ItemStatus.DONE else ItemStatus.WAITING
    items.all { it.status == ItemStatus.DONE || it.status == ItemStatus.SKIPPED } -> ItemStatus.DONE
    items.any { it.status == ItemStatus.RUNNING } -> ItemStatus.RUNNING
    items.any { it.status == ItemStatus.ERROR } && items.none { it.status == ItemStatus.WAITING } -> ItemStatus.ERROR
    items.any { it.status == ItemStatus.DONE } -> ItemStatus.RUNNING
    else -> ItemStatus.WAITING
}

/**
 * Avancement de la synchro (étape « Préparation de tes cartes » et section « Avancement » de l'écran Connect).
 * Connect 4 et plus : liste des vraies tâches, regroupées (TaskProgressBlock, SyncTasksUi.kt).
 * Ancien Connect, ou rien reçu encore : ancien affichage par famille (LegacyProgressBlock).
 */
@Composable
fun SyncProgressBlock(ui: SyncUi) {
    val snap = ui.snapshot
    if (snap != null && snap.hasTasks) TaskProgressBlock(ui, snap) else LegacyProgressBlock(ui)
}

/**
 * Ancien affichage (Connect 3 et moins) : % global, barre, départements, liste par famille.
 * Affiche l'état « en cours » tant que Connect n'a rien renvoyé.
 */
@Composable
private fun LegacyProgressBlock(ui: SyncUi) {
    val snap = ui.snapshot
    val percent = when {
        ui.phase == SyncPhase.DONE -> 100
        else -> snap?.percent ?: 0
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Pourcentage global.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$percent %", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(12.dp))
            Text(
                champiText(
                    ui.message ?: when (ui.phase) {
                        SyncPhase.IDLE -> "En attente"
                        SyncPhase.RUNNING -> "Téléchargement en cours dans Champimap Connect…"
                        SyncPhase.DONE -> "Tout est prêt !"
                        SyncPhase.FAILED -> "La synchro a échoué."
                        SyncPhase.CANCELLED -> "Synchro interrompue."
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
        if (ui.phase == SyncPhase.RUNNING && snap == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(8.dp))
        } else {
            LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth().height(8.dp))
        }

        // Avancement par département.
        snap?.departements?.takeIf { it.isNotEmpty() }?.let { depts ->
            Spacer(Modifier.height(4.dp))
            depts.forEach { d ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusIcon(if (ui.phase == SyncPhase.DONE && d.status != ItemStatus.ERROR) ItemStatus.DONE else d.status)
                    Spacer(Modifier.width(8.dp))
                    Text("${d.code} · ${d.name}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    Text("${d.percent} %", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                }
                LinearProgressIndicator(
                    progress = { d.percent / 100f },
                    modifier = Modifier.fillMaxWidth().padding(start = 30.dp).height(4.dp),
                )
            }
        }

        // Liste dynamique par famille.
        Spacer(Modifier.height(4.dp))
        val items = snap?.items ?: emptyList()
        KIND_LABELS.forEach { (kind, label) ->
            val ofKind = items.filter { it.kind == kind }
            // Météo et antipoison n'apparaissent que si Connect les traite.
            if (ofKind.isEmpty() && (kind == ItemKind.METEO || kind == ItemKind.ANTIPOISON || kind == ItemKind.IA)) return@forEach
            val done = ofKind.count { it.status == ItemStatus.DONE || it.status == ItemStatus.SKIPPED }
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusIcon(groupStatus(ofKind, ui.phase))
                Spacer(Modifier.width(8.dp))
                Text(champiText(label), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                if (ofKind.isNotEmpty()) Text("$done/${ofKind.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            // Détail des éléments en cours ou en erreur.
            ofKind.filter { it.status == ItemStatus.RUNNING || it.status == ItemStatus.ERROR }.forEach { item ->
                Text(
                    champiText(
                        buildString {
                            append(item.label)
                            if (item.status == ItemStatus.RUNNING && item.percent > 0) append(" · ${item.percent} %")
                            item.detail?.let { append(" · $it") }
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.status == ItemStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 30.dp),
                )
            }
        }
        snap?.errors?.takeIf { it.isNotEmpty() && ui.phase != SyncPhase.RUNNING }?.let { errs ->
            Text(
                champiText("À refaire plus tard : " + errs.take(3).joinToString(" · ")),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Rappel affiché sous les boutons de synchro (l'appli n'a pas accès à l'état du réseau : texte seul). */
const val WIFI_ADVICE = "Conseillé : en Wi-Fi (cartes et modèle d'identification volumineux)"

/** « 26/10/2026 à 14 h 05 », ou « jamais ». */
fun formatSyncDate(ms: Long): String {
    if (ms <= 0) return "jamais"
    val dt = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault())
    return String.format(java.util.Locale.FRANCE, "%02d/%02d/%04d à %d h %02d", dt.dayOfMonth, dt.monthValue, dt.year, dt.hour, dt.minute)
}

/** Nombre de cartes présentes dans filesDir/maps/. */
fun mapCount(context: Context): Int =
    ConnectClient.mapsDir(context).listFiles { f -> f.isFile && f.name.endsWith(".pmtiles") && f.length() > 127 }?.size ?: 0
