package fr.pushpull.champimap.core.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoDao {
    @Upsert suspend fun upsert(p: Photo)
    @Query("SELECT * FROM photo WHERE ownerId = :ownerId AND deletedAt IS NULL ORDER BY createdAt")
    fun ofOwner(ownerId: String): Flow<List<Photo>>
}
