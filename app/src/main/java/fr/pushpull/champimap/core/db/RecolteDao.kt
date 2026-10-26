package fr.pushpull.champimap.core.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RecolteDao {
    @Upsert suspend fun upsert(r: Recolte)
    @Upsert suspend fun upsertItem(i: RecolteItem)
    @Query("SELECT * FROM recolte WHERE id = :id") suspend fun get(id: String): Recolte?
    @Query("SELECT * FROM recolte WHERE spotId = :spotId AND deletedAt IS NULL ORDER BY time DESC")
    fun ofSpot(spotId: String): Flow<List<Recolte>>
    @Query("SELECT * FROM recolte WHERE sortieId = :sortieId AND deletedAt IS NULL ORDER BY time DESC")
    fun ofSortie(sortieId: String): Flow<List<Recolte>>
    @Query("SELECT * FROM recolte_item WHERE recolteId = :recolteId AND deletedAt IS NULL")
    fun items(recolteId: String): Flow<List<RecolteItem>>
}
