package fr.pushpull.champimap.feature.carnet

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.db.Sortie
import fr.pushpull.champimap.core.settings.MessageTone
import fr.pushpull.champimap.core.ui.ChampiButton
import fr.pushpull.champimap.core.ui.ChampiCard
import fr.pushpull.champimap.core.ui.ChampiOutlinedButton
import fr.pushpull.champimap.core.ui.SectionTitle
import fr.pushpull.champimap.core.ui.VSpace
import fr.pushpull.champimap.feature.bonus.champiText
import kotlinx.coroutines.launch

/*
 * Bloc « Carnet » de la fin de sortie (inséré dans FinSortieScreen) :
 *  - récap « +N XP » de la sortie, détaillé par source ;
 *  - passage de niveau éventuel (et nouveau titre) ;
 *  - succès débloqués annoncés un par un (carte animée sobre), dans le ton choisi.
 */

/** Bloc carnet de la fin de sortie. [sortie] null = pas encore chargée (rien n'est affiché). */
@Composable
fun CarnetFinSortie(sortie: Sortie?, onOpenCarnet: () -> Unit) {
    if (sortie == null) return
    val state by remember { CarnetEngine.stateFlow() }.collectAsState(initial = null)
    val settings by remember { AppGraph.settings.flow }.collectAsState(initial = null)
    val st = state ?: return
    val tone = settings?.messageTone ?: MessageTone.NORMAL

    // XP de la sortie, et niveaux avant / après (XP gagnée avant le début de cette sortie + celle de la sortie).
    val bySource = st.xp.ofSortie(sortie.id)
    val gained = bySource.values.sum()
    val before = CarnetLevels.levelInfo(st.xp.before(sortie.startedAt, sortie.id))
    val after = CarnetLevels.levelInfo(before.totalXp + gained)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle(CarnetTextes.FIN_TITRE)
        XpRecap(gained, bySource, after)
        if (after.level > before.level) LevelUpCard(before, after, tone)
        BadgeAnnouncer(st.toAnnounce, tone, onOpenCarnet)
    }
}

/** « +N XP » et détail par source. */
@Composable
private fun XpRecap(gained: Int, bySource: Map<XpSource, Int>, level: LevelInfo) {
    ChampiCard(Modifier.fillMaxWidth()) {
        if (gained <= 0) {
            Text(
                champiText(CarnetTextes.FIN_AUCUNE_XP),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "+${fmtXp(gained.toLong())} XP",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            VSpace(6.dp)
            XpSource.entries.forEach { src ->
                val v = bySource[src] ?: return@forEach
                if (v <= 0) return@forEach
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(
                        champiText(CarnetTextes.SOURCES[src]?.nom ?: src.name),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text("+$v", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        VSpace(8.dp)
        Text(
            champiText("${CarnetTextes.NIVEAU} ${level.level} · ${level.titleWithStars}"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        VSpace(4.dp)
        XpBar(level.progress, height = 6.dp)
    }
}

/** Passage de niveau : numéro, message, et nouveau titre (ou nouvelle étoile) s'il change. */
@Composable
private fun LevelUpCard(before: LevelInfo, after: LevelInfo, tone: MessageTone) {
    val anim = remember(after.level) { Animatable(0.85f) }
    LaunchedEffect(after.level) { anim.animateTo(1f, tween(400)) }
    ChampiCard(Modifier.fillMaxWidth().graphicsLayer { scaleX = anim.value; scaleY = anim.value }) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                champiText("${CarnetTextes.FIN_NIVEAU_ATTEINT} ${after.level} !"),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center,
            )
            VSpace(4.dp)
            Text(champiText(CarnetTextes.passageNiveau(after.level, tone)), textAlign = TextAlign.Center)
            if (after.title != before.title) {
                val titre = CarnetLevels.titleOf(after.level)
                VSpace(8.dp)
                Text(
                    champiText("${CarnetTextes.FIN_NOUVEAU_TITRE} : ${titre.nom}"),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Text(champiText(titre.message.pour(tone)), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            } else if (after.stars > before.stars) {
                VSpace(8.dp)
                Text(champiText(after.titleWithStars), style = MaterialTheme.typography.titleMedium)
                Text(champiText(CarnetTextes.ETOILE.pour(tone)), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * Annonce des nouveaux succès, un par un : carte animée (apparition douce), bouton « Suivant »,
 * puis « Super ! » au dernier. Chaque succès annoncé est marqué comme vu.
 */
@Composable
private fun BadgeAnnouncer(toAnnounce: List<String>, tone: MessageTone, onOpenCarnet: () -> Unit) {
    // Liste figée dès qu'il y a quelque chose à annoncer : les succès marqués « vus » ne font pas sauter l'affichage.
    val list = remember(toAnnounce.isEmpty()) { toAnnounce.filter { it in CarnetBadges.BY_ID } }
    var index by remember(list) { mutableStateOf(0) }
    if (index >= list.size) return

    val id = list[index]
    val def = CarnetBadges.BY_ID[id] ?: return
    val textes = CarnetTextes.succes(id)

    // Apparition : léger zoom et fondu, relancés à chaque succès.
    val anim = remember(id) { Animatable(0f) }
    LaunchedEffect(id) { anim.animateTo(1f, tween(450)) }

    /** Marque le succès courant comme vu, puis passe au suivant. */
    fun next() {
        AppGraph.appScope.launch { CarnetEngine.markAnnounced(listOf(id)) }
        index++
    }

    ChampiCard(
        Modifier.fillMaxWidth().graphicsLayer {
            alpha = anim.value
            val sc = 0.9f + 0.1f * anim.value
            scaleX = sc
            scaleY = sc
        },
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                champiText(CarnetTextes.NOUVEAU_SUCCES) + if (list.size > 1) "  ${index + 1} / ${list.size}" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VSpace(8.dp)
            BadgePicto(def.family, unlocked = true, size = 72.dp)
            VSpace(8.dp)
            Text(champiText(textes.nom), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            VSpace(4.dp)
            Text(champiText(textes.message.pour(tone)), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            VSpace(12.dp)
            ChampiButton(if (index + 1 < list.size) CarnetTextes.SUIVANT else CarnetTextes.TERMINER, onClick = { next() })
            VSpace(6.dp)
            ChampiOutlinedButton(CarnetTextes.TOUT_VOIR, onClick = {
                // Tout ce qui reste est vu dans le carnet : on le marque d'un coup.
                val rest = list.drop(index)
                AppGraph.appScope.launch { CarnetEngine.markAnnounced(rest) }
                index = list.size
                onOpenCarnet()
            })
        }
    }
}
