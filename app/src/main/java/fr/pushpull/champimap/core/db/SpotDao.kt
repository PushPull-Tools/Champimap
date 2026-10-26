package fr.pushpull.champimap.core.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SpotDao {
    @Upsert suspend fun upsert(s: Spot)
    @Upsert suspend fun upsertPosition(p: SpotPosition)
    @Upsert suspend fun upsertVisit(v: SpotVisit)
    @Query("SELECT * FROM spot WHERE id = :id") suspend fun get(id: String): Spot?
    @Query("SELECT * FROM spot WHERE id = :id") fun flow(id: String): Flow<Spot?>
    @Query("SELECT * FROM spot WHERE deletedAt IS NULL ORDER BY updatedAt DESC")
    fun all(): Flow<List<Spot>>
    @Query("SELECT * FROM spot_position WHERE spotId = :spotId ORDER BY time DESC")
    fun positions(spotId: String): Flow<List<SpotPosition>>
    @Query("SELECT * FROM spot_visit WHERE spotId = :spotId ORDER BY time DESC")
    fun visits(spotId: String): Flow<List<SpotVisit>>
    @Query("SELECT DISTINCT spot.* FROM spot JOIN spot_visit ON spot.id = spot_visit.spotId WHERE spot_visit.sortieId = :sortieId AND spot.deletedAt IS NULL")
    fun spotsOfSortie(sortieId: String): Flow<List<Spot>>
}
