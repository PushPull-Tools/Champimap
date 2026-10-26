package fr.pushpull.champimap.core.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BattueDao {
    @Upsert suspend fun upsert(b: Battue)
    @Query("SELECT * FROM battue ORDER BY date") fun all(): Flow<List<Battue>>
    @Query("SELECT * FROM battue WHERE date = :date") suspend fun on(date: String): List<Battue>
}
