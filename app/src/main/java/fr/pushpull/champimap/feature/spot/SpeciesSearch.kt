package fr.pushpull.champimap.feature.spot

import fr.pushpull.champimap.core.db.Edibility
import fr.pushpull.champimap.core.db.Species
import java.util.Calendar

/*
 * Recherche d'espèces rapide, même avec une grosse base :
 *  - un INDEX EN MÉMOIRE construit une seule fois (noms normalisés : minuscules, sans accents),
 *  - un calcul des lignes à afficher (sections, lettres A–Z, résultats) fait HORS du fil principal
 *    par l'écran (Dispatchers.Default), sans aucune requête Room pendant la frappe.
 * Aucune dépendance à Compose ici : que des données.
 */

// -------------------------------------------------------------------------------------------
// Index en mémoire
// -------------------------------------------------------------------------------------------

/** Une espèce prête pour la recherche : noms normalisés et lettre de l'index A–Z. */
class IndexedSpecies(
    val species: Species,
    /** Clé de tri alphabétique (nom courant normalisé). */
    val sortKey: String,
    /** Lettre de rangement (A–Z, « # » si le nom ne commence pas par une lettre). */
    val letter: Char,
    /** Nom courant, latin et autres noms, tous normalisés. */
    val names: List<String>,
    /** Mois de pousse (1..12). */
    val months: Set<Int>,
)

/** Index complet, trié de A à Z. */
class SpeciesIndex(val entries: List<IndexedSpecies>) {
    val byId: Map<String, IndexedSpecies> = entries.associateBy { it.species.id }
}

/** Construit l'index (à appeler hors du fil principal : normalise tous les noms). */
fun buildSpeciesIndex(all: List<Species>): SpeciesIndex {
    val entries = all.map { s ->
        val sortKey = normalize(s.nameFr)
        val first = sortKey.firstOrNull()?.uppercaseChar() ?: '#'
        IndexedSpecies(
            species = s,
            sortKey = sortKey,
            letter = if (first in 'A'..'Z') first else '#',
            names = (listOf(s.nameFr, s.nameLatin) + (s.otherNames?.split(',', ';') ?: emptyList()))
                .map { normalize(it) }
                .filter { it.isNotEmpty() },
            months = s.months?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet() ?: emptySet(),
        )
    }.sortedBy { it.sortKey }
    return SpeciesIndex(entries)
}

/** Mois en cours (1..12), pour la section « De saison ». */
fun currentMonth(): Int = Calendar.getInstance().get(Calendar.MONTH) + 1

// -------------------------------------------------------------------------------------------
// Lignes affichées par la liste (LazyColumn avec clés stables)
// -------------------------------------------------------------------------------------------

/** Une ligne de la liste des espèces. `key` est unique et stable (clé de LazyColumn). */
sealed interface BrowseRow { val key: String }

/** Titre de section (« Récentes », « De saison », « Toutes les espèces »). */
data class SectionRow(val title: String, override val key: String) : BrowseRow

/** En-tête de lettre (« A », « B »…), cible de l'index A–Z. */
data class LetterRow(val letter: Char) : BrowseRow { override val key: String get() = "lettre-$letter" }

/** Une espèce. Le préfixe de clé distingue une même espèce présente dans plusieurs sections. */
data class SpeciesItemRow(val species: Species, override val key: String) : BrowseRow

/** Message (aucun résultat, chargement…). */
data class MessageRow(val text: String, override val key: String) : BrowseRow

/**
 * Résultat d'un calcul : lignes à afficher et position de chaque lettre
 * (vide si la liste n'est pas alphabétique, par exemple pendant une recherche).
 */
class BrowseResult(val rows: List<BrowseRow>, val letterPositions: Map<Char, Int>) {
    val alphabetical: Boolean get() = letterPositions.isNotEmpty()
}

/**
 * Calcule les lignes à afficher (à appeler sur Dispatchers.Default).
 * - Recherche saisie : résultats par pertinence (tolérants aux fautes), sans sections.
 * - Sinon : « Récentes », « De saison » (espèces courantes du mois), puis toutes de A à Z.
 * Les filtres de comestibilité (vides = tous) s'appliquent partout et se combinent à la recherche.
 */
fun computeBrowse(
    index: SpeciesIndex,
    rawQuery: String,
    filters: Set<Edibility>,
    recentIds: List<String>,
    month: Int,
): BrowseResult {
    val q = normalize(rawQuery)
    fun keep(e: IndexedSpecies) = filters.isEmpty() || e.species.edibility in filters
    val rows = ArrayList<BrowseRow>()

    // --- Recherche : par pertinence --------------------------------------------------------
    if (q.isNotEmpty()) {
        val found = index.entries.asSequence()
            .filter(::keep)
            .mapNotNull { e -> e.names.mapNotNull { matchScoreNormalized(it, q) }.minOrNull()?.let { e to it } }
            .sortedWith(compareBy({ it.second }, { it.first.sortKey }))
            .map { it.first }
            .toList()
        if (found.isEmpty()) rows += MessageRow("Aucune espèce trouvée.", "vide")
        found.forEach { rows += SpeciesItemRow(it.species, "q-" + it.species.id) }
        return BrowseResult(rows, emptyMap())
    }

    // --- Sections du haut --------------------------------------------------------------------
    val recent = recentIds.mapNotNull { index.byId[it] }.filter(::keep).take(6)
    if (recent.isNotEmpty()) {
        rows += SectionRow("Récentes", "sec-recentes")
        recent.forEach { rows += SpeciesItemRow(it.species, "r-" + it.species.id) }
    }
    val season = index.entries.filter { keep(it) && it.species.common && month in it.months }
    if (season.isNotEmpty()) {
        rows += SectionRow("De saison", "sec-saison")
        season.forEach { rows += SpeciesItemRow(it.species, "s-" + it.species.id) }
    }

    // --- Toutes les espèces, rangées par lettre --------------------------------------------
    val all = index.entries.filter(::keep)
    if (all.isEmpty()) {
        rows += MessageRow(if (index.entries.isEmpty()) "Liste des espèces en cours de chargement…" else "Aucune espèce pour ces filtres.", "vide")
        return BrowseResult(rows, emptyMap())
    }
    rows += SectionRow("Toutes les espèces (A → Z)", "sec-toutes")
    val positions = LinkedHashMap<Char, Int>()
    var current: Char? = null
    all.forEach { e ->
        if (e.letter != current) {
            current = e.letter
            positions[e.letter] = rows.size
            rows += LetterRow(e.letter)
        }
        rows += SpeciesItemRow(e.species, "a-" + e.species.id)
    }
    return BrowseResult(rows, positions)
}

/**
 * Position de la lettre demandée dans la liste ; si aucune espèce ne commence par elle,
 * la lettre suivante disponible (sinon la précédente).
 */
fun positionForLetter(result: BrowseResult, letter: Char): Int? {
    val p = result.letterPositions
    p[letter]?.let { return it }
    val after = p.keys.filter { it > letter }.minOrNull()
    if (after != null) return p[after]
    val before = p.keys.filter { it < letter }.maxOrNull()
    return before?.let { p[it] }
}
