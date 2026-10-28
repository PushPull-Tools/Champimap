package fr.pushpull.champimap.feature.spot

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.pushpull.champimap.core.ErrorLogger
import fr.pushpull.champimap.core.db.Edibility
import fr.pushpull.champimap.core.db.Species
import fr.pushpull.champimap.feature.bonus.champiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * « Idées recettes » de la fiche espèce : 3 idées courtes (titre + une ligne) par espèce COMESTIBLE.
 * Pas de recette détaillée : juste de quoi donner envie et orienter.
 * Données : assets/recettes.json, chargé à la première demande, hors du fil principal, puis gardé en mémoire.
 *  - « especes » : idées propres à une espèce (clé = identifiant de species.json) ;
 *  - « genres »  : idées par genre (clé = début de l'identifiant, ex. « suillus ») ;
 *  - « defaut »  : idées génériques, pour qu'aucune espèce comestible ne reste sans idée.
 * Les espèces non comestibles (à éviter, toxiques, mortelles, psychotropes, inconnues) n'ont JAMAIS d'idées.
 */

/** Une idée de recette : titre et une ligne (ingrédients principaux, méthode). */
@Serializable
data class IdeeRecette(val titre: String, val ligne: String)

/** Contenu de recettes.json. */
@Serializable
internal data class RecettesJson(
    val especes: Map<String, List<IdeeRecette>> = emptyMap(),
    val genres: Map<String, List<IdeeRecette>> = emptyMap(),
    val defaut: List<IdeeRecette> = emptyList(),
)

/** Ligne fixe en bas de la section. */
const val RAPPEL_CUISSON = "Toujours bien cuire les champignons sauvages."

/** Chargement paresseux et choix des idées d'une espèce. */
object Recettes {
    private const val ASSET = "recettes.json"

    /** Nombre d'idées affichées par espèce. */
    const val PAR_ESPECE = 3

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    /** Fichier déjà lu (null tant qu'il ne l'est pas). */
    @Volatile private var cache: RecettesJson? = null

    /** Lit recettes.json une seule fois (hors du fil principal). Fichier illisible → aucune idée. */
    private suspend fun load(context: Context): RecettesJson = cache ?: mutex.withLock {
        cache ?: withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(ASSET).bufferedReader().use { json.decodeFromString(RecettesJson.serializer(), it.readText()) }
            }.onFailure { ErrorLogger.log("Recettes", "Lecture de $ASSET impossible", it) }
                .getOrDefault(RecettesJson())
        }.also { cache = it }
    }

    /**
     * Idées pour une espèce : les siennes, sinon celles de son genre, sinon les génériques.
     * Liste vide si l'espèce n'est pas comestible.
     */
    suspend fun pour(context: Context, s: Species): List<IdeeRecette> {
        if (s.edibility != Edibility.COMESTIBLE) return emptyList()
        return choisir(load(context), s.id)
    }

    /** Choix pur (testable) : espèce → genre → défaut, 3 idées au plus. */
    internal fun choisir(r: RecettesJson, speciesId: String): List<IdeeRecette> {
        val genre = speciesId.substringBefore('-')
        val liste = r.especes[speciesId]?.takeIf { it.isNotEmpty() }
            ?: r.genres[genre]?.takeIf { it.isNotEmpty() }
            ?: r.defaut
        return liste.take(PAR_ESPECE)
    }
}

/**
 * Section repliable « Idées recettes » (rien du tout si l'espèce n'est pas comestible
 * ou tant que les idées ne sont pas chargées).
 */
@Composable
fun IdeesRecettes(s: Species, initiallyOpen: Boolean = false) {
    if (s.edibility != Edibility.COMESTIBLE) return
    val context = LocalContext.current
    val idees by produceState(emptyList<IdeeRecette>(), s.id, s.edibility) { value = Recettes.pour(context, s) }
    if (idees.isEmpty()) return
    Collapsible("Idées recettes", initiallyOpen) {
        Column(Modifier.padding(bottom = 8.dp)) {
            idees.forEach { idee ->
                Text(champiText(idee.titre), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    champiText(idee.ligne),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Text(
                champiText(RAPPEL_CUISSON),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
