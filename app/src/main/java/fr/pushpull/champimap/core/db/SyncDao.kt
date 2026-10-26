package fr.pushpull.champimap.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

/*
 * Requêtes du module groupe : lecture et écriture de toutes les entités échangées
 * entre téléphones (sortie à plusieurs) ou contenues dans un spot partagé (.champi, QR).
 * La règle de fusion (« la plus récente `updatedAt` gagne ») est appliquée dans
 * feature/groupe/GroupRepository.kt, pas ici.
 * Les listes passées en paramètre `IN (:ids)` doivent rester < 999 éléments
 * (limite SQLite) : le module les découpe par paquets.
 */

/** Dernier instant de trace GPS connu pour un auteur (résumé de synchro). */
data class AuthorMaxTime(val authorId: String, val maxTime: Long)

/** Fusion des données entre téléphones (module groupe). */
@Dao
interface SyncDao {

    // ---------------------------------------------------------------- Sorties

    @Query("SELECT * FROM sortie WHERE groupId = :groupId")
    suspend fun sortiesOfGroup(groupId: String): List<Sortie>

    @Query("SELECT * FROM sortie WHERE id = :id")
    suspend fun sortie(id: String): Sortie?

    /** Sortie en cours (non terminée, hors corbeille), la plus récente. */
    @Query("SELECT * FROM sortie WHERE endedAt IS NULL AND deletedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun currentSortie(): Sortie?

    @Upsert
    suspend fun upsertSortie(s: Sortie)

    // ---------------------------------------------------------------- Personnes

    @Query("SELECT * FROM person WHERE isMe = 1 LIMIT 1")
    suspend fun me(): Person?

    @Query("SELECT * FROM person WHERE id = :id")
    suspend fun person(id: String): Person?

    @Query("SELECT * FROM person WHERE id IN (:ids)")
    suspend fun persons(ids: List<String>): List<Person>

    @Upsert
    suspend fun upsertPerson(p: Person)

    // ---------------------------------------------------------------- Spots

    @Query("SELECT * FROM spot WHERE id = :id")
    suspend fun spot(id: String): Spot?

    @Query("SELECT * FROM spot WHERE id IN (:ids)")
    suspend fun spots(ids: List<String>): List<Spot>

    @Upsert
    suspend fun upsertSpot(s: Spot)

    /** Spots visités pendant ces sorties. */
    @Query("SELECT DISTINCT spotId FROM spot_visit WHERE sortieId IN (:sortieIds)")
    suspend fun spotIdsVisitedIn(sortieIds: List<String>): List<String>

    /** Spots récoltés pendant ces sorties. */
    @Query("SELECT DISTINCT spotId FROM recolte WHERE sortieId IN (:sortieIds)")
    suspend fun spotIdsHarvestedIn(sortieIds: List<String>): List<String>

    // ---------------------------------------------------------------- Positions des spots

    @Query("SELECT * FROM spot_position WHERE spotId IN (:spotIds)")
    suspend fun positionsOfSpots(spotIds: List<String>): List<SpotPosition>

    @Query("SELECT * FROM spot_position WHERE id = :id")
    suspend fun position(id: String): SpotPosition?

    /** Une position ne change jamais une fois créée : on l'ajoute si elle est absente. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPosition(p: SpotPosition)

    @Query("SELECT COUNT(*) FROM spot_position WHERE spotId = :spotId AND lat = :lat AND lon = :lon")
    suspend fun countPositionsAt(spotId: String, lat: Double, lon: Double): Int

    /** Marque comme active la (les) position(s) égale(s) à la position affichée du spot. */
    @Query("UPDATE spot_position SET active = CASE WHEN lat = :lat AND lon = :lon THEN 1 ELSE 0 END WHERE spotId = :spotId")
    suspend fun setActivePositionAt(spotId: String, lat: Double, lon: Double)

    // ---------------------------------------------------------------- Visites

    @Query("SELECT * FROM spot_visit WHERE sortieId IN (:sortieIds)")
    suspend fun visitsOfSorties(sortieIds: List<String>): List<SpotVisit>

    @Query("SELECT * FROM spot_visit WHERE spotId = :spotId")
    suspend fun visitsOfSpot(spotId: String): List<SpotVisit>

    @Query("SELECT * FROM spot_visit WHERE id = :id")
    suspend fun visit(id: String): SpotVisit?

    @Upsert
    suspend fun upsertVisit(v: SpotVisit)

    // ---------------------------------------------------------------- Récoltes

    @Query("SELECT * FROM recolte WHERE sortieId IN (:sortieIds)")
    suspend fun recoltesOfSorties(sortieIds: List<String>): List<Recolte>

    @Query("SELECT * FROM recolte WHERE spotId = :spotId")
    suspend fun recoltesOfSpot(spotId: String): List<Recolte>

    @Query("SELECT * FROM recolte WHERE id = :id")
    suspend fun recolte(id: String): Recolte?

    @Upsert
    suspend fun upsertRecolte(r: Recolte)

    @Query("SELECT * FROM recolte_item WHERE recolteId IN (:recolteIds)")
    suspend fun itemsOfRecoltes(recolteIds: List<String>): List<RecolteItem>

    @Query("SELECT * FROM recolte_item WHERE id = :id")
    suspend fun item(id: String): RecolteItem?

    @Upsert
    suspend fun upsertItem(i: RecolteItem)

    // ---------------------------------------------------------------- Photos

    @Query("SELECT * FROM photo WHERE ownerId IN (:ownerIds)")
    suspend fun photosOfOwners(ownerIds: List<String>): List<Photo>

    @Query("SELECT * FROM photo WHERE id = :id")
    suspend fun photo(id: String): Photo?

    @Upsert
    suspend fun upsertPhoto(p: Photo)

    // ---------------------------------------------------------------- Traces GPS

    /** Pour chaque auteur, l'instant du dernier point de trace connu dans ces sorties. */
    @Query("SELECT authorId, MAX(time) AS maxTime FROM track_point WHERE sortieId IN (:sortieIds) GROUP BY authorId")
    suspend fun trackMaxByAuthor(sortieIds: List<String>): List<AuthorMaxTime>

    /** Points d'un auteur postérieurs à `after` (par paquets de `limit`). */
    @Query("SELECT * FROM track_point WHERE sortieId IN (:sortieIds) AND authorId = :authorId AND time > :after ORDER BY time LIMIT :limit")
    suspend fun trackAfter(sortieIds: List<String>, authorId: String, after: Long, limit: Int): List<TrackPoint>

    /** Les points de trace sont immuables : ajout seulement s'ils sont absents. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTrackPoints(points: List<TrackPoint>)
}
