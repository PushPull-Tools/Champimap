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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/*
 * Photos des fiches espèces (1 à 3 par espèce), cherchées dans cet ordre :
 *  1. assets/ia/fiches.json + assets/ia/fiches/ (livrées avec le modèle d'IA, ajoutées par la CI) ;
 *  2. filesDir/fiches.json + filesDir/fiches/ (photos livrées plus tard dans le dossier privé).
 * fiches.json : { "<id espèce>": [ { "fichier": "...", "licence": "...", "credit": "..." }, … ] }
 * Chaque fiches.json est lu une seule fois puis gardé en mémoire. Sans photo : aucun affichage.
 */

/**
 * Une photo de fiche, avec sa licence et son crédit (affichés en petit).
 * `model` est ce que Coil affiche : un File, ou une adresse « file:///android_asset/… ».
 */
class FichePhoto(val model: Any, val licence: String?, val credit: String?)

/** Nombre maximal de photos par fiche. */
private const val MAX_PHOTOS = 3

/** Adresse Coil des fichiers d'assets. */
private const val ASSET_URI = "file:///android_asset/"

/** Index des photos lu une fois : fiches.json décodé et liste des fichiers présents. */
private class FicheIndex(val json: JSONObject, val files: Set<String>)

@Volatile private var assetIndex: FicheIndex? = null
@Volatile private var assetIndexLoaded = false

/** Texte d'un champ JSON, null si absent, vide ou « null ». */
private fun JSONObject.text(name: String): String? =
    optString(name, "").trim().takeIf { it.isNotEmpty() && it != "null" }

/** Index des photos livrées dans les assets (null si absentes). Lu une seule fois. */
private fun assetFiches(context: Context): FicheIndex? {
    if (assetIndexLoaded) return assetIndex
    synchronized(FicheIndex::class.java) {
        if (!assetIndexLoaded) {
            assetIndex = try {
                IaAssets.readText(context, IaAssets.FICHES_JSON)?.let { text ->
                    val files = context.assets.list(IaAssets.FICHES_DIR)?.toSet() ?: emptySet()
                    FicheIndex(JSONObject(text), files)
                }
            } catch (e: Exception) {
                ErrorLogger.log("Espèces", "Lecture des photos de fiches (assets) impossible", e)
                null
            }
            assetIndexLoaded = true
        }
    }
    return assetIndex
}

/** Noms de fichier candidats pour la photo n° i (nom donné, sinon <id>_<n>.webp). */
private fun candidates(o: JSONObject, speciesId: String, i: Int): List<String> = listOfNotNull(
    // File(nom).name : jamais de chemin en dehors du dossier fiches/.
    o.text("fichier")?.let { File(it).name },
    "${speciesId}_${i + 1}.webp",
    "${speciesId}_$i.webp",
)

/** Photos d'une espèce dans un tableau fiches.json, `resolve` donnant le modèle Coil d'un nom présent. */
private fun photosFrom(arr: JSONArray, speciesId: String, resolve: (String) -> Any?): List<FichePhoto> =
    (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val model = candidates(o, speciesId, i).firstNotNullOfOrNull(resolve) ?: return@mapNotNull null
        FichePhoto(model, o.text("licence"), o.text("credit"))
    }.take(MAX_PHOTOS)

/**
 * Lit les photos d'une espèce (à appeler hors du fil principal) : assets d'abord, puis dossier privé.
 * Une photo sans fichier présent est ignorée ; toute erreur de lecture donne une liste vide.
 */
fun loadFichePhotos(context: Context, speciesId: String): List<FichePhoto> {
    // 1. Photos livrées avec le modèle (assets/ia/fiches/).
    assetFiches(context)?.let { idx ->
        val arr = idx.json.optJSONArray(speciesId)
        if (arr != null) {
            val list = photosFrom(arr, speciesId) { name ->
                if (name in idx.files) "$ASSET_URI${IaAssets.FICHES_DIR}/$name" else null
            }
            if (list.isNotEmpty()) return list
        }
    }
    // 2. Photos du dossier privé (filesDir/fiches/).
    val json = File(context.filesDir, "fiches.json")
    if (!json.exists()) return emptyList()
    val dir = File(context.filesDir, "fiches")
    return try {
        val arr = JSONObject(json.readText()).optJSONArray(speciesId) ?: return emptyList()
        photosFrom(arr, speciesId) { name -> File(dir, name).takeIf { it.exists() } }
    } catch (e: Exception) {
        ErrorLogger.log("Espèces", "Lecture de fiches.json impossible", e)
        emptyList()
    }
}

/** Nombre total de photos de fiches livrées dans les assets (0 si aucune). */
fun countAssetFichePhotos(context: Context): Int = assetFiches(context)?.files?.size ?: 0

/**
 * Crédit court d'une photo : « © Auteur · CC BY » ou « Domaine public (CC0) ».
 * Les crédits iNaturalist ont la forme « (c) Nom, some rights reserved (CC BY) ».
 */
fun formatPhotoCredit(credit: String?, licence: String?): String {
    val lic = licence?.trim()?.lowercase()
    if (lic == "cc0") return "Domaine public (CC0)"
    val author = credit?.let { authorFromCredit(it) }
    val licText = lic?.uppercase()?.replace('-', ' ')
    return listOfNotNull(author?.let { "© $it" }, licText).joinToString(" · ")
}

/** Nom de l'auteur dans un crédit iNaturalist (null si « no rights reserved » ou vide). */
fun authorFromCredit(credit: String): String? {
    val c = credit.trim()
    if (c.isEmpty() || c.equals("no rights reserved", ignoreCase = true)) return null
    val noPrefix = c.removePrefix("(c)").removePrefix("(C)").removePrefix("©").trim()
    return noPrefix.substringBefore(", some rights reserved").substringBefore(", all rights reserved").trim()
        .takeIf { it.isNotEmpty() }
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
                model = current.model,
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
        val credit = formatPhotoCredit(current.credit, current.licence)
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
