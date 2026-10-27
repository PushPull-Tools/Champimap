package fr.pushpull.champimap.feature.sortie

import fr.pushpull.champimap.core.util.pluriel
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.db.SpotMarker
import fr.pushpull.champimap.core.map.ChampiMapView
import fr.pushpull.champimap.core.ui.ChampiScreen
import fr.pushpull.champimap.feature.bonus.champiText
import fr.pushpull.champimap.nav.Routes
import org.maplibre.android.geometry.LatLng

/*
 * Carte globale (onglet Carte) : tous les spots, colorés selon l'espèce de leur dernière récolte.
 * Cadrage automatique sur l'ensemble des spots ; sans spot, sur les départements cochés.
 * Légende des couleurs d'un appui. Appui sur une épingle → fiche du spot.
 */
@Composable
fun CarteScreen(nav: NavController) {
    val context = LocalContext.current
    val spots by remember { AppGraph.db.sortieDao().spotMarkers() }.collectAsState(initial = null)
    val settings by remember { AppGraph.settings.flow }.collectAsState(initial = null)
    val current by remember { AppGraph.db.sortieDao().current() }.collectAsState(initial = null)
    var layers by remember { mutableStateOf<ChampiMapLayers?>(null) }
    var showLegend by remember { mutableStateOf(false) }
    var framed by remember { mutableStateOf(false) }

    SortieIntentHandler(nav)

    /** Cadre sur les spots, sinon sur les départements cochés (emprise des cartes installées). */
    fun frame(l: ChampiMapLayers, list: List<SpotMarker>) {
        if (list.isNotEmpty()) l.fitTo(list.map { LatLng(it.lat, it.lon) })
        else OfflineMapStyle.boundsOf(context, settings?.departements.orEmpty())?.let { l.fitBounds(it) }
    }

    // Épingles à jour + premier cadrage dès que les données et la carte sont prêtes.
    LaunchedEffect(layers, spots) {
        val l = layers ?: return@LaunchedEffect
        val list = spots ?: return@LaunchedEffect
        // TODO(bonus) : pictos de l'easter egg (markerStyle MUSHROOMS / TREES) — voir MapIcons.
        l.setSpots(list)
        if (!framed && settings != null) { frame(l, list); framed = true }
    }
    LaunchedEffect(layers, settings?.departements) {
        val l = layers ?: return@LaunchedEffect
        val list = spots ?: return@LaunchedEffect
        if (!framed && settings != null) { frame(l, list); framed = true }
    }

    ChampiScreen {
        Box(Modifier.fillMaxSize()) {
            ChampiMapView(Modifier.fillMaxSize(), onMapReady = { map ->
                val l = ChampiMapLayers(context, map)
                l.onSpotClick { nav.navigate(Routes.spot(it)) }
                layers = l
            })

            // Barre haute : titre, sortie en cours, légende, recadrage.
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).panelBackground().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(champiText("Carte"), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                    val n = spots?.size ?: 0
                    Text(
                        champiText(
                            (if (n == 0) "Aucun spot pour l'instant" else pluriel(n, "spot")) +
                                if (OfflineMapStyle.mapFiles(context).isEmpty()) " · sans fond de carte" else "",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                MapIconButton(onClick = { showLegend = !showLegend }) {
                    Icon(Icons.Filled.Palette, contentDescription = "Légende des couleurs")
                }
                Spacer(Modifier.width(8.dp))
                MapIconButton(onClick = { layers?.let { frame(it, spots.orEmpty()) } }) {
                    Icon(Icons.Filled.CenterFocusStrong, contentDescription = "Voir tous les spots")
                }
            }

            if (showLegend) {
                Legend(
                    spots = spots.orEmpty(),
                    onClose = { showLegend = false },
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 84.dp, end = 12.dp),
                )
            }

            // Sortie en cours : raccourci pour la reprendre.
            current?.let { s ->
                FilledTonalButton(
                    onClick = { nav.navigate(Routes.sortie(s.id)) },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).heightIn(min = 52.dp),
                ) { Text(champiText("Reprendre la sortie")) }
            }
        }
    }
}

/** Bouton rond posé sur la carte. */
@Composable
private fun MapIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(52.dp),
        // Icône en couleur « sur surface » (sinon noire en thème sombre).
        colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface),
        content = content,
    )
}

/** Légende : une pastille par espèce présente sur la carte (+ spots sans récolte). */
@Composable
private fun Legend(spots: List<SpotMarker>, onClose: () -> Unit, modifier: Modifier) {
    val entries = spots
        .filter { it.colorHex != null && it.speciesName != null }
        .groupBy { it.speciesName!! to it.colorHex!! }
        .map { (k, v) -> Triple(k.first, k.second, v.size) }
        .sortedByDescending { it.third }
    val empty = spots.count { it.colorHex == null }

    Column(
        modifier
            .widthIn(max = 260.dp)
            .heightIn(max = 360.dp)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f), RoundedCornerShape(16.dp))
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(champiText("Couleur = dernière récolte"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        if (entries.isEmpty() && empty == 0) {
            Text(champiText("Aucun spot pour l'instant."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        entries.forEach { (name, hex, count) -> LegendRow(hex, "$name ($count)") }
        if (empty > 0) LegendRow(MapIcons.EMPTY_COLOR, "Sans récolte ($empty)")
        Text(
            champiText("Fermer"),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.clickable(onClick = onClose).padding(top = 4.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun LegendRow(hex: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(16.dp).background(colorFromHex(hex), CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(champiText(label), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}
