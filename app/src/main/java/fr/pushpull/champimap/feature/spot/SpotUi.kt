package fr.pushpull.champimap.feature.spot

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.db.Edibility
import fr.pushpull.champimap.core.db.Species
import fr.pushpull.champimap.core.ui.ChampiChip
import fr.pushpull.champimap.feature.bonus.champiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Petits outils d'interface du module spot : enregistrement automatique (anti-rebond 300 ms),
 * pastilles (comestibilité, sélectionnables), bandeau « Supprimé — Annuler »,
 * recherche tolérante aux fautes, formats de date et de coordonnées.
 */

// -------------------------------------------------------------------------------------------
// Enregistrement automatique
// -------------------------------------------------------------------------------------------

/**
 * Enregistre une modification 300 ms après la dernière frappe (une file par champ, `key`).
 * L'écriture tourne dans `AppGraph.appScope` : quitter l'écran juste après une frappe
 * n'empêche pas l'enregistrement. `onSaved` est rappelé sur le fil principal.
 * À n'appeler que depuis le fil principal (interface).
 */
class AutoSaver(private val onSaved: () -> Unit) {
    private val jobs = HashMap<String, Job>()

    fun save(key: String, block: suspend () -> Unit) {
        jobs[key]?.cancel()
        jobs[key] = AppGraph.appScope.launch {
            delay(300)
            block()
            withContext(Dispatchers.Main) { onSaved() }
        }
    }

    /** Enregistrement immédiat (pastilles, étoiles : pas de frappe à attendre). */
    fun now(block: suspend () -> Unit) {
        AppGraph.appScope.launch {
            block()
            withContext(Dispatchers.Main) { onSaved() }
        }
    }
}

/** Action annulable affichée dans le bandeau « Supprimé — Annuler » (10 s). */
data class UndoAction(val label: String, val undo: suspend () -> Unit)

/** Bandeau bas « Supprimé — Annuler ». */
@Composable
fun UndoBanner(action: UndoAction, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                champiText(action.label),
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.weight(1f).padding(vertical = 14.dp),
            )
            TextButton(onClick = onUndo) {
                Text("Annuler", color = MaterialTheme.colorScheme.inversePrimary)
            }
        }
    }
}

// -------------------------------------------------------------------------------------------
// Pastilles
// -------------------------------------------------------------------------------------------

/** Pastille sélectionnable (statut, stade, qualité…). Sélectionnée = couleur principale. */
@Composable
fun SelectChip(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier.clip(RoundedCornerShape(15.dp)).clickable(enabled = enabled, onClick = onClick),
    ) { ChampiChip(text, background = bg, textColor = fg) }
}

/** Libellé de comestibilité. */
fun edibilityLabel(e: Edibility): String = when (e) {
    Edibility.COMESTIBLE -> "Comestible"
    Edibility.A_EVITER -> "À éviter"
    Edibility.TOXIQUE -> "Toxique"
    Edibility.MORTEL -> "Mortel"
    Edibility.PSYCHOTROPE -> "Psychotrope"
    Edibility.INCONNU -> "Inconnu"
}

/** Couleurs (fond, texte) de la pastille de comestibilité, identiques dans tous les thèmes. */
private fun edibilityColors(e: Edibility): Pair<Color, Color> = when (e) {
    Edibility.COMESTIBLE -> Color(0xFFDDEFE2) to Color(0xFF1E5A33)
    Edibility.A_EVITER -> Color(0xFFFCEBD6) to Color(0xFF7A3E06)
    Edibility.TOXIQUE -> Color(0xFFFADCD9) to Color(0xFF8C1D18)
    Edibility.MORTEL -> Color(0xFF8C1D18) to Color.White
    Edibility.PSYCHOTROPE -> Color(0xFFEBDDFB) to Color(0xFF4F2A86)
    Edibility.INCONNU -> Color(0xFFE6E6E6) to Color(0xFF444444)
}

/** Pastille Comestible / À éviter / Toxique / Mortel / Psychotrope. */
@Composable
fun EdibilityChip(e: Edibility) {
    val (bg, fg) = edibilityColors(e)
    ChampiChip(edibilityLabel(e), background = bg, textColor = fg)
}

/** Couleur d'une espèce (épingles, pastilles), repli sur le brun par défaut. */
fun speciesColor(s: Species?): Color =
    runCatching { Color(android.graphics.Color.parseColor(s?.colorHex ?: "#9A6A42")) }.getOrDefault(Color(0xFF9A6A42))

