package fr.pushpull.champimap.feature.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.db.HarvestRow
import fr.pushpull.champimap.core.db.Person
import fr.pushpull.champimap.core.db.Sortie
import fr.pushpull.champimap.core.db.VisitRow
import fr.pushpull.champimap.feature.bonus.champiText

/*
 * Briques d'interface du module stats : chargement des données, sélecteur segmenté,
 * tuiles de chiffres, graphique en barres, podium.
 */

/** Toutes les données utiles aux écrans de stats, observées en direct. */
data class StatsData(
    val harvests: List<HarvestRow>,
    val sorties: List<Sortie>,
    val visits: List<VisitRow>,
    val persons: List<Person>,
)

/** Observe la base et renvoie les données (listes vides pendant le premier chargement). */
@Composable
fun rememberStatsData(): StatsData {
    val dao = AppGraph.db.statsDao()
    val harvestsFlow = remember { dao.harvestsFlow() }
    val sortiesFlow = remember { dao.sortiesFlow() }
    val visitsFlow = remember { dao.visitsFlow() }
    val personsFlow = remember { AppGraph.db.personDao().all() }
    val harvests by harvestsFlow.collectAsState(initial = emptyList())
    val sorties by sortiesFlow.collectAsState(initial = emptyList())
    val visits by visitsFlow.collectAsState(initial = emptyList())
    val persons by personsFlow.collectAsState(initial = emptyList())
    return StatsData(harvests, sorties, visits, persons)
}

/** Sélecteur segmenté (Jour / Semaine / Mois / Saison), fait main pour rester stable et grand. */
@Composable
fun SegmentedSelector(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val isSel = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSel) scheme.primary else Color.Transparent)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    champiText(label),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSel) scheme.onPrimary else scheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Tuile chiffre + libellé (sorties, spots…). */
@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(champiText(label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/**
 * Graphique en barres dessiné avec des Box (pas de bibliothèque).
 * Appui sur une barre → `onSelect(index)` ; la barre choisie est mise en avant.
 */
@Composable
fun BarChart(bars: List<BarValue>, selected: Int?, onSelect: (Int) -> Unit, height: Dp = 140.dp) {
    val scheme = MaterialTheme.colorScheme
    val max = (bars.maxOfOrNull { it.grams } ?: 0L).coerceAtLeast(1L)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(height),
            horizontalArrangement = Arrangement.spacedBy(if (bars.size > 15) 2.dp else 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            bars.forEachIndexed { i, b ->
                val fraction = (b.grams.toFloat() / max).coerceIn(0f, 1f)
                val color = when {
                    selected == i -> scheme.secondary
                    b.highlight -> scheme.primary
                    else -> scheme.primary.copy(alpha = 0.55f)
                }
                Box(
                    Modifier.weight(1f).fillMaxHeight().clickable { onSelect(i) },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    // Trait minimal pour les cases vides : on voit que la case existe.
                    val barModifier = if (b.grams > 0) Modifier.fillMaxHeight(fraction.coerceAtLeast(0.03f)) else Modifier.height(2.dp)
                    Box(
                        barModifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(if (b.grams > 0) color else scheme.outline),
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (bars.size > 15) 2.dp else 6.dp)) {
            bars.forEach { b ->
                Text(
                    b.label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

/**
 * Podium 1-2-3 : 2e à gauche, 1er au centre (plus haut), 3e à droite.
 * Le rang est écrit dans un cercle, centré par une Box (alignement Center, sans marge de police).
 */
@Composable
fun Podium(entries: List<PodiumEntry>) {
    val scheme = MaterialTheme.colorScheme
    // Ordre visuel : 2, 1, 3 (les places absentes laissent un vide).
    val order = listOf(1, 0, 2)
    val heights = listOf(70.dp, 96.dp, 54.dp)
    val medalColors = listOf(Color(0xFFC9A227), Color(0xFF9EA7AD), Color(0xFFB0703C))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
        order.forEachIndexed { pos, rank ->
            val e = entries.getOrNull(rank)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                if (e != null) {
                    Text(
                        champiText(if (e.isMe) "${e.name} (moi)" else e.name),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    Text(StatsFormat.kg(e.grams), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(heights[pos])
                            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                            .background(if (rank == 0) scheme.primary else scheme.primaryContainer),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        RankCircle(rank + 1, medalColors[rank], Modifier.padding(top = 10.dp))
                    }
                }
            }
        }
    }
}

/** Chiffre dans un cercle, parfaitement centré (Box carrée + alignement Center). */
@Composable
fun RankCircle(rank: Int, color: Color, modifier: Modifier = Modifier, size: Dp = 34.dp) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .border(2.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            rank.toString(),
            color = Color.White,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 16.sp,
            lineHeight = 16.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/** Petite ligne « libellé ……… valeur ». */
@Composable
fun KeyValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(champiText(label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
