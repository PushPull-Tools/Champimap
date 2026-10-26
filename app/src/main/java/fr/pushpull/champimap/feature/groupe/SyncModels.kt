package fr.pushpull.champimap.feature.groupe

import fr.pushpull.champimap.core.db.DepartType
import fr.pushpull.champimap.core.db.Person
import fr.pushpull.champimap.core.db.Photo
import fr.pushpull.champimap.core.db.PhotoOwner
import fr.pushpull.champimap.core.db.PositionSource
import fr.pushpull.champimap.core.db.Recolte
import fr.pushpull.champimap.core.db.RecolteItem
import fr.pushpull.champimap.core.db.Sortie
import fr.pushpull.champimap.core.db.Spot
import fr.pushpull.champimap.core.db.SpotPosition
import fr.pushpull.champimap.core.db.SpotStatus
import fr.pushpull.champimap.core.db.SpotVisit
import fr.pushpull.champimap.core.db.TrackPoint
import kotlinx.serialization.Serializable

/*
 * Modèles échangés entre téléphones (sortie à plusieurs) et contenus dans un spot partagé.
 *
 * Les entités Room (core/db/Entities.kt) ne sont pas sérialisables : chaque entité a ici
 * son « DTO » (même champs, énumérations en texte) et deux conversions toDto() / toEntity().
 * Les noms de champs JSON sont courts mais lisibles ; `ignoreUnknownKeys` permet d'ajouter
 * des champs plus tard sans casser la compatibilité entre deux versions de l'appli.
 */

/** Lit une énumération par son nom, avec une valeur de repli si le nom est inconnu. */
private inline fun <reified E : Enum<E>> enumOr(name: String, default: E): E =
    runCatching { enumValueOf<E>(name) }.getOrDefault(default)

// ------------------------------------------------------------------------------- Person

@Serializable
data class PersonDto(val id: String, val name: String, val updatedAt: Long)

/** Une personne envoyée n'est jamais « moi » chez le destinataire : isMe est toujours faux. */
fun Person.toDto() = PersonDto(id, name, updatedAt)
fun PersonDto.toEntity() = Person(id = id, name = name, isMe = false, updatedAt = updatedAt)

// ------------------------------------------------------------------------------- Sortie

