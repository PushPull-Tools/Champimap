package fr.pushpull.champimap.feature.sortie

import fr.pushpull.champimap.core.util.pluriel
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.pushpull.champimap.core.db.SpotMarker
import fr.pushpull.champimap.core.ui.BUTTON_HEIGHT
import fr.pushpull.champimap.core.ui.ChampiButton
import fr.pushpull.champimap.core.ui.theme.SafetyTheme
import fr.pushpull.champimap.feature.bonus.champiText
import kotlinx.coroutines.delay

/*
 * Morceaux de l'écran « Sortie en cours » : barre haute, pastilles, bandeaux, panneau bas.
 */

/** Distance sous laquelle on passe en « mode approche » vers un spot. */
const val APPROACH_DISTANCE_M = 15.0

/** Barre haute : « Sortie en cours · 1 h 12 », « 3 spots · 1,1 km parcourus », Terminer (deux appuis). */
@Composable
internal fun SortieTopBar(startedAt: Long, now: Long, spotCount: Int, distanceM: Double, onEnd: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    // Le second appui doit venir dans les 5 s, sinon le bouton revient à « Terminer ».
    LaunchedEffect(confirm) {
        if (confirm) { delay(5_000); confirm = false }
    }
    Row(
        Modifier.fillMaxWidth().panelBackground().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                champiText("Sortie en cours · " + Geo.formatDuration(now - startedAt)),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                champiText("${pluriel(spotCount, "spot")} · ${Geo.formatDistance(distanceM)} parcourus"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(
            onClick = { if (confirm) { confirm = false; onEnd() } else confirm = true },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (confirm) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                contentColor = if (confirm) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
            ),
        ) { Text(champiText(if (confirm) "Confirmer ?" else "Terminer")) }
    }
}

/** Pastilles : « Avec Julie · synchro OK », « GPS ±4 m », « Batterie 64 % ». */
@Composable
internal fun SortiePills(
    companions: List<String>,
    shared: Boolean,
    syncStatus: String?,
    fix: GpsFix?,
    paused: Boolean,
    battery: Int?,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (shared) {
            val who = if (companions.isEmpty()) "Sortie partagée" else "Avec " + companions.joinToString(", ")
            MapPill(who + (syncStatus?.let { " · $it" } ?: ""))
        }
        MapPill(
            when {
                paused -> "GPS en pause (immobile)"
                fix == null -> "GPS : recherche…"
                else -> "GPS ±${fix.accuracyM.toInt()} m"
            },
            warn = fix != null && !paused && fix.accuracyM > 25f,
        )
        if (battery != null) MapPill("Batterie $battery %", warn = battery < 20)
    }
}

/** Bandeaux : période de chasse, nuit qui tombe (≤ 45 min), batterie faible. */
@Composable
internal fun SortieBanners(
    hunting: Boolean,
    onHideHunting: () -> Unit,
    nightMin: Long?,
    distanceToCar: Double?,
    onHideNight: () -> Unit,
    economy: Boolean,
    battery: Int?,
    onHideBattery: () -> Unit,
) {
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (nightMin != null && nightMin <= 45) {
            val car = distanceToCar?.let { " La voiture est à ${Geo.formatDistance(it)}." } ?: ""
            SafetyBanner(
                title = if (nightMin <= 0) "Il fait nuit en forêt" else "Nuit en forêt dans $nightMin min",
                text = "Prends le chemin du retour.$car",
                onClose = onHideNight,
            )
        }
        if (economy) {
            SafetyBanner(
                title = "Batterie faible" + (battery?.let { " : $it %" } ?: ""),
                text = "Mode économie : un point GPS toutes les 30 s.",
                onClose = onHideBattery,
            )
        }
        if (hunting) {
            SafetyBanner(
                title = "Période de chasse",
                text = "Reste visible !",
                strong = false,
                onClose = onHideHunting,
            )
        }
    }
}

