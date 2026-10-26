package fr.pushpull.champimap.core.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SortieDao {
    @Upsert suspend fun upsert(s: Sortie)
    @Upsert suspend fun upsertPoints(points: List<TrackPoint>)
    @Query("SELECT * FROM sortie WHERE id = :id") suspend fun get(id: String): Sortie?
    @Query("SELECT * FROM sortie WHERE id = :id") fun flow(id: String): Flow<Sortie?>
    @Query("SELECT * FROM sortie WHERE deletedAt IS NULL ORDER BY startedAt DESC")
    fun all(): Flow<List<Sortie>>
    @Query("SELECT * FROM sortie WHERE endedAt IS NULL AND deletedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    fun current(): Flow<Sortie?>
    @Query("SELECT * FROM track_point WHERE sortieId = :sortieId ORDER BY time")
    fun track(sortieId: String): Flow<List<TrackPoint>>
}
