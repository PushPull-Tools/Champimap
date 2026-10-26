package fr.pushpull.champimap.core.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/*
 * Accès aux spots, à l'historique de leurs positions et aux visites (module spot).
 * Les autres modules peuvent LIRE ces requêtes ; seul le module spot les modifie.
 */
@Dao
interface SpotDao {
    // --- Écriture -------------------------------------------------------------------------
    @Upsert suspend fun upsert(s: Spot)
    @Upsert suspend fun upsertPosition(p: SpotPosition)
    @Upsert suspend fun upsertVisit(v: SpotVisit)

    // --- Spots ----------------------------------------------------------------------------
    @Query("SELECT * FROM spot WHERE id = :id") suspend fun get(id: String): Spot?
    @Query("SELECT * FROM spot WHERE id = :id") fun flow(id: String): Flow<Spot?>
    @Query("SELECT * FROM spot WHERE deletedAt IS NULL ORDER BY updatedAt DESC")
    fun all(): Flow<List<Spot>>
    @Query("SELECT DISTINCT spot.* FROM spot JOIN spot_visit ON spot.id = spot_visit.spotId WHERE spot_visit.sortieId = :sortieId AND spot.deletedAt IS NULL")
    fun spotsOfSortie(sortieId: String): Flow<List<Spot>>

    // --- Historique des positions -----------------------------------------------------------
    @Query("SELECT * FROM spot_position WHERE spotId = :spotId ORDER BY time DESC")
    fun positions(spotId: String): Flow<List<SpotPosition>>

    /** Même liste, en lecture ponctuelle (calcul de moyenne). */
    @Query("SELECT * FROM spot_position WHERE spotId = :spotId ORDER BY time DESC")
    suspend fun positionsList(spotId: String): List<SpotPosition>

    @Query("SELECT * FROM spot_position WHERE id = :id")
    suspend fun position(id: String): SpotPosition?

    /** Plus aucune position active pour ce spot (avant d'en activer une autre). */
    @Query("UPDATE spot_position SET active = 0 WHERE spotId = :spotId")
    suspend fun clearActivePositions(spotId: String)

    @Query("UPDATE spot_position SET active = 1 WHERE id = :id")
    suspend fun setActivePosition(id: String)

    // --- Visites --------------------------------------------------------------------------
    @Query("SELECT * FROM spot_visit WHERE spotId = :spotId ORDER BY time DESC")
    fun visits(spotId: String): Flow<List<SpotVisit>>

    /** Annule une visite (bouton « Annuler » après « Spot vide »). */
    @Query("DELETE FROM spot_visit WHERE id = :id")
    suspend fun deleteVisit(id: String)

    @Query("SELECT COUNT(*) FROM spot_visit WHERE spotId = :spotId")
    fun visitCount(spotId: String): Flow<Int>

    /** Visite (non vide) de ce spot pendant cette sortie, s'il y en a une. */
    @Query("SELECT * FROM spot_visit WHERE spotId = :spotId AND sortieId = :sortieId AND empty = 0 LIMIT 1")
    suspend fun visitOf(spotId: String, sortieId: String): SpotVisit?
}