/**
 * Panneau bas : flèche + distance vers la voiture (ou vers le spot visé), minutes avant la nuit,
 * gros bouton orange « Marquer un spot », « Spot existant », « Je suis perdu ».
 * Mode approche à moins de 15 m d'un spot visé.
 */
@Composable
internal fun SortieBottomPanel(
    modifier: Modifier,
    fix: GpsFix?,
    start: Pair<Double, Double>?,
    departIsCar: Boolean,
    target: SpotMarker?,
    compassActive: Boolean,
    nightMin: Long?,
    follow: Boolean,
    onRecenter: () -> Unit,
    onMark: () -> Unit,
    onExisting: () -> Unit,
    onLost: () -> Unit,
    onCancelTarget: () -> Unit,
    onArrived: (SpotMarker) -> Unit,
) {
    // Cible de la flèche : le spot visé, sinon la voiture / le départ.
    val goal: Pair<Double, Double>? = target?.let { it.lat to it.lon } ?: start
    val heading by rememberHeading(active = compassActive && goal != null && fix != null, lat = fix?.lat, lon = fix?.lon)
    val distance = if (fix != null && goal != null) Geo.distanceM(fix.lat, fix.lon, goal.first, goal.second) else null
    val bearing = if (fix != null && goal != null) Geo.bearingDeg(fix.lat, fix.lon, goal.first, goal.second) else null
    val approach = target != null && distance != null && distance <= APPROACH_DISTANCE_M

    Column(modifier, horizontalAlignment = Alignment.End) {
        if (!follow) {
            IconButton(
                onClick = onRecenter,
                modifier = Modifier.size(52.dp),
                colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface),
            ) { Icon(Icons.Filled.MyLocation, contentDescription = "Recentrer sur ma position") }
            Spacer(Modifier.size(8.dp))
        }
        Column(
            Modifier.fillMaxWidth().panelBackground().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Flèche : cap à suivre moins cap du téléphone (sans boussole : relative au nord).
                val rotation = if (bearing != null) (bearing - (heading ?: 0f).toDouble()).toFloat() else 0f
                if (bearing != null) DirectionArrow(rotation, size = if (approach) 72.dp else 56.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    val label = when {
                        target != null && approach -> "Tu y es presque : ${distance!!.toInt()} m"
                        target != null -> "Vers ${target.name}"
                        goal == null -> "Départ pas encore enregistré"
                        departIsCar -> "Vers la voiture"
                        else -> "Vers le départ"
                    }
                    Text(champiText(label), style = MaterialTheme.typography.titleMedium, maxLines = 1, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        champiText(
                            (distance?.let { Geo.formatDistance(it) } ?: "—") +
                                (if (heading == null && bearing != null) " · nord en haut (pas de boussole)" else "") +
                                " · " + nightText(nightMin),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (target != null) {
                    OutlinedButton(onClick = onCancelTarget, modifier = Modifier.heightIn(min = 44.dp)) { Text(champiText("Arrêter")) }
                }
            }

            if (approach && target != null) {
                // Mode approche : guidage fin + confirmation d'arrivée (la photo se prend sur la fiche du spot).
                ChampiButton("Je suis au spot", onClick = { onArrived(target) }, color = MaterialTheme.colorScheme.primary)
            }

            ChampiButton("Marquer un spot", onClick = onMark, color = MaterialTheme.colorScheme.secondary)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onExisting,
                    modifier = Modifier.weight(1f).heightIn(min = BUTTON_HEIGHT),
                    shape = RoundedCornerShape(16.dp),
                ) { Text(champiText("Spot existant"), textAlign = TextAlign.Center) }
                // Bouton de sécurité : jamais en thème party.
                Box(Modifier.weight(1f)) {
                    SafetyTheme {
                        Button(
                            onClick = onLost,
                            modifier = Modifier.fillMaxWidth().heightIn(min = BUTTON_HEIGHT),
                            shape = RoundedCornerShape(16.dp),
                            // Rouge pâle en clair, rouge foncé en sombre (paire errorContainer du thème).
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
                        ) { Text("Je suis perdu", fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}
