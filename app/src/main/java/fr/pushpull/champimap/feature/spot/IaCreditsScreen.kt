package fr.pushpull.champimap.feature.spot

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import fr.pushpull.champimap.core.ErrorLogger
import fr.pushpull.champimap.core.ui.ChampiCard
import fr.pushpull.champimap.core.ui.ChampiScreen
import fr.pushpull.champimap.core.ui.SectionTitle
import fr.pushpull.champimap.feature.bonus.champiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.zip.GZIPInputStream

/*
 * « Crédits des photos et du modèle » (Paramètres → Données) :
 *  - modèle d'identification (EfficientNet-B2, poids ImageNet torchvision, BSD-3) et ONNX Runtime (MIT) ;
 *  - photos iNaturalist (CC0 / CC BY) : nombres de photos, d'auteurs, photos des fiches ;
 *  - liste complète des auteurs, lue EN FLUX depuis credits.csv.gz du modèle (ligne par ligne,
 *    seul le décompte par auteur reste en mémoire) puis affichée dans une LazyColumn.
 */

/** Résumé des crédits des photos d'entraînement. */
private class CreditsSummary(
    val total: Int,
    val cc0: Int,
    val ccBy: Int,
    /** Auteurs (nom, nombre de photos), du plus grand nombre au plus petit. */
    val authors: List<Pair<String, Int>>,
)

/** Infos sur le modèle livré (null si l'appli n'a pas de modèle). */
private class ModelInfo(val speciesCount: Int, val accuracy: String?, val fichePhotos: Int)

/** Nombre avec séparateur de milliers français (« 271 653 »). */
private fun fmt(n: Int): String = String.format(Locale.FRENCH, "%,d", n)

/**
 * Découpe une ligne CSV simple (virgules, champs éventuellement entre guillemets, "" = guillemet).
 */
private fun parseCsvLine(line: String): List<String> {
    val out = ArrayList<String>(4)
    val cur = StringBuilder()
    var quoted = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
            c == '"' -> quoted = !quoted
            c == ',' && !quoted -> { out += cur.toString(); cur.setLength(0) }
            else -> cur.append(c)
        }
        i++
    }
    out += cur.toString()
    return out
}

/**
 * Lit credits.csv.gz en flux (colonnes : espece, photo_inaturalist, licence, credit)
 * et compte les photos par licence et par auteur. Null si le fichier est absent ou illisible.
 */
private fun readCredits(context: Context): CreditsSummary? {
    val input = IaAssets.open(context, IaAssets.CREDITS) ?: return null
    return try {
        var total = 0
        var cc0 = 0
        var ccBy = 0
        val byAuthor = HashMap<String, Int>()
        GZIPInputStream(input).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.drop(1).forEach { line ->
                if (line.isBlank()) return@forEach
                val cols = parseCsvLine(line)
                val licence = cols.getOrNull(2)?.trim()?.lowercase().orEmpty()
                total++
                if (licence == "cc0") cc0++ else if (licence.startsWith("cc-by")) ccBy++
                cols.getOrNull(3)?.let { authorFromCredit(it) }?.let { byAuthor[it] = (byAuthor[it] ?: 0) + 1 }
            }
        }
        val authors = byAuthor.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() })
            .map { it.key to it.value }
        CreditsSummary(total, cc0, ccBy, authors)
    } catch (e: Exception) {
        ErrorLogger.log("Crédits", "Lecture des crédits des photos impossible", e)
        null
    }
}

/** Écran des crédits (route CREDITS_IA). */
@Composable
fun IaCreditsScreen(nav: NavController) {
    val context = LocalContext.current
    val model by produceState<ModelInfo?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            IaAssets.labels(context)?.let { ModelInfo(it.species.size, IaAssets.accuracyLine(context), countAssetFichePhotos(context)) }
        }
    }
    // null = lecture en cours ; résultat vide si le fichier est absent.
    val credits by produceState<Result<CreditsSummary?>?>(initialValue = null) {
        value = Result.success(withContext(Dispatchers.IO) { readCredits(context) })
    }

    ChampiScreen(title = "Crédits", onBack = { nav.popBackStack() }) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "modele") { ModelCard(model) }
            item(key = "photos") { PhotosCard(credits?.getOrNull(), loading = credits == null, fichePhotos = model?.fichePhotos ?: 0) }
            val authors = credits?.getOrNull()?.authors.orEmpty()
            if (authors.isNotEmpty()) {
                item(key = "titre-auteurs") { SectionTitle("Auteurs des photos (${fmt(authors.size)})") }
                items(authors, key = { "a-" + it.first }) { (name, count) -> AuthorRow(name, count) }
            }
        }
    }
}

/** Carte « Modèle d'identification ». */
@Composable
private fun ModelCard(info: ModelInfo?) {
    ChampiCard(Modifier.fillMaxWidth()) {
        Text(champiText("Modèle d'identification"), style = MaterialTheme.typography.titleMedium)
        if (info == null) {
            Text(champiText("Aucun modèle d'identification dans cette version de l'appli."), Modifier.padding(top = 6.dp))
            return@ChampiCard
        }
        Text(
            champiText(
                "EfficientNet-B2 : architecture et poids pré-entraînés ImageNet de torchvision (licence BSD-3-Clause), " +
                    "ré-entraîné pour Champimap sur ${fmt(info.speciesCount)} espèces de champignons observées en France."
            ),
            Modifier.padding(top = 6.dp),
        )
        info.accuracy?.let {
            Text(
                champiText("Précision mesurée sur des photos de contrôle : ${it.replace('.', ',')}."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Text(
            champiText("Il fonctionne entièrement sur le téléphone, sans Internet, avec ONNX Runtime (Microsoft, licence MIT)."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Carte « Photos » : origine, licences, nombres. */
@Composable
private fun PhotosCard(summary: CreditsSummary?, loading: Boolean, fichePhotos: Int) {
    ChampiCard(Modifier.fillMaxWidth()) {
        Text(champiText("Photos"), style = MaterialTheme.typography.titleMedium)
        Text(
            champiText("Photos d'observations publiées sur iNaturalist par leurs auteurs, sous licence CC0 (domaine public) ou CC BY (attribution)."),
            Modifier.padding(top = 6.dp),
        )
        when {
            loading -> Text(champiText("Lecture des crédits…"), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            summary == null -> Text(champiText("Liste des crédits absente de cette version."), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            else -> {
                Text(
                    champiText(
                        "Entraînement du modèle : ${fmt(summary.total)} photos (${fmt(summary.ccBy)} en CC BY, ${fmt(summary.cc0)} en CC0), " +
                            "de ${fmt(summary.authors.size)} auteurs nommés."
                    ),
                    Modifier.padding(top = 6.dp),
                )
                if (fichePhotos > 0) {
                    Text(
                        champiText("Fiches espèces : ${fmt(fichePhotos)} photos, avec l'auteur et la licence sous chaque photo."),
                        Modifier.padding(top = 4.dp),
                    )
                }
                Text(
                    champiText("La liste complète des auteurs (crédit de chaque photo) est incluse dans l'appli ; les voici, merci à eux !"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** Ligne d'auteur : nom et nombre de photos. */
@Composable
private fun AuthorRow(name: String, count: Int) {
    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(8.dp))
        Text(
            if (count > 1) "${fmt(count)} photos" else "1 photo",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
