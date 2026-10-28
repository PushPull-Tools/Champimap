package fr.pushpull.champimap.feature.carnet

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
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
import fr.pushpull.champimap.feature.bonus.animationsEnabled
import fr.pushpull.champimap.feature.bonus.champiText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Bloc « Carnet » de la fin de sortie (inséré dans FinSortieScreen) :
 *  - récap « +N XP » de la sortie, détaillé par source ;
 *  - passage de niveau éventuel (et nouveau titre) ;
 *  - succès débloqués annoncés un par un (carte qui se retourne), dans le ton choisi.
 *
 * Page de transition (instant = false) : les lignes d'XP apparaissent une par une, leurs points
 * s'ajoutent au total qui compte et la barre de niveau se remplit en direct ; si un niveau est
 * atteint, la barre se vide et repart avec « Niveau N ! » qui rebondit. La carte de passage de
 * niveau puis les succès arrivent ensuite. instant = true → état final directement.
 */

/** Petite pause avant la première ligne. */
private const val FIRST_LINE_DELAY_MS = 200L

/** Apparition d'une ligne d'XP (avant que ses points s'ajoutent). */
private const val LINE_IN_MS = 300

/** Pause entre deux lignes. */
private const val LINE_GAP_MS = 150L

/** Durée du comptage des points d'une ligne (selon leur nombre, bornée). */
private fun countMs(points: Int): Int = (350 + points * 2).coerceIn(400, 1_100)

/**
 * Bloc carnet de la fin de sortie. [sortie] null = pas encore chargée (rien n'est affiché).
 * @param instant état final directement (pas d'animation, ou toucher pour passer).
 * @param start l'animation de l'XP peut commencer (les chiffres du récap ont fini de compter).
 * @param onAnimationDone appelé quand l'XP est entièrement affichée.
 */
@Composable
fun CarnetFinSortie(
    sortie: Sortie?,
    onOpenCarnet: () -> Unit,
    instant: Boolean = true,
    start: Boolean = true,
    onAnimationDone: () -> Unit = {},
) {
    if (sortie == null) return
    val state by remember { CarnetEngine.stateFlow() }.collectAsState(initial = null)
    val settings by remember { AppGraph.settings.flow }.collectAsState(initial = null)
    val st = state ?: return
    val tone = settings?.messageTone ?: MessageTone.NORMAL

    // XP de la sortie (lignes non nulles, dans l'ordre des sources), et niveaux avant / après
    // (XP gagnée avant le début de cette sortie + celle de la sortie).
    val bySource = st.xp.ofSortie(sortie.id)
    val rows = XpSource.entries.mapNotNull { src -> bySource[src]?.takeIf { it > 0 }?.let { src to it } }
    val gained = rows.sumOf { it.second }
    val before = CarnetLevels.levelInfo(st.xp.before(sortie.startedAt, sortie.id))
    val after = CarnetLevels.levelInfo(before.totalXp + gained)

    // --- Animation de l'XP -----------------------------------------------------------------------
    // xp : total affiché pendant le comptage ; shown : nombre de lignes déjà apparues ; done : fini.
    val xp = remember(sortie.id) { Animatable(0f) }
    var shown by remember(sortie.id) { mutableStateOf(if (instant) Int.MAX_VALUE else 0) }
    var done by remember(sortie.id) { mutableStateOf(instant) }
    val latestRows by rememberUpdatedState(rows)
    val latestOnDone by rememberUpdatedState(onAnimationDone)

    LaunchedEffect(sortie.id, instant, start) {
        if (instant) {
            shown = Int.MAX_VALUE
            done = true
            latestOnDone()
            return@LaunchedEffect
        }
        if (!start) return@LaunchedEffect
        delay(FIRST_LINE_DELAY_MS)
        var total = 0
        latestRows.forEachIndexed { i, (_, points) ->
            // La ligne apparaît, puis ses points s'ajoutent au total (la barre suit).
            shown = i + 1
            delay(LINE_IN_MS.toLong())
            total += points
            xp.animateTo(total.toFloat(), tween(countMs(points), easing = FastOutSlowInEasing))
            delay(LINE_GAP_MS)
        }
        done = true
        latestOnDone()
    }

    // Total affiché : valeur animée pendant la transition, valeur réelle ensuite.
    val displayed = if (done) gained.toLong() else xp.value.toLong()
    val live = CarnetLevels.levelInfo(before.totalXp + displayed)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle(CarnetTextes.FIN_TITRE)
        XpRecap(
            gained = gained,
            displayed = displayed,
            rows = rows,
            shown = if (done) rows.size else shown,
            live = live,
            levelUpFrom = if (done) null else before.level,
        )
        // Carte de niveau et succès : seulement une fois l'XP entièrement comptée.
        if (done) {
            if (after.level > before.level) LevelUpCard(before, after, tone, animate = !instant)
            BadgeAnnouncer(st.toAnnounce, tone, onOpenCarnet, animateFirst = !instant)
        }
    }
}

