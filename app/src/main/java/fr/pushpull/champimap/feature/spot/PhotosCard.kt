package fr.pushpull.champimap.feature.spot

import fr.pushpull.champimap.core.util.pluriel
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.db.Photo
import fr.pushpull.champimap.core.ui.ChampiCard
import fr.pushpull.champimap.core.ui.ConfirmDialog
import fr.pushpull.champimap.core.ui.SectionTitle
import fr.pushpull.champimap.feature.bonus.champiText
import kotlinx.coroutines.launch
import java.io.File

/*
 * Photos d'un spot (fiche spot) : grille de miniatures (spot + espèces récoltées).
 * Appui = agrandir ; appui long = sélection (puis appuis pour en ajouter) ;
 * « Supprimer » → boîte de confirmation → corbeille 30 jours.
 */

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun PhotosCard(spotId: String, onInfo: (String) -> Unit) {
    val photos by remember(spotId) { AppGraph.db.photoDao().ofSpotAll(spotId) }.collectAsState(initial = emptyList())
    var selected by remember { mutableStateOf(setOf<String>()) }
    var viewing by remember { mutableStateOf<Photo?>(null) }
    var askDelete by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()

    ChampiCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(if (selecting) pluriel(selected.size, "sélectionnée") else "Photos (${photos.size})", Modifier.weight(1f))
            if (selecting) {
                TextButton(onClick = { selected = emptySet() }) { Text("Annuler") }
                TextButton(onClick = { askDelete = true }) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
            }
        }
        if (photos.isEmpty()) {
            Text(champiText("Pas encore de photo."), style = MaterialTheme.typography.bodySmall)
            return@ChampiCard
        }
        if (!selecting) {
            Text(champiText("Appui long pour sélectionner et supprimer."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(
            Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            photos.forEach { p ->
                val isSel = p.id in selected
                Box(
                    Modifier.size(96.dp).clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .then(if (isSel) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier)
                        .combinedClickable(
                            onClick = {
                                if (selecting) selected = if (isSel) selected - p.id else selected + p.id
                                else viewing = p
                            },
                            onLongClick = { selected = selected + p.id },
                        ),
                ) {
                    AsyncImage(model = File(p.path), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    if (isSel) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = "Sélectionnée",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).background(MaterialTheme.colorScheme.surface, CircleShape),
                        )
                    }
                }
            }
        }
    }

    // Agrandissement d'une photo.
    viewing?.let { p ->
        AlertDialog(
            onDismissRequest = { viewing = null },
            text = {
                AsyncImage(
                    model = File(p.path),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = { TextButton(onClick = { viewing = null }) { Text("Fermer") } },
            dismissButton = {
                TextButton(onClick = { selected = setOf(p.id); viewing = null; askDelete = true }) {
                    Text("Supprimer", color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }

    // Suppression : toujours confirmée (règle du cahier des charges pour les photos).
    if (askDelete) {
        val n = selected.size
        ConfirmDialog(
            title = if (n > 1) "Supprimer ces $n photos ?" else "Supprimer cette photo ?",
            text = "Elles iront dans la corbeille pendant 30 jours.",
            onConfirm = {
                val ids = selected.toList()
                askDelete = false
                selected = emptySet()
                AppGraph.appScope.launch {
                    // Les photos d'une récolte verrouillée (cadenas) ne sont pas supprimées.
                    val done = SpotRepository.trashPhotos(ids)
                    val skipped = n - done
                    onInfo(
                        when {
                            done == 0 -> "Récolte verrouillée : ouvre le cadenas pour supprimer ces photos."
                            skipped > 0 -> "${pluriel(done, "photo")} à la corbeille, ${pluriel(skipped, "protégée")} par le cadenas."
                            done > 1 -> "$done photos mises à la corbeille."
                            else -> "Photo mise à la corbeille."
                        }
                    )
                }
            },
            onDismiss = { askDelete = false },
        )
    }
}
