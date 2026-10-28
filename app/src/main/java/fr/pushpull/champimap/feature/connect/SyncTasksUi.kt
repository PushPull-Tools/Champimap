package fr.pushpull.champimap.feature.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.pushpull.champimap.feature.bonus.champiText
import java.util.Locale

/*
 * Avancement détaillé de la synchro (Champimap Connect 4 et plus, snapshot.format = 2) :
 *  - ligne de résumé « 3 tâches sur 9 · 42 % · Carte · Haute-Vienne » + barre globale ;
 *  - les vraies tâches de Connect, dans l'ordre réel d'exécution, regroupées :
 *    un bloc par département (« 87 · Haute-Vienne »), puis le bloc « Général » ;
 *  - tâche en cours surlignée (indicateur + %), tâches faites cochées, « Déjà à jour », « Annulé » ;
 *  - erreurs en rouge avec leur message et « Réessayer » (relance seulement cette tâche).
 * Utilisé par SyncProgressBlock (ConnectUi.kt) : étape « Préparation de tes cartes » et écran Connect.
 */

/** Liste des tâches regroupées, avec résumé en tête. */
@Composable
internal fun TaskProgressBlock(ui: SyncUi, snap: SyncSnapshot) {
    val retry = rememberConnectRetry()
    // « Réessayer » seulement hors synchro : Connect ne mène qu'une synchro à la fois.
    val canRetry = ui.phase != SyncPhase.RUNNING
    val percent = snap.percent.coerceIn(0, 100)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Résumé : tâches traitées, pourcentage pondéré, tâche en cours (sinon état final).
        Text(
            champiText(snap.summary(percent, snap.step)),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth().height(8.dp))
        // Message de l'appli (import du modèle, « Tout est prêt ! »…), s'il apporte quelque chose.
        ui.message?.takeIf { it.isNotBlank() && it != snap.step }?.let {
            Text(champiText(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Un bloc par groupe, dans l'ordre réel.
        taskGroups(snap.items).forEach { (group, tasks) ->
            Spacer(Modifier.height(4.dp))
            GroupHeader(group, tasks)
            tasks.forEach { item -> TaskRow(item, canRetry = canRetry, onRetry = { retry(listOf(item)) }) }
        }

        // Erreurs hors tâches (demande refusée, zone météo en échec, arrêt complet…), une fois la synchro finie.
        if (ui.phase != SyncPhase.RUNNING) {
            val others = snap.errors.filter { e ->
                e != "Synchronisation annulée" && snap.items.none { e.startsWith(it.label + " :") }
            }
            if (others.isNotEmpty()) {
                Text(
                    champiText(others.take(3).joinToString(" · ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * Groupes dans l'ordre réel : chaque département dans l'ordre de sa première tâche,
 * puis « Général » (tâches sans département).
 */
private fun taskGroups(items: List<SyncItem>): List<Pair<String, List<SyncItem>>> {
    val depts = items.filter { it.departement != null }.groupBy { it.group }.toList()
    val general = items.filter { it.departement == null }
    return if (general.isEmpty()) depts else depts + (ConnectProtocol.GROUP_GENERAL to general)
}

/** Titre d'un bloc : « 87 · Haute-Vienne » et « 2/3 » (tâches traitées / total). */
@Composable
private fun GroupHeader(group: String, tasks: List<SyncItem>) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            champiText(group),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text(
            "${tasks.count { it.isProcessed }}/${tasks.size}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Ligne d'une tâche : icône d'état, libellé, état à droite (%, taille, « Déjà à jour »…), détail,
 * barre fine si elle est en cours (ligne surlignée), message rouge + « Réessayer » si elle a échoué.
 */
@Composable
private fun TaskRow(item: SyncItem, canRetry: Boolean, onRetry: () -> Unit) {
    val current = item.status == ItemStatus.RUNNING
    val error = item.status == ItemStatus.ERROR
    val highlight = if (current) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent
    Column(
        Modifier.fillMaxWidth()
            .background(highlight, RoundedCornerShape(10.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusIcon(item.status)
            Spacer(Modifier.width(8.dp))
            Text(
                champiText(item.title),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            stateText(item)?.let {
                Text(
                    champiText(it),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        error -> MaterialTheme.colorScheme.error
                        current -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        detailText(item)?.let {
            Text(
                champiText(it),
                style = MaterialTheme.typography.bodySmall,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 30.dp),
            )
        }
        if (current) {
            LinearProgressIndicator(
                progress = { item.percent.coerceIn(0, 100) / 100f },
                modifier = Modifier.fillMaxWidth().padding(start = 30.dp, top = 4.dp).height(4.dp),
            )
        }
        if (error && canRetry) {
            TextButton(onClick = onRetry, modifier = Modifier.padding(start = 22.dp)) {
                Text(champiText("Réessayer"), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Texte d'état à droite d'une tâche (null = rien). */
private fun stateText(item: SyncItem): String? = when (item.status) {
    ItemStatus.RUNNING -> "${item.percent.coerceIn(0, 100)} %"
    ItemStatus.DONE -> item.sizeBytes?.let { sizeText(it) }
    ItemStatus.SKIPPED -> "Déjà à jour"
    ItemStatus.CANCELLED -> "Annulé"
    ItemStatus.ERROR -> "Erreur"
    ItemStatus.WAITING -> null
}

/** Détail sous le libellé (sans répéter « Déjà à jour » ni « Annulé », déjà affichés à droite). */
private fun detailText(item: SyncItem): String? {
    val d = item.detail?.trim().orEmpty()
    return when (item.status) {
        ItemStatus.WAITING -> null
        ItemStatus.SKIPPED -> d.removePrefix("Déjà à jour").removePrefix(" · ").trim().ifBlank { null }
        ItemStatus.CANCELLED -> d.takeIf { it.isNotBlank() && it != "Annulé" }
        else -> d.ifBlank { null }
    }
}

/** Taille lisible : « 35,2 Mo » ou « 830 Ko ». */
private fun sizeText(bytes: Long): String =
    if (bytes >= 1024 * 1024) String.format(Locale.FRANCE, "%.1f Mo", bytes / 1048576.0)
    else String.format(Locale.FRANCE, "%d Ko", (bytes + 1023) / 1024)