/**
 * « +N XP » (qui compte), détail par source (lignes qui apparaissent une à une),
 * niveau en cours et barre qui se remplit en direct.
 * @param levelUpFrom niveau de départ pendant l'animation (null = état final, pas d'effet).
 */
@Composable
private fun XpRecap(
    gained: Int,
    displayed: Long,
    rows: List<Pair<XpSource, Int>>,
    shown: Int,
    live: LevelInfo,
    levelUpFrom: Int?,
) {
    // Rebond de « Niveau N ! » à chaque niveau franchi pendant le comptage.
    val pop = remember { Animatable(1f) }
    val leveledUp = levelUpFrom != null && live.level > levelUpFrom
    LaunchedEffect(live.level) {
        if (leveledUp) {
            pop.snapTo(1.3f)
            pop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMediumLow))
        }
    }

    ChampiCard(Modifier.fillMaxWidth()) {
        if (gained <= 0) {
            Text(
                champiText(CarnetTextes.FIN_AUCUNE_XP),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "+${fmtXp(displayed)} XP",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            VSpace(6.dp)
            // Toutes les lignes sont posées dès le début (pas de saut de mise en page) ; elles se montrent une à une.
            rows.forEachIndexed { i, (src, points) ->
                XpLine(CarnetTextes.SOURCES[src]?.nom ?: src.name, points, visible = i < shown)
            }
        }
        VSpace(8.dp)
        if (leveledUp) {
            // Pendant le comptage : « Niveau N ! » en couleur girolle, qui rebondit.
            Text(
                champiText("${CarnetTextes.NIVEAU} ${live.level} !"),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                    transformOrigin = TransformOrigin(0f, 0.5f)
                },
            )
        } else {
            Text(
                champiText("${CarnetTextes.NIVEAU} ${live.level} · ${live.titleWithStars}"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        VSpace(4.dp)
        XpBar(live.progress, height = 8.dp)
    }
}

/** Ligne « Source … +N » : glisse et apparaît en fondu quand [visible] devient vrai. */
@Composable
private fun XpLine(label: String, points: Int, visible: Boolean) {
    val a = remember { Animatable(if (visible) 1f else 0f) }
    LaunchedEffect(visible) {
        if (visible) a.animateTo(1f, tween(LINE_IN_MS, easing = FastOutSlowInEasing)) else a.snapTo(0f)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .graphicsLayer {
                alpha = a.value
                translationX = (1f - a.value) * 24.dp.toPx()
            },
    ) {
        Text(champiText(label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text("+$points", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** Passage de niveau : numéro, message, et nouveau titre (ou nouvelle étoile) s'il change. */
@Composable
private fun LevelUpCard(before: LevelInfo, after: LevelInfo, tone: MessageTone, animate: Boolean) {
    // Zoom doux à l'apparition (sauf état final direct).
    val anim = remember(after.level) { Animatable(if (animate) 0.85f else 1f) }
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
 * Annonce des nouveaux succès, un par un : carte qui se retourne (zoom doux), bouton « Suivant »,
 * puis « Super ! » au dernier. Chaque succès annoncé est marqué comme vu.
 * @param animateFirst animer aussi le premier succès (faux = état final direct).
 */
@Composable
private fun BadgeAnnouncer(toAnnounce: List<String>, tone: MessageTone, onOpenCarnet: () -> Unit, animateFirst: Boolean) {
    // Liste figée dès qu'il y a quelque chose à annoncer : les succès marqués « vus » ne font pas sauter l'affichage.
    val list = remember(toAnnounce.isEmpty()) { toAnnounce.filter { it in CarnetBadges.BY_ID } }
    var index by remember(list) { mutableStateOf(0) }
    if (index >= list.size) return

    val id = list[index]
    val def = CarnetBadges.BY_ID[id] ?: return
    val textes = CarnetTextes.succes(id)

    // Apparition : la carte se retourne avec un léger zoom, relancée à chaque succès
    // (pas d'animation si « réduire les animations », ni pour le premier en état final direct).
    val enabled = animationsEnabled()
    val animate = enabled && (index > 0 || animateFirst)
    val anim = remember(id) { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(id) { if (animate) anim.animateTo(1f, tween(550, easing = FastOutSlowInEasing)) }

    /** Marque le succès courant comme vu, puis passe au suivant. */
    fun next() {
        AppGraph.appScope.launch { CarnetEngine.markAnnounced(listOf(id)) }
        index++
    }

    ChampiCard(
        Modifier.fillMaxWidth().graphicsLayer {
            // Retournement : de 90° (carte vue par la tranche) à 0°, fondu rapide au début.
            rotationY = (1f - anim.value) * 90f
            cameraDistance = 12f * density
            alpha = (anim.value * 2f).coerceAtMost(1f)
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
