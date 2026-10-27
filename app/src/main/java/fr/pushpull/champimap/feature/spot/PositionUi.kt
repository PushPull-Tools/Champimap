package fr.pushpull.champimap.feature.spot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.db.PositionSource
import fr.pushpull.champimap.core.db.Spot
import fr.pushpull.champimap.core.map.ChampiMapView
import fr.pushpull.champimap.core.ui.ChampiButton
import fr.pushpull.champimap.core.ui.ChampiCard
import fr.pushpull.champimap.core.ui.ChampiChip
import fr.pushpull.champimap.core.ui.ChampiOutlinedButton
import fr.pushpull.champimap.core.ui.ChampiScreen
import fr.pushpull.champimap.core.ui.ConfirmDialog
import fr.pushpull.champimap.feature.bonus.champiText
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng

/*
 * Position d'un spot (module spot) :
 *  - « Je suis au pied » : mesure précise de 30 s avec jauge en direct, validation manuelle ;
 *  - « Ajuster la position » : carte avec épingle fixe au centre (on déplace la carte dessous),
 *    distance en direct, confirmation au-delà de 50 m ;
 *  - « Historique des positions (N) » avec « Revenir à cette position ».
 */

/** Durée de la mesure « Je suis au pied ». */
private const val AU_PIED_MS = 30_000L

/** Distance au-delà de laquelle un déplacement manuel demande confirmation. */
private const val CONFIRM_MOVE_M = 50f

/** Libellé de l'origine d'une position. */
fun sourceLabel(s: PositionSource): String = when (s) {
    PositionSource.GPS_PRECIS -> "GPS précis"
    PositionSource.AU_PIED -> "Au pied"
    PositionSource.MANUEL -> "Placé à la main"
    PositionSource.CARTE -> "Ajusté sur la carte"
    PositionSource.IMPORT -> "Importé"
}

/**
 * Fenêtre « Je suis au pied » : mesure en cours (jauge ±m), puis résultat à valider.
 * Reste immobile au pied du spot pendant la mesure.
 */
