package fr.pushpull.champimap.core.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Sauvegarde, export et corbeille (module réglages). */
@Dao
interface BackupDao {
    @Query("SELECT COUNT(*) FROM spot WHERE deletedAt IS NOT NULL")
    fun trashedSpots(): Flow<Int>
}
