package fr.pushpull.champimap.core.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/*
 * Accès aux récoltes et à leurs espèces (module spot).
 * Suppression = corbeille (`deletedAt`), jamais d'effacement direct.
 */
@Dao
interface RecolteDao {
    // --- Écriture -------------------------------------------------------------------------
    @Upsert suspend fun upsert(r: Recolte)
    @Upsert suspend fun upsertItem(i: RecolteItem)

    // --- Récoltes -------------------------------------------------------------------------
    @Query("SELECT * FROM recolte WHERE id = :id") suspend fun get(id: String): Recolte?
    @Query("SELECT * FROM recolte WHERE id = :id") fun flow(id: String): Flow<Recolte?>
    @Query("SELECT * FROM recolte WHERE spotId = :spotId AND deletedAt IS NULL ORDER BY time DESC")
    fun ofSpot(spotId: String): Flow<List<Recolte>>
    @Query("SELECT * FROM recolte WHERE sortieId = :sortieId AND deletedAt IS NULL ORDER BY time DESC")
    fun ofSortie(sortieId: String): Flow<List<Recolte>>

    /** Récolte de ce spot pendant cette sortie (la plus récente). */
    @Query("SELECT * FROM recolte WHERE spotId = :spotId AND sortieId = :sortieId AND deletedAt IS NULL ORDER BY time DESC LIMIT 1")
    suspend fun ofSpotAndSortie(spotId: String, sortieId: String): Recolte?

    /** Dernière récolte de ce spot, toutes sorties confondues. */
    @Query("SELECT * FROM recolte WHERE spotId = :spotId AND deletedAt IS NULL ORDER BY time DESC LIMIT 1")
    suspend fun latestOfSpot(spotId: String): Recolte?

    // --- Espèces d'une récolte ----------------------------------------------------------------
    @Query("SELECT * FROM recolte_item WHERE recolteId = :recolteId AND deletedAt IS NULL")
    fun items(recolteId: String): Flow<List<RecolteItem>>

    @Query("SELECT * FROM recolte_item WHERE recolteId = :recolteId AND deletedAt IS NULL")
    suspend fun itemsList(recolteId: String): List<RecolteItem>

    @Query("SELECT * FROM recolte_item WHERE id = :id") suspend fun item(id: String): RecolteItem?

    /** Met une espèce de récolte à la corbeille. */
    @Query("UPDATE recolte_item SET deletedAt = :at, updatedAt = :at WHERE id = :id")
    suspend fun trashItem(id: String, at: Long)

    /** Sort une espèce de la corbeille (bouton « Annuler »). */
    @Query("UPDATE recolte_item SET deletedAt = NULL, updatedAt = :at WHERE id = :id")
    suspend fun restoreItem(id: String, at: Long)

    /** Espèces les plus souvent récoltées (« Tes habituels »), de la plus fréquente à la moins fréquente. */
    @Query("SELECT speciesId FROM recolte_item WHERE deletedAt IS NULL GROUP BY speciesId ORDER BY COUNT(*) DESC LIMIT :limit")
    fun habitualSpeciesIds(limit: Int): Flow<List<String>>
}