@Composable
fun AuPiedDialog(onDismiss: () -> Unit, onResult: (PreciseFix) -> Unit) {
    val context = LocalContext.current
    var progress by remember { mutableStateOf(PreciseProgress(0f, 0, null, null)) }
    var result by remember { mutableStateOf<PreciseFix?>(null) }
    var done by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val gpsOn = remember { isGpsEnabled(context) }

    // Nouvelle mesure à chaque tentative ; annulée si la fenêtre se ferme.
    LaunchedEffect(attempt) {
        if (!gpsOn) { done = true; return@LaunchedEffect }
        done = false
        result = null
        result = measurePrecisePosition(context, AU_PIED_MS) { progress = it }
        done = true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(champiText(if (done) "Mesure terminée" else "Reste immobile au pied du spot")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    !gpsOn -> Text(champiText("Le GPS du téléphone est coupé. Active la localisation puis réessaie."))
                    !done -> {
                        LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                        val first = progress.lastAccuracyM?.let { "±${it.toInt()} m" } ?: "…"
                        val est = progress.estimateAccuracyM?.let { "±${it.toInt()} m" } ?: "…"
                        Text(
                            "$first → $est",
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(champiText("${progress.samples} mesures · plus c'est long, plus c'est précis"), style = MaterialTheme.typography.bodySmall)
                    }
                    result == null -> Text(champiText("Aucun signal GPS reçu. Mets-toi à découvert et réessaie."))
                    else -> {
                        val r = result!!
                        Text(
                            "±${r.accuracyM.toInt()} m",
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(champiText("Moyenne de ${r.samples} mesures. Elle sera combinée aux visites précédentes."))
                    }
                }
            }
        },
        confirmButton = {
            val r = result
            if (done && r != null) TextButton(onClick = { onResult(r) }) { Text(champiText("Valider")) }
            else if (done && gpsOn) TextButton(onClick = { attempt++ }) { Text(champiText("Réessayer")) }
            else if (!done) TextButton(onClick = { done = true }, enabled = false) { Text(champiText("Mesure…")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/**
 * Écran « Ajuster la position » : la carte bouge sous une épingle fixe au centre.
 * `onConfirm(lat, lon)` reçoit la nouvelle position (après confirmation si > 50 m).
 */
@Composable
fun AdjustPositionScreen(spot: Spot, onCancel: () -> Unit, onConfirm: (Double, Double) -> Unit) {
    var target by remember { mutableStateOf(LatLng(spot.lat, spot.lon)) }
    var mapReady by remember { mutableStateOf(false) }
    var askConfirm by remember { mutableStateOf(false) }
    val dist = distanceM(spot.lat, spot.lon, target.latitude, target.longitude)

    ChampiScreen(title = "Ajuster la position", onBack = onCancel) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ChampiMapView(Modifier.fillMaxSize()) { map ->
                mapReady = true
                map.cameraPosition = CameraPosition.Builder().target(LatLng(spot.lat, spot.lon)).zoom(18.0).build()
                // Position sous l'épingle = centre de la carte, mise à jour pendant le déplacement.
                map.addOnCameraMoveListener { map.cameraPosition.target?.let { target = it } }
                map.addOnCameraIdleListener { map.cameraPosition.target?.let { target = it } }
            }
            // Épingle fixe : sa pointe est au centre exact de la carte.
            Icon(
                Icons.Filled.Place,
                contentDescription = "Nouvelle position",
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.align(Alignment.Center).size(48.dp).offset(y = (-24).dp),
            )
            Box(Modifier.align(Alignment.TopCenter).padding(12.dp)) {
                ChampiChip(
                    if (mapReady) "Déplacement : ${formatDistance(dist)}" else "Carte indisponible",
                    background = MaterialTheme.colorScheme.surface,
                    textColor = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(champiText("Fais glisser la carte pour placer l'épingle sur le spot."), style = MaterialTheme.typography.bodyMedium)
            ChampiButton(
                "Valider cette position",
                enabled = mapReady && dist >= 1f,
                onClick = { if (dist > CONFIRM_MOVE_M) askConfirm = true else onConfirm(target.latitude, target.longitude) },
            )
            ChampiOutlinedButton("Annuler", onClick = onCancel)
        }
    }
    if (askConfirm) {
        ConfirmDialog(
            title = "Déplacer le spot de ${formatDistance(dist)} ?",
            text = "C'est loin de la position actuelle. L'ancienne position reste dans l'historique.",
            confirmLabel = "Déplacer",
            onConfirm = { askConfirm = false; onConfirm(target.latitude, target.longitude) },
            onDismiss = { askConfirm = false },
        )
    }
}

/** Carte « Historique des positions (N) », repliable, avec retour arrière. */
@Composable
fun PositionsCard(spot: Spot, onReverted: () -> Unit) {
    val scope = rememberCoroutineScope()
    val positions by remember(spot.id) { AppGraph.db.spotDao().positions(spot.id) }.collectAsState(initial = emptyList())
    var open by remember { mutableStateOf(false) }

    ChampiCard(Modifier.fillMaxWidth()) {
        TextButton(onClick = { open = !open }, modifier = Modifier.fillMaxWidth()) {
            Text(
                champiText("Historique des positions (${positions.size})"),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
        }
        if (!open) return@ChampiCard
        if (positions.isEmpty()) {
            Text(champiText("Seulement la position d'origine pour l'instant."), style = MaterialTheme.typography.bodySmall)
        }
        positions.forEach { p ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        champiText(sourceLabel(p.source)),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    if (p.active) ChampiChip("Actuelle", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                }
                val acc = p.accuracyM?.let { " · ±${it.toInt()} m" } ?: ""
                Text(formatDateTime(p.time) + acc, style = MaterialTheme.typography.bodySmall)
                if (!spot.coordsHidden) Text(formatCoords(p.lat, p.lon), style = MaterialTheme.typography.bodySmall)
                if (!p.active) {
                    TextButton(onClick = { scope.launch { SpotRepository.revertTo(spot.id, p.id); onReverted() } }) {
                        Text(champiText("Revenir à cette position"))
                    }
                }
            }
        }
    }
}
