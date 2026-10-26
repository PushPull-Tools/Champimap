package fr.pushpull.champimap.core.map

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.maplibre.android.maps.MapLibreMap

/*
 * SOCLE : carte hors ligne partagée par tous les écrans (sortie, carte, trésors).
 * Le module sortie l'implémente avec MapLibre et les fichiers de carte locaux
 * (PMTiles fournis par Champimap Connect), ou une grille vide sans carte.
 * Signature à conserver EXACTEMENT.
 */
@Composable
fun ChampiMapView(
    modifier: Modifier = Modifier,
    onMapReady: (MapLibreMap) -> Unit = {},
) {
    Box(modifier)
}
