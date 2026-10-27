package fr.pushpull.champimap.feature.spot

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import fr.pushpull.champimap.core.ErrorLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/*
 * Photos des fiches espèces (1 à 3 par espèce), livrées plus tard dans le dossier privé :
 *   filesDir/fiches/<id>_<n>.webp  +  filesDir/fiches.json
 * fiches.json : { "<id espèce>": [ { "fichier": "...", "licence": "...", "credit": "..." }, … ] }
 * Tant que ces fichiers n'existent pas, la fiche garde son affichage actuel (aucune photo).
 */

/** Une photo de fiche, avec sa licence et son crédit (affichés en petit). */
class FichePhoto(val file: File, val licence: String?, val credit: String?)

/** Nombre maximal de photos par fiche. */
private const val MAX_PHOTOS = 3

/** Texte d'un champ JSON, null si absent, vide ou « null ». */
private fun JSONObject.text(name: String): String? =
    optString(name, "").trim().takeIf { it.isNotEmpty() && it != "null" }

/**
 * Lit les photos d'une espèce (sur le disque : à appeler hors du fil principal).
 * Le fichier est cherché sous le nom donné par « fichier », sinon <id>_<n>.webp (n à partir de 1, puis 0).
 * Une photo sans fichier présent est ignorée ; toute erreur de lecture donne une liste vide.
 */
fun loadFichePhotos(context: Context, speciesId: String): List<FichePhoto> {
    val json = File(context.filesDir, "fiches.json")
    if (!json.exists()) return emptyList()
    val dir = File(context.filesDir, "fiches")
    return try {
        val arr = JSONObject(json.readText()).optJSONArray(speciesId) ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            // File(nom).name : jamais de chemin en dehors du dossier fiches/.
            val candidates = listOfNotNull(
                o.text("fichier")?.let { File(it).name },
                "${speciesId}_${i + 1}.webp",
                "${speciesId}_$i.webp",
            )
            val file = candidates.map { File(dir, it) }.firstOrNull { it.exists() } ?: return@mapNotNull null
            FichePhoto(file, o.text("licence"), o.text("credit"))
        }.take(MAX_PHOTOS)
    } catch (e: Exception) {
        ErrorLogger.log("Espèces", "Lecture de fiches.json impossible", e)
        emptyList()
    }
}

/**
 * Carrousel des photos d'une fiche (rien n'est affiché s'il n'y en a pas) :
 * appui sur la photo = photo suivante, points de position, crédit et licence en petit dessous.
 */
@Composable
fun FichePhotosCarousel(speciesId: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val photos by produceState(initialValue = emptyList<FichePhoto>(), speciesId) {
        value = withContext(Dispatchers.IO) { loadFichePhotos(context, speciesId) }
    }
    if (photos.isEmpty()) return
    var page by remember(speciesId) { mutableIntStateOf(0) }
    val current = photos[page.coerceIn(0, photos.size - 1)]

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(enabled = photos.size > 1) { page = (page + 1) % photos.size },
        ) {
            AsyncImage(
                model = current.file,
                contentDescription = "Photo de l'espèce",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // Points de position (seulement s'il y a plusieurs photos).
            if (photos.size > 1) {
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(8.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    photos.indices.forEach { i ->
                        Box(
                            Modifier.size(8.dp).background(
                                if (i == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                CircleShape,
                            )
                        )
                    }
                }
            }
        }
        val credit = listOfNotNull(current.credit?.let { "© $it" }, current.licence).joinToString(" · ")
        if (credit.isNotEmpty()) {
            Text(
                credit,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}
