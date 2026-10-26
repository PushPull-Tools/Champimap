package fr.pushpull.champimap.feature.carnet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.settings.MessageTone
import fr.pushpull.champimap.core.ui.ChampiCard
import fr.pushpull.champimap.core.ui.ChampiScreen
import fr.pushpull.champimap.core.ui.SectionTitle
import fr.pushpull.champimap.core.ui.VSpace
import fr.pushpull.champimap.feature.bonus.champiText
import kotlinx.coroutines.launch

/*
 * Écran « Mon carnet » (route Routes.CARNET) : niveau, titre (+ étoiles), barre d'XP, XP totale,
 * prochain titre, origine de l'XP, et grille des succès par famille avec le compteur « 23 / 80 succès ».
 * Succès obtenus : picto en couleur + date ; non obtenus : silhouette « ? » ; secrets : invisibles.
 */

/** Nombre de tuiles par ligne de la grille des succès. */
private const val COLONNES = 3

/** Écran du carnet. Signature appelée par AppNav. */
@Composable
fun CarnetScreen(nav: NavController) {
    val state by remember { CarnetEngine.stateFlow() }.collectAsState(initial = null)
    val settings by remember { AppGraph.settings.flow }.collectAsState(initial = null)
    val tone = settings?.messageTone ?: MessageTone.NORMAL
    var opened by remember { mutableStateOf<BadgeDef?>(null) }

    // Les succès « Nouveau » de cette visite : figés au premier état reçu, marqués jusqu'à la sortie de l'écran.
    val st = state
    val news = remember(st == null) { st?.toAnnounce?.toSet().orEmpty() }
    val latestNews by rememberUpdatedState(news)

    // En quittant le carnet, tout ce qui a été vu ici est considéré comme annoncé.
    DisposableEffect(Unit) {
        onDispose {
            val ids = latestNews.toList()
            if (ids.isNotEmpty()) AppGraph.appScope.launch { CarnetEngine.markAnnounced(ids) }
        }
    }

    ChampiScreen(title = CarnetTextes.ECRAN_TITRE, onBack = { nav.popBackStack() }) {
        if (st == null) return@ChampiScreen
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LevelCard(st.level)
            XpOrigin(st.xp)
            BadgeCounter(st)
            BadgeFamily.entries.forEach { fam ->
                FamilyGrid(fam, st, news) { opened = it }
            }
            VSpace(16.dp)
        }
    }

    opened?.let { def -> BadgeDialog(def, st?.unlocked?.get(def.id), tone) { opened = null } }
}

/** Carte du niveau : grand numéro, titre, barre, XP totale et prochain titre. */
@Composable
private fun LevelCard(level: LevelInfo) {
    ChampiCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                champiText("${CarnetTextes.NIVEAU} ${level.level}"),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(champiText(level.titleWithStars), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            VSpace(10.dp)
            XpBar(level.progress, height = 10.dp)
            VSpace(4.dp)
            Text(
                "${fmtXp(level.xpInLevel)} / ${fmtXp(level.xpForNext)} XP ${CarnetTextes.VERS_NIVEAU} ${level.level + 1}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VSpace(8.dp)
            Text(
                "${CarnetTextes.XP_TOTALE} : ${fmtXp(level.totalXp)}",
                style = MaterialTheme.typography.titleMedium,
            )
            val next = CarnetLevels.nextTitle(level.level)
            val nextLine = if (next != null) {
                "${CarnetTextes.PROCHAIN_TITRE} : ${next.nom} (${CarnetTextes.NIVEAU.lowercase()} ${next.niveau})"
            } else {
                "${CarnetTextes.TOUS_TITRES} ${CarnetLevels.nextStarLevel(level.level)}."
            }
            Text(
                champiText(nextLine),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** D'où vient l'XP : total par source, avec la règle de chaque source. */
@Composable
private fun XpOrigin(xp: XpResult) {
    val bySource = xp.bySource()
    if (bySource.isEmpty()) return
    Column {
        SectionTitle(CarnetTextes.ORIGINE_XP)
        ChampiCard(Modifier.fillMaxWidth()) {
            XpSource.entries.forEach { src ->
                val v = bySource[src] ?: return@forEach
                val t = CarnetTextes.SOURCES[src] ?: return@forEach
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(champiText(t.nom), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            champiText(t.regle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("+${fmtXp(v.toLong())}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** Compteur « 23 / 80 succès » (les secrets comptent dans le total, mais restent cachés). */
@Composable
private fun BadgeCounter(st: CarnetState) {
    val got = st.unlocked.keys.count { it in CarnetBadges.BY_ID }
    Text(
        "$got / ${CarnetBadges.TOTAL} ${CarnetTextes.MOT_SUCCES}",
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
}

/** Une famille : titre, description, puis grille des succès (secrets non obtenus invisibles). */
@Composable
private fun FamilyGrid(fam: BadgeFamily, st: CarnetState, news: Set<String>, onOpen: (BadgeDef) -> Unit) {
    val defs = CarnetBadges.ALL.filter { it.family == fam }
    val visible = defs.filter { !it.secret || it.id in st.unlocked }
    val textes = CarnetTextes.FAMILLES[fam] ?: return
    // Famille des secrets : on n'affiche rien tant qu'aucun n'est découvert.
    if (fam == BadgeFamily.SECRETS && visible.isEmpty()) return
    val got = defs.count { it.id in st.unlocked }

    Column {
        val header = if (fam == BadgeFamily.SECRETS) {
            "${textes.nom} · $got ${CarnetTextes.SECRETS_DECOUVERTS}"
        } else {
            "${textes.nom} · $got / ${defs.size}"
        }
        SectionTitle(header)
        ChampiCard(Modifier.fillMaxWidth()) {
            Text(
                champiText(textes.description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VSpace(6.dp)
            visible.chunked(COLONNES).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { def ->
                        BadgeTile(
                            def = def,
                            unlockedAt = st.unlocked[def.id],
                            isNew = def.id in news,
                            modifier = Modifier.weight(1f),
                            onClick = { onOpen(def) },
                        )
                    }
                    // Cases vides pour garder des colonnes alignées sur la dernière ligne.
                    repeat(COLONNES - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
