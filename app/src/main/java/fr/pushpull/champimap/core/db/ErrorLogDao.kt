package fr.pushpull.champimap.core.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ErrorLogDao {
    @Upsert suspend fun upsert(e: ErrorLog)
    @Query("SELECT * FROM error_log ORDER BY time DESC") fun all(): Flow<List<ErrorLog>>
    @Query("DELETE FROM error_log") suspend fun clear()
    /** Rotation : garde les 500 dernières lignes et 30 jours au maximum. */
    @Query("DELETE FROM error_log WHERE time < :minTime OR id NOT IN (SELECT id FROM error_log ORDER BY time DESC LIMIT 500)")
    suspend fun rotate(minTime: Long)
}
