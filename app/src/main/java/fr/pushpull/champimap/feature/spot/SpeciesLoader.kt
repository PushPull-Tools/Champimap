package fr.pushpull.champimap.feature.spot

import android.content.Context
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.ErrorLogger
import fr.pushpull.champimap.core.db.Edibility
import fr.pushpull.champimap.core.db.Species
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.FileNotFoundException

/*
 * Chargement du catalogue des espèces : lit assets/species.json (tableau d'objets)
 * et le copie dans la table `species`, UNIQUEMENT si elle est vide.
 * Appelé au démarrage (ChampimapApp) et, par sécurité, à l'ouverture du catalogue.
 */

/** Une espèce telle qu'écrite dans species.json (champs facultatifs nullables). */
@Serializable
internal data class SpeciesJson(
    val id: String,
    val nameFr: String,
    val nameLatin: String,
    val otherNames: String? = null,
    val edibility: String? = null,
    val common: Boolean = false,
    val lookalikes: String? = null,
    val lookalikeWarning: String? = null,
    val months: String? = null,
    val habitat: String? = null,
    val description: String? = null,
    val colorHex: String? = null,
)

/** Charge la liste des espèces (assets/species.json) dans la base au premier lancement. */
object SpeciesLoader {
    private const val ASSET = "species.json"
    private val mutex = Mutex() // évite deux chargements simultanés (démarrage + catalogue)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun ensureLoaded(context: Context) {
        withContext(Dispatchers.IO) { load(context) }
    }

    /** Lecture du fichier et insertion en base (sur le fil d'entrées-sorties). */
    private suspend fun load(context: Context) {
        mutex.withLock {
            val dao = AppGraph.db.speciesDao()
            if (dao.count() > 0) return@withLock
            try {
                val text = context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
                val list = json.decodeFromString(ListSerializer(SpeciesJson.serializer()), text).map { it.toEntity() }
                dao.upsertAll(list)
            } catch (e: FileNotFoundException) {
                ErrorLogger.log("Espèces", "Fichier $ASSET absent des assets : catalogue vide", e)
            } catch (e: Exception) {
                ErrorLogger.log("Espèces", "Lecture de $ASSET impossible", e)
            }
        }
    }

    /** Conversion vers l'entité Room (comestibilité inconnue si la valeur est absente ou invalide). */
    private fun SpeciesJson.toEntity() = Species(
        id = id,
        nameFr = nameFr,
        nameLatin = nameLatin,
        otherNames = otherNames,
        edibility = runCatching { Edibility.valueOf(edibility ?: "INCONNU") }.getOrDefault(Edibility.INCONNU),
        common = common,
        lookalikes = lookalikes,
        lookalikeWarning = lookalikeWarning,
        months = months,
        habitat = habitat,
        description = description,
        colorHex = colorHex ?: "#9A6A42",
    )
}
