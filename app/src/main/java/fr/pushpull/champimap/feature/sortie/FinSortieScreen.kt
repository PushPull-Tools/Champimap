package fr.pushpull.champimap.feature.sortie

import fr.pushpull.champimap.core.util.motPluriel
import fr.pushpull.champimap.core.util.pluriel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.db.SortieSpotSummary
import fr.pushpull.champimap.core.ui.ChampiButton
import fr.pushpull.champimap.core.ui.ChampiCard
import fr.pushpull.champimap.core.ui.ChampiOutlinedButton
import fr.pushpull.champimap.core.ui.ChampiScreen
import fr.pushpull.champimap.core.ui.SectionTitle
import fr.pushpull.champimap.feature.bonus.champiText
import fr.pushpull.champimap.feature.carnet.CarnetFinSortie
import fr.pushpull.champimap.feature.ventes.VenteFinSortieButton
import fr.pushpull.champimap.nav.Routes
import java.util.Locale

/*
 * Fin de sortie : récapitulatif (durée, km, spots), liste des spots (« Compléter » si rien n'est saisi),
 * « Ajouter une autre sortie », « Retour à l'accueil », lien discret « En cas d'intoxication ».
 */
@Composable
fun FinSortieScreen(nav: NavController, sortieId: String) {
    val context = LocalContext.current
    val db = AppGraph.db
    val sortie by remember(sortieId) { db.sortieDao().flow(sortieId) }.collectAsState(initial = null)
    val track by remember(sortieId) { db.sortieDao().track(sortieId) }.collectAsState(initial = emptyList())
    val spots by remember(sortieId) { db.sortieDao().spotsSummary(sortieId) }.collectAsState(initial = emptyList())
    val settings by remember { AppGraph.settings.flow }.collectAsState(initial = null)

    // Arrivé ici, le suivi n'a plus lieu d'être (sortie terminée depuis l'écran ou la notification).
    LaunchedEffect(sortie?.endedAt) { if (sortie?.endedAt != null) SortieTrackingService.stop(context) }

    val km = remember(track) { Geo.trackLengthM(track) }
    val duration = sortie?.let { (it.endedAt ?: System.currentTimeMillis()) - it.startedAt } ?: 0L
    val totalG = spots.sumOf { it.weightG }

    /** Retour à l'accueil en vidant la pile. */
    fun home() = nav.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = true }; launchSingleTop = true }

    ChampiScreen(title = "Sortie terminée", onBack = { home() }) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Récapitulatif en trois tuiles.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RecapTile(Geo.formatDuration(duration), "durée", Modifier.weight(1f))
                RecapTile(Geo.formatDistance(km), "parcourus", Modifier.weight(1f))
                RecapTile("${spots.size}", motPluriel(spots.size, "spot"), Modifier.weight(1f))
            }
            if (totalG > 0) {
                Text(
                    champiText("Récolte pesée : " + formatWeight(totalG)),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }

            // Carnet : XP gagnée pendant la sortie, passage de niveau, nouveaux succès (un par un).
            CarnetFinSortie(sortie, onOpenCarnet = { nav.navigate(Routes.CARNET) })

            SectionTitle("Spots de la sortie")
            ChampiCard(Modifier.fillMaxWidth()) {
                if (spots.isEmpty()) {
                    Text(champiText("Aucun spot noté pendant cette sortie."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                spots.forEachIndexed { i, s ->
                    if (i > 0) HorizontalDivider()
                    SpotSummaryRow(s, onOpen = { nav.navigate(Routes.spot(s.id, sortieId)) })
                }
            }

            // Mode vendeur (expérimental) : vente liée à cette sortie (bouton masqué si le mode est coupé).
            VenteFinSortieButton(nav, sortieId)

            ChampiOutlinedButton("Ajouter une autre sortie", onClick = {
                nav.navigate(Routes.DEPART) { popUpTo(Routes.HOME) }
            })
            ChampiButton("Retour à l'accueil", onClick = { home() })

            // Lien discret vers la fiche intoxication (masquable dans les réglages).
            if (settings?.intoxLinkVisible != false) {
                Text(
                    "En cas d'intoxication",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textDecoration = TextDecoration.Underline,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { nav.navigate(Routes.intox(sortieId)) }
                        .padding(vertical = 14.dp),
                )
            }
        }
    }
}

/** Tuile de chiffre clé. */
@Composable
private fun RecapTile(value: String, label: String, modifier: Modifier) {
    ChampiCard(modifier) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
            Text(champiText(label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Ligne d'un spot : nom, ce qui a été saisi, « Compléter » si rien. */
@Composable
private fun SpotSummaryRow(s: SortieSpotSummary, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onOpen).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(s.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            val detail = when {
                s.items > 0 -> pluriel(s.items, "espèce") + if (s.weightG > 0) " · " + formatWeight(s.weightG) else ""
                s.emptyVisit -> "Spot vide"
                else -> "Rien de saisi"
            }
            Text(champiText(detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (s.items == 0 && !s.emptyVisit) {
            TextButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) { Text(champiText("Compléter")) }
        }
    }
}

/** « 850 g » ou « 1,25 kg ». */
private fun formatWeight(g: Int): String =
    if (g < 1000) "$g g" else String.format(Locale.FRANCE, "%.2f kg", g / 1000.0).replace(",00 kg", " kg")