/** Petit disque coloré de l'espèce. */
@Composable
fun SpeciesDot(s: Species?, size: Int = 14) {
    Box(Modifier.size(size.dp).background(speciesColor(s), CircleShape))
}

// -------------------------------------------------------------------------------------------
// Recherche tolérante aux fautes
// -------------------------------------------------------------------------------------------

/** Minuscules, sans accents ni ponctuation (« Cèpe de Bordeaux » → « cepe de bordeaux »). */
fun normalize(text: String): String =
    Normalizer.normalize(text.lowercase(Locale.FRENCH), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex(" +"), " ")
        .trim()

/** Distance de Levenshtein (nombre de lettres à changer / ajouter / retirer). */
private fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var prev = IntArray(b.length + 1) { it }
    var cur = IntArray(b.length + 1)
    for (i in 1..a.length) {
        cur[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
        }
        val t = prev; prev = cur; cur = t
    }
    return prev[b.length]
}

/**
 * Score de correspondance d'un nom avec la recherche (plus petit = meilleur, null = pas trouvé).
 * 0 : le nom commence par la recherche ; 1 : un mot commence par elle ; 2 : contenue ;
 * 3+ : début d'un mot proche à quelques fautes près (1 faute par 4 lettres tapées).
 */
private fun matchScore(name: String, query: String): Int? {
    val n = normalize(name)
    if (n.startsWith(query)) return 0
    val words = n.split(' ')
    if (words.any { it.startsWith(query) }) return 1
    if (n.contains(query)) return 2
    val allowed = query.length / 4
    if (allowed == 0) return null
    // Chaque mot de la recherche doit ressembler au début d'un mot du nom.
    var total = 0
    for (q in query.split(' ')) {
        // On compare au début du mot, de même longueur (± 1 lettre oubliée ou en trop).
        val best = words.minOfOrNull { w ->
            minOf(levenshtein(q, w.take(q.length)), levenshtein(q, w.take(q.length + 1)), levenshtein(q, w.take(maxOf(1, q.length - 1))))
        } ?: return null
        if (best > maxOf(1, q.length / 4)) return null
        total += best
    }
    return if (total <= allowed + 1) 3 + total else null
}

/** Filtre et trie les espèces selon la recherche (nom courant, latin ou autres noms). */
fun searchSpecies(all: List<Species>, rawQuery: String): List<Species> {
    val q = normalize(rawQuery)
    if (q.isEmpty()) return all
    return all.mapNotNull { s ->
        val names = listOfNotNull(s.nameFr, s.nameLatin) + (s.otherNames?.split(',', ';') ?: emptyList())
        names.mapNotNull { matchScore(it, q) }.minOrNull()?.let { s to it }
    }.sortedWith(compareBy({ it.second }, { it.first.nameFr })).map { it.first }
}

// -------------------------------------------------------------------------------------------
// Formats
// -------------------------------------------------------------------------------------------

/** « 26/10/2026 à 14 h 05 ». */
fun formatDateTime(time: Long): String =
    SimpleDateFormat("dd/MM/yyyy 'à' H 'h' mm", Locale.FRENCH).format(Date(time))

/** « 26 oct. 2026 ». */
fun formatDate(time: Long): String = SimpleDateFormat("d MMM yyyy", Locale.FRENCH).format(Date(time))

/** Coordonnées au format « 45,83412 ; 1,26034 ». */
fun formatCoords(lat: Double, lon: Double): String =
    String.format(Locale.FRENCH, "%.5f ; %.5f", lat, lon)

/** Distance lisible : « 12 m » ou « 1,2 km ». */
fun formatDistance(m: Float): String =
    if (m < 1000f) "${m.toInt()} m" else String.format(Locale.FRENCH, "%.1f km", m / 1000f)

/** Noms des mois, pour la saison d'une espèce. */
private val MONTHS = listOf(
    "janvier", "février", "mars", "avril", "mai", "juin",
    "juillet", "août", "septembre", "octobre", "novembre", "décembre",
)

/** « 9,10,11 » → « septembre, octobre, novembre ». */
fun formatMonths(months: String?): String? =
    months?.split(',')?.mapNotNull { it.trim().toIntOrNull()?.takeIf { m -> m in 1..12 } }
        ?.takeIf { it.isNotEmpty() }
        ?.joinToString(", ") { MONTHS[it - 1] }

/** Bandeau d'information temporaire (sans action). */
@Composable
fun InfoBanner(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        shadowElevation = 6.dp,
    ) {
        Text(
            champiText(text),
            color = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        )
    }
}

/** Espacement horizontal standard entre boutons d'une même ligne. */
val ROW_GAP = Arrangement.spacedBy(10.dp)