@Serializable
data class SortieDto(
    val id: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val departType: String,
    val startLat: Double? = null,
    val startLon: Double? = null,
    val placeName: String? = null,
    val groupId: String? = null,
    val pictoKey: String? = null,
    val markerStyle: String = "COLORS",
    val authorId: String,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

fun Sortie.toDto() = SortieDto(
    id, startedAt, endedAt, departType.name, startLat, startLon, placeName,
    groupId, pictoKey, markerStyle, authorId, updatedAt, deletedAt,
)

fun SortieDto.toEntity() = Sortie(
    id = id, startedAt = startedAt, endedAt = endedAt,
    departType = enumOr(departType, DepartType.FOOT),
    startLat = startLat, startLon = startLon, placeName = placeName, groupId = groupId,
    pictoKey = pictoKey, markerStyle = markerStyle, authorId = authorId,
    updatedAt = updatedAt, deletedAt = deletedAt,
)

// ------------------------------------------------------------------------------- Spot

@Serializable
data class SpotDto(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float? = null,
    val essence: String? = null,
    val rating: Int? = null,
    val status: String = "ACTIF",
    val repere: String? = null,
    val note: String? = null,
    val placedManually: Boolean = false,
    val authorId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

/** Le mémo vocal (fichier local) et l'option « coordonnées masquées » ne voyagent pas. */
fun Spot.toDto() = SpotDto(
    id, name, lat, lon, accuracyM, essence, rating, status.name, repere, note,
    placedManually, authorId, createdAt, updatedAt, deletedAt,
)

/** `local` = version déjà présente sur ce téléphone (on garde ses réglages purement locaux). */
fun SpotDto.toEntity(local: Spot?) = Spot(
    id = id, name = name, lat = lat, lon = lon, accuracyM = accuracyM,
    coordsHidden = local?.coordsHidden ?: true,
    essence = essence, rating = rating, status = enumOr(status, SpotStatus.ACTIF),
    repere = repere, note = note, voiceMemoPath = local?.voiceMemoPath,
    placedManually = placedManually, authorId = authorId, createdAt = createdAt,
    updatedAt = updatedAt, deletedAt = deletedAt,
)

// ------------------------------------------------------------------------------- SpotPosition

@Serializable
data class PositionDto(
    val id: String,
    val spotId: String,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float? = null,
    val source: String,
    val time: Long,
    val active: Boolean = false,
    val authorId: String,
)

fun SpotPosition.toDto() = PositionDto(id, spotId, lat, lon, accuracyM, source.name, time, active, authorId)
fun PositionDto.toEntity() = SpotPosition(
    id = id, spotId = spotId, lat = lat, lon = lon, accuracyM = accuracyM,
    source = enumOr(source, PositionSource.IMPORT), time = time, active = active, authorId = authorId,
)

// ------------------------------------------------------------------------------- SpotVisit

@Serializable
data class VisitDto(
    val id: String,
    val spotId: String,
    val sortieId: String,
    val time: Long,
    val empty: Boolean = false,
    val authorId: String,
    val updatedAt: Long,
)

fun SpotVisit.toDto() = VisitDto(id, spotId, sortieId, time, empty, authorId, updatedAt)
fun VisitDto.toEntity() = SpotVisit(id, spotId, sortieId, time, empty, authorId, updatedAt)

// ------------------------------------------------------------------------------- Recolte

@Serializable
data class RecolteDto(
    val id: String,
    val spotId: String,
    val sortieId: String,
    val time: Long,
    val locked: Boolean = false,
    val authorId: String,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

fun Recolte.toDto() = RecolteDto(id, spotId, sortieId, time, locked, authorId, updatedAt, deletedAt)
fun RecolteDto.toEntity() = Recolte(id, spotId, sortieId, time, locked, authorId, updatedAt, deletedAt)

// ------------------------------------------------------------------------------- RecolteItem

@Serializable
data class ItemDto(
    val id: String,
    val recolteId: String,
    val speciesId: String,
    val weightG: Int? = null,
    val stade: String? = null,
    val qualites: String? = null,
    val etats: String? = null,
    val authorId: String,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

fun RecolteItem.toDto() = ItemDto(id, recolteId, speciesId, weightG, stade, qualites, etats, authorId, updatedAt, deletedAt)
fun ItemDto.toEntity() = RecolteItem(id, recolteId, speciesId, weightG, stade, qualites, etats, authorId, updatedAt, deletedAt)

// ------------------------------------------------------------------------------- Photo

/** Métadonnées d'une photo ; le fichier voyage à part (sortie à plusieurs) ou dans le ZIP (.champi). */
@Serializable
data class PhotoDto(
    val id: String,
    val ownerType: String,
    val ownerId: String,
    val createdAt: Long,
    val authorId: String,
    val deletedAt: Long? = null,
)

fun Photo.toDto() = PhotoDto(id, ownerType.name, ownerId, createdAt, authorId, deletedAt)

/** `path` = emplacement LOCAL du fichier (le chemin de l'autre téléphone n'a pas de sens ici). */
fun PhotoDto.toEntity(path: String) = Photo(
    id = id, ownerType = enumOr(ownerType, PhotoOwner.SPOT), ownerId = ownerId,
    path = path, createdAt = createdAt, authorId = authorId, deletedAt = deletedAt,
)

// ------------------------------------------------------------------------------- TrackPoint

@Serializable
data class TrackDto(
    val id: String,
    val sortieId: String,
    val authorId: String,
    val time: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
)

fun TrackPoint.toDto() = TrackDto(id, sortieId, authorId, time, lat, lon, accuracyM)
fun TrackDto.toEntity() = TrackPoint(id, sortieId, authorId, time, lat, lon, accuracyM)

// ------------------------------------------------------------------------------- Lot de données

/**
 * Un lot de données à fusionner : changements d'une synchro, ou contenu d'un spot partagé.
 * Toutes les listes sont facultatives (vides par défaut).
 */
@Serializable
data class SyncBundle(
    val persons: List<PersonDto> = emptyList(),
    val sorties: List<SortieDto> = emptyList(),
    val spots: List<SpotDto> = emptyList(),
    val positions: List<PositionDto> = emptyList(),
    val visits: List<VisitDto> = emptyList(),
    val recoltes: List<RecolteDto> = emptyList(),
    val items: List<ItemDto> = emptyList(),
    val photos: List<PhotoDto> = emptyList(),
    val track: List<TrackDto> = emptyList(),
) {
    /** Nombre total d'éléments (pour savoir s'il y a eu du nouveau). */
    val size: Int
        get() = persons.size + sorties.size + spots.size + positions.size + visits.size +
            recoltes.size + items.size + photos.size + track.size
}

/**
 * Résumé de ce qu'un téléphone possède déjà, envoyé au début de chaque échange :
 * l'autre ne renvoie que ce qui manque ou ce qui est plus récent (= les changements
 * depuis le dernier échange, sans avoir à mémoriser d'horodatage de synchro).
 *
 * @param versions clé « type:id » → `updatedAt` (0 pour les éléments immuables : positions ;
 *   pour les photos : `deletedAt` ou 0).
 * @param trackMax identifiant d'auteur → instant du dernier point de trace connu.
 * @param photoFiles identifiants des photos dont le FICHIER est présent sur ce téléphone.
 */
@Serializable
data class SyncSummary(
    val versions: Map<String, Long> = emptyMap(),
    val trackMax: Map<String, Long> = emptyMap(),
    val photoFiles: List<String> = emptyList(),
)
