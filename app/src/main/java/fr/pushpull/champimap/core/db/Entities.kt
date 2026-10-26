package fr.pushpull.champimap.core.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Modèle de données de Champimap (base SQLite locale, via Room).
 *
 * Règles communes :
 *  - Tous les identifiants sont des UUID texte : deux téléphones peuvent créer des
 *    données chacun de leur côté et les fusionner sans collision (sortie à plusieurs).
 *  - `updatedAt` (millisecondes) sert à la fusion : la version la plus récente gagne.
 *  - `authorId` = identifiant de la personne qui a saisi la donnée (voir Person).
 *  - `deletedAt` non nul = élément dans la corbeille (purgé après 30 jours).
 *  - Les dates sont des millisecondes depuis l'époque Unix (UTC).
 */

/** Une personne connue de l'appli : soi-même ou un proche rencontré en sortie partagée. */
@Entity(tableName = "person")
data class Person(
    @PrimaryKey val id: String,
    val name: String,
    val isMe: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Façon de démarrer une sortie. */
enum class DepartType { CAR, FOOT }

/**
 * Une sortie : de la voiture (ou du départ à pied) jusqu'au retour.
 * `groupId` non nul = sortie partagée avec d'autres téléphones.
 * `pictoKey` = picto choisi (débloqué par l'easter egg), sinon la date est affichée.
 * `markerStyle` = style des repères sur la carte : COLORS, MUSHROOMS ou TREES (easter egg).
 */
@Entity(tableName = "sortie", indices = [Index("startedAt"), Index("groupId")])
data class Sortie(
    @PrimaryKey val id: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val departType: DepartType,
    val startLat: Double? = null,
    val startLon: Double? = null,
    val placeName: String? = null,
    val groupId: String? = null,
    val pictoKey: String? = null,
    val markerStyle: String = "COLORS",
    val authorId: String,
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null,
)

/** Un point de la trace GPS d'une sortie. */
@Entity(tableName = "track_point", indices = [Index("sortieId"), Index("time")])
data class TrackPoint(
    @PrimaryKey val id: String,
    val sortieId: String,
    val authorId: String,
    val time: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
)

/** Statut d'un spot. */
enum class SpotStatus { ACTIF, EPUISE, DETRUIT, A_EXPLORER }

/**
 * Un spot : un coin à champignons, réutilisable d'une sortie à l'autre.
 * La position affichée est `lat`/`lon` ; tout l'historique est dans SpotPosition.
 */
@Entity(tableName = "spot", indices = [Index("updatedAt")])
data class Spot(
    @PrimaryKey val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float? = null,
    val coordsHidden: Boolean = true,
    val essence: String? = null,
    val rating: Int? = null,
    val status: SpotStatus = SpotStatus.ACTIF,
    val repere: String? = null,
    val note: String? = null,
    val voiceMemoPath: String? = null,
    val placedManually: Boolean = false,
    val authorId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null,
)

/** Origine d'une position de spot (historique, retour arrière possible). */
enum class PositionSource { GPS_PRECIS, AU_PIED, MANUEL, CARTE, IMPORT }

/** Historique des positions d'un spot. `active` = position actuellement utilisée. */
@Entity(tableName = "spot_position", indices = [Index("spotId")])
data class SpotPosition(
    @PrimaryKey val id: String,
    val spotId: String,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float? = null,
    val source: PositionSource,
    val time: Long = System.currentTimeMillis(),
    val active: Boolean = false,
    val authorId: String,
)

/** Passage sur un spot pendant une sortie. `empty` = « Spot vide ». */
@Entity(tableName = "spot_visit", indices = [Index("spotId"), Index("sortieId")])
data class SpotVisit(
    @PrimaryKey val id: String,
    val spotId: String,
    val sortieId: String,
    val time: Long,
    val empty: Boolean = false,
    val authorId: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Une récolte sur un spot, lors d'une sortie. Peut contenir plusieurs espèces. */
@Entity(tableName = "recolte", indices = [Index("spotId"), Index("sortieId")])
data class Recolte(
    @PrimaryKey val id: String,
    val spotId: String,
    val sortieId: String,
    val time: Long,
    val locked: Boolean = false,
    val authorId: String,
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null,
)

/**
 * Une espèce dans une récolte. Le poids est facultatif (pesé plus tard, ou jamais).
 * `stade`, `qualites` et `etats` : listes de mots-clés séparés par des virgules
 * (ex. "BOUCHON", "FERME,VEREUX", "GORGE_EAU").
 */
@Entity(tableName = "recolte_item", indices = [Index("recolteId"), Index("speciesId")])
data class RecolteItem(
    @PrimaryKey val id: String,
    val recolteId: String,
    val speciesId: String,
    val weightG: Int? = null,
    val stade: String? = null,
    val qualites: String? = null,
    val etats: String? = null,
    val authorId: String,
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null,
)

/** À qui appartient une photo. */
enum class PhotoOwner { SPOT, RECOLTE_ITEM }

/** Une photo stockée dans le dossier privé de l'appli. */
@Entity(tableName = "photo", indices = [Index("ownerId")])
data class Photo(
    @PrimaryKey val id: String,
    val ownerType: PhotoOwner,
    val ownerId: String,
    val path: String,
    val createdAt: Long = System.currentTimeMillis(),
    val authorId: String,
    val deletedAt: Long? = null,
)

/** Comestibilité d'une espèce. */
enum class Edibility { COMESTIBLE, A_EVITER, TOXIQUE, MORTEL, PSYCHOTROPE, INCONNU }

/**
 * Une espèce de champignon (chargée depuis assets/species.json au premier lancement).
 * `lookalikes` : identifiants d'espèces sosies (séparés par des virgules).
 * `months` : mois de pousse (1..12, séparés par des virgules).
 */
@Entity(tableName = "species", indices = [Index("nameFr"), Index("nameLatin")])
data class Species(
    @PrimaryKey val id: String,
    val nameFr: String,
    val nameLatin: String,
    val otherNames: String? = null,
    val edibility: Edibility,
    val common: Boolean,
    val lookalikes: String? = null,
    val lookalikeWarning: String? = null,
    val months: String? = null,
    val habitat: String? = null,
    val description: String? = null,
    val colorHex: String = "#9A6A42",
)

/** Météo d'une journée pour une zone large (~25 km). Importée par Champimap Connect. */
@Entity(tableName = "weather_day", primaryKeys = ["zoneId", "date"])
data class WeatherDay(
    val zoneId: String,
    val date: String,
    val rainMm: Double,
    val tMinC: Double,
    val tMaxC: Double,
)

/** Fiche « En cas d'intoxication ». `sortieIds` : identifiants séparés par des virgules. */
@Entity(tableName = "intox_fiche")
data class IntoxFiche(
    @PrimaryKey val id: String,
    val createdAt: Long = System.currentTimeMillis(),
    val sortieIds: String,
    val notes: String? = null,
)

/** Jour de battue saisi à la main (bandeau chasse). */
@Entity(tableName = "battue")
data class Battue(
    @PrimaryKey val id: String,
    val date: String,
    val note: String? = null,
)

/** Journal : uniquement ce qui n'a pas fonctionné. JAMAIS de coordonnées ni de nom de spot. */
@Entity(tableName = "error_log", indices = [Index("time")])
data class ErrorLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long = System.currentTimeMillis(),
    val screen: String,
    val message: String,
    val detail: String? = null,
)

/** Hôpital avec urgences (importé par Champimap Connect, depuis OpenStreetMap). */
@Entity(tableName = "hospital")
data class Hospital(
    @PrimaryKey val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val phone: String? = null,
    val departement: String? = null,
)
