package fr.pushpull.champimap.feature.connect

import android.content.Context
import fr.pushpull.champimap.core.ErrorLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/*
 * Liste des versions publiées du modèle d'identification, reçue de Champimap Connect (ia_versions.json,
 * Connect 3 et plus) à chaque synchro qui inclut le modèle, ou sur demande (« Actualiser la liste »).
 * Gardée dans filesDir/ia_versions.json : le menu de choix de version reste rempli hors ligne.
 * Format : tableau d'objets { version, especes?, top1?, top5?, tailleModeleMo?, size?, date? },
 * de la plus récente à la plus ancienne.
 */

/** Une version publiée du modèle, avec ses statistiques (toutes facultatives). */
data class IaPublishedVersion(
    val version: String,
    val especes: Int? = null,
    val top1: Double? = null,
    val tailleModeleMo: Double? = null,
) {
    /** « ia-v1 · 1 032 espèces · 73,8 % · 35 Mo » (statistiques inconnues omises). */
    fun summary(): String = buildList {
        add(version)
        especes?.let { add("${String.format(Locale.FRANCE, "%,d", it).replace(' ', ' ').replace(' ', ' ')} espèces") }
        top1?.let { add(String.format(Locale.FRANCE, "%.1f %%", it)) }
        tailleModeleMo?.let { add(String.format(Locale.FRANCE, "%.0f Mo", it)) }
    }.joinToString(" · ")
}

object IaVersions {
    private const val FILE = "ia_versions.json"

    /** Garde-fou : une liste raisonnable (quelques centaines de versions au plus). */
    private const val MAX_VERSIONS = 500

    private val _list = MutableStateFlow<List<IaPublishedVersion>?>(null)

    /** Versions connues (null = pas encore lues ; vide = jamais reçues). */
    val list: StateFlow<List<IaPublishedVersion>?> = _list.asStateFlow()

    /** Lit la liste gardée sur le téléphone (une seule fois). */
    suspend fun load(context: Context) {
        if (_list.value != null) return
        val text = withContext(Dispatchers.IO) {
            runCatching { File(context.applicationContext.filesDir, FILE).takeIf { it.isFile }?.readText() }.getOrNull()
        }
        _list.value = text?.let { parse(it) } ?: emptyList()
    }

    /** Enregistre la liste reçue de Connect. Faux si elle est illisible (rien n'est remplacé). */
    suspend fun save(context: Context, text: String): Boolean {
        val parsed = parse(text) ?: return false
        withContext(Dispatchers.IO) {
            // Fichier temporaire puis renommage : jamais de liste à moitié écrite.
            val dir = context.applicationContext.filesDir
            val tmp = File(dir, "$FILE.tmp")
            tmp.writeText(text)
            val target = File(dir, FILE)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) ErrorLogger.log("IA", "Enregistrement de la liste des versions impossible")
        }
        _list.value = parsed
        return true
    }

    /** Décode ia_versions.json (null si illisible). Versions invalides ignorées, plus récente d'abord. */
    private fun parse(text: String): List<IaPublishedVersion>? = runCatching {
        ConnectProtocol.json.parseToJsonElement(text).objectList("versions")
            .mapNotNull { o ->
                val v = o.str("version")?.takeIf { ConnectProtocol.IA_VERSION_REGEX.matches(it) } ?: return@mapNotNull null
                IaPublishedVersion(
                    version = v,
                    especes = o.int("especes")?.takeIf { it > 0 },
                    top1 = o.dbl("top1"),
                    // Taille du modèle publiée, sinon taille de l'archive.
                    tailleModeleMo = o.dbl("tailleModeleMo") ?: o.long("size")?.takeIf { it > 0 }?.let { it / 1048576.0 },
                )
            }
            .distinctBy { it.version }
            .sortedByDescending { number(it.version) }
            .take(MAX_VERSIONS)
    }.onFailure { ErrorLogger.log("IA", "Liste des versions illisible", it) }.getOrNull()

    /** N de « ia-vN » (0 si invalide). */
    private fun number(v: String): Int = ConnectProtocol.IA_VERSION_REGEX.matchEntire(v)?.groupValues?.get(1)?.toIntOrNull() ?: 0
}
