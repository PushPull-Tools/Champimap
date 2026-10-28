package fr.pushpull.champimap.feature.spot

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.ErrorLogger
import fr.pushpull.champimap.core.db.Edibility
import fr.pushpull.champimap.core.db.Species
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.FileNotFoundException

/*
 * Chargement du catalogue des espèces dans la table `species` :
 *  1. assets/species.json (fiches complètes) : copié UNIQUEMENT si la table est vide ;
 *  2. assets/ia/especes_ia.json (espèces connues du modèle d'IA) : ajoutées sans rien écraser
 *     (« insert ignore »), comestibilité non renseignée, genres masqués exclus (lichens, rouilles…).
 * L'étape 2 est rejouée à chaque démarrage où la VERSION du catalogue change (numéro + empreinte
 * du fichier du modèle, mémorisés dans un petit DataStore propre au module) : idempotente, sans
 * changement de schéma Room. Appelé au démarrage (ChampimapApp) et à l'ouverture des listes.
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

/** Petit stockage du module spot : version du catalogue déjà chargée. */
private val Context.speciesCatalogStore: DataStore<Preferences> by preferencesDataStore(name = "species_catalog")

/** Charge la liste des espèces dans la base (premier lancement, puis à chaque nouvelle version du catalogue). */
object SpeciesLoader {
    private const val ASSET = "species.json"

    /** À augmenter si la règle d'ajout change (ex. nouveaux genres masqués). */
    private const val CATALOG_VERSION = 2

    private val KEY_VERSION = stringPreferencesKey("catalogVersion")
    private val mutex = Mutex() // évite deux chargements simultanés (démarrage + catalogue)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Vrai une fois le travail fait pendant ce lancement de l'appli (appels suivants immédiats). */
    @Volatile private var done = false

    suspend fun ensureLoaded(context: Context) {
        if (done) return
        withContext(Dispatchers.IO) { load(context.applicationContext) }
    }

    /** Lecture des fichiers et insertion en base (sur le fil d'entrées-sorties). */
    private suspend fun load(context: Context) {
        mutex.withLock {
            if (done) return@withLock
            val dao = AppGraph.db.speciesDao()
            if (dao.count() == 0) loadBaseCatalog(context)

            // Espèces du modèle : seulement si la version a changé depuis le dernier chargement.
            val iaText = IaAssets.readText(context, IaAssets.SPECIES)
            val version = "$CATALOG_VERSION:" + (iaText?.hashCode()?.toString() ?: "sans-ia")
            val store = context.speciesCatalogStore
            val stored = runCatching { store.data.first()[KEY_VERSION] }.getOrNull()
            if (stored != version) {
                val ok = iaText == null || addIaSpecies(iaText)
                if (ok) runCatching { store.edit { it[KEY_VERSION] = version } }
            }
            // Catalogue toujours vide (fichier illisible) : on réessaiera au prochain appel.
            done = dao.count() > 0
        }
    }

    /** Copie species.json dans la base (table vide). */
    private suspend fun loadBaseCatalog(context: Context) {
        try {
            val text = context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val list = json.decodeFromString(ListSerializer(SpeciesJson.serializer()), text).map { it.toEntity() }
            AppGraph.db.speciesDao().upsertAll(list)
        } catch (e: FileNotFoundException) {
            ErrorLogger.log("Espèces", "Fichier $ASSET absent des assets : catalogue vide", e)
        } catch (e: Exception) {
            ErrorLogger.log("Espèces", "Lecture de $ASSET impossible", e)
        }
    }

    /** Ajoute les espèces du modèle absentes du catalogue (sans écraser). Vrai si tout s'est bien passé. */
    private suspend fun addIaSpecies(text: String): Boolean = try {
        val list = json.decodeFromString(ListSerializer(IaSpeciesJson.serializer()), text).toCatalogAdditions()
        if (list.isNotEmpty()) AppGraph.db.speciesDao().insertIgnore(list)
        true
    } catch (e: Exception) {
        ErrorLogger.log("Espèces", "Ajout des espèces du modèle d'IA impossible", e)
        false
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
