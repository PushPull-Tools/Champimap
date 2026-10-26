package fr.pushpull.champimap.core.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface IntoxDao {
    @Upsert suspend fun upsert(f: IntoxFiche)
    @Query("SELECT * FROM intox_fiche ORDER BY createdAt DESC") fun all(): Flow<List<IntoxFiche>>
    @Query("SELECT * FROM intox_fiche WHERE id = :id") suspend fun get(id: String): IntoxFiche?
}
