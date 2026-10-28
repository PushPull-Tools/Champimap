package fr.pushpull.champimap.feature.spot

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.pushpull.champimap.core.db.Edibility
import fr.pushpull.champimap.core.db.Species
import fr.pushpull.champimap.core.ui.ChampiSearchField
import fr.pushpull.champimap.core.ui.SectionTitle
import fr.pushpull.champimap.feature.bonus.champiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Liste d'espèces commune au choix d'espèce (« Complète ta cueillette ») et au catalogue :
 * barre de recherche, filtres de comestibilité en pastilles, sections « Récentes » / « De saison »,
 * toutes les espèces de A à Z avec en-têtes de lettre et INDEX A–Z sur le bord droit
 * (appui ou glissement sur une lettre → saut, lettre affichée en grand pendant le glissement).
 * Les calculs sont faits hors du fil principal, avec un petit délai anti-rebond pendant la frappe.
 */

/** Filtres de comestibilité proposés (dans cet ordre). */
private val FILTRES = listOf(Edibility.COMESTIBLE, Edibility.TOXIQUE, Edibility.MORTEL, Edibility.PSYCHOTROPE)

/** Lettres de l'index du bord droit. */
private val LETTRES = ('A'..'Z').toList()

/** Délai anti-rebond pendant la frappe (ms). */
private const val ANTI_REBOND_MS = 120L

/**
 * Liste d'espèces avec recherche, filtres et index A–Z.
 * @param all toutes les espèces (lues une seule fois depuis la base par l'écran).
 * @param recentIds espèces récemment récoltées (section « Récentes »), vide = pas de section.
 * @param header contenu affiché au-dessus de la recherche (ex. photo à identifier).
 * @param onPick appui sur une espèce.
 */
@Composable
fun SpeciesBrowser(
    all: List<Species>,
    recentIds: List<String>,
    onPick: (Species) -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    var filters by remember { mutableStateOf(setOf<Edibility>()) }
    val month = remember { currentMonth() }

    // Index en mémoire, reconstruit seulement quand la liste des espèces change.
    val index by produceState<SpeciesIndex?>(initialValue = null, all) {
        value = withContext(Dispatchers.Default) { buildSpeciesIndex(all) }
    }
    // Lignes affichées : recalculées hors du fil principal (anti-rebond pendant la frappe).
    var result by remember { mutableStateOf(BrowseResult(emptyList(), emptyMap())) }
    LaunchedEffect(index, query, filters, recentIds) {
        val idx = index ?: return@LaunchedEffect
        if (query.isNotBlank()) delay(ANTI_REBOND_MS) // une nouvelle frappe annule ce calcul
        result = withContext(Dispatchers.Default) { computeBrowse(idx, query, filters, recentIds, month) }
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var dragLetter by remember { mutableStateOf<Char?>(null) }
    // Nouvelle recherche ou nouveaux filtres : on revient en haut de la liste.
    LaunchedEffect(query, filters) { if (listState.firstVisibleItemIndex > 0) listState.scrollToItem(0) }

    Column(modifier) {
        header()
        ChampiSearchField(
            query = query,
            onQueryChange = { query = it },
            placeholder = "Nom courant ou latin",
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        // Filtres de comestibilité (combinables entre eux et avec la recherche).
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FILTRES.forEach { e ->
                SelectChip(edibilityLabel(e), selected = e in filters) {
                    filters = if (e in filters) filters - e else filters + e
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
            val showIndex = result.alphabetical
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(end = if (showIndex) 28.dp else 0.dp),
            ) {
                items(
                    result.rows,
                    key = { it.key },
                    contentType = { row -> rowType(row) },
                ) { row ->
                    when (row) {
                        is SectionRow -> SectionTitle(row.title, Modifier.padding(horizontal = 16.dp).padding(top = 8.dp))
                        is LetterRow -> LetterHeader(row.letter)
                        is SpeciesItemRow -> SpeciesRow(row.species) { onPick(row.species) }
                        is MessageRow -> Text(
                            champiText(row.text),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                item(key = "bas") { Spacer(Modifier.heightIn(min = 32.dp)) }
            }

            if (showIndex) {
                AlphabetIndex(
                    available = result.letterPositions.keys,
                    onLetter = { c ->
                        dragLetter = c
                        positionForLetter(result, c)?.let { pos -> scope.launch { listState.scrollToItem(pos) } }
                    },
                    onRelease = { dragLetter = null },
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(28.dp),
                )
            }

            // Lettre en grand pendant le glissement sur l'index.
            dragLetter?.let { c ->
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shadowElevation = 6.dp,
                    modifier = Modifier.align(Alignment.Center).size(96.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(c.toString(), style = MaterialTheme.typography.headlineLarge)
                    }
                }
            }
        }
    }
}

/** Type de ligne (recyclage des lignes de même forme par la LazyColumn). */
private fun rowType(row: BrowseRow): Int = when (row) {
    is SectionRow -> 0
    is LetterRow -> 1
    is SpeciesItemRow -> 2
    is MessageRow -> 3
}

/** En-tête de lettre dans la liste (« A », « B »…). */
@Composable
private fun LetterHeader(letter: Char) {
    Text(
        letter.toString(),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

/** Ligne d'espèce : disque coloré, noms (une ligne chacun), pastille de comestibilité. */
@Composable
fun SpeciesRow(s: Species, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpeciesDot(s, 16)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(champiText(s.nameFr), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                s.nameLatin,
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        EdibilityChip(s.edibility)
    }
}

/**
 * Index A–Z du bord droit, façon répertoire de contacts : un appui ou un glissement
 * donne la lettre sous le doigt (`onLetter` à chaque changement), `onRelease` au lâcher.
 * Les lettres sans espèce sont grisées (le saut va alors à la lettre suivante).
 */
@Composable
private fun AlphabetIndex(
    available: Set<Char>,
    onLetter: (Char) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Rappels à jour même si la liste change pendant un glissement.
    val currentOnLetter by rememberUpdatedState(onLetter)
    val currentOnRelease by rememberUpdatedState(onRelease)
    BoxWithConstraints(
        modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    var last: Char? = null
                    /** Lettre sous le doigt selon la hauteur touchée. */
                    fun pick(y: Float) {
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        val i = ((y / h) * LETTRES.size).toInt().coerceIn(0, LETTRES.size - 1)
                        val c = LETTRES[i]
                        if (c != last) { last = c; currentOnLetter(c) }
                    }
                    val down = awaitFirstDown()
                    down.consume()
                    pick(down.position.y)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        change.consume()
                        pick(change.position.y)
                    }
                    currentOnRelease()
                }
            },
    ) {
        // Taille des lettres adaptée à la hauteur disponible (clavier ouvert = index plus serré).
        val fontSize = ((maxHeight.value / LETTRES.size) * 0.62f).coerceIn(7f, 13f).sp
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LETTRES.forEach { c ->
                Text(
                    c.toString(),
                    fontSize = fontSize,
                    lineHeight = fontSize,
                    maxLines = 1,
                    fontWeight = if (c in available) FontWeight.Bold else FontWeight.Normal,
                    color = if (c in available) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                )
            }
        }
    }
}
