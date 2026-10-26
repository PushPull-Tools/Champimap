package fr.pushpull.champimap.core.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/*
 * Accès aux photos (module spot). Les fichiers sont dans filesDir/photos/.
 * Suppression = corbeille (`deletedAt`) ; le fichier est effacé lors de la purge à 30 jours.
 */
@Dao
interface PhotoDao {
    @Upsert suspend fun upsert(p: Photo)

    @Query("SELECT * FROM photo WHERE ownerId = :ownerId AND deletedAt IS NULL ORDER BY createdAt")
    fun ofOwner(ownerId: String): Flow<List<Photo>>

    /** Photo la plus récente d'un propriétaire (spot ou espèce de récolte). */
    @Query("SELECT * FROM photo WHERE ownerId = :ownerId AND deletedAt IS NULL ORDER BY createdAt DESC LIMIT 1")
    fun latestOf(ownerId: String): Flow<Photo?>

    /**
     * Toutes les photos liées à un spot : celles du spot, celles des espèces récoltées sur ce spot
     * et celles « en attente » rattachées à une récolte (prises avant le choix de l'espèce).
     */
    @Query(
        """
        SELECT * FROM photo WHERE deletedAt IS NULL AND (
            ownerId = :spotId
            OR ownerId IN (SELECT recolte_item.id FROM recolte_item JOIN recolte ON recolte.id = recolte_item.recolteId
                           WHERE recolte.spotId = :spotId AND recolte_item.deletedAt IS NULL)
            OR ownerId IN (SELECT recolte.id FROM recolte WHERE recolte.spotId = :spotId)
        ) ORDER BY createdAt DESC
        """
    )
    fun ofSpotAll(spotId: String): Flow<List<Photo>>

    /** Met des photos à la corbeille (30 jours). */
    @Query("UPDATE photo SET deletedAt = :at WHERE id IN (:ids)")
    suspend fun trash(ids: List<String>, at: Long)

    /** Rattache les photos d'un propriétaire à un autre (photo prise avant le choix de l'espèce). */
    @Query("UPDATE photo SET ownerId = :to WHERE ownerId = :from AND deletedAt IS NULL")
    suspend fun reassign(from: String, to: String)
}
