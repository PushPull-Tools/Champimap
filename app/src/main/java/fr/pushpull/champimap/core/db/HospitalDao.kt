package fr.pushpull.champimap.core.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface HospitalDao {
    @Upsert suspend fun upsertAll(list: List<Hospital>)
    @Query("SELECT * FROM hospital") suspend fun all(): List<Hospital>
}
