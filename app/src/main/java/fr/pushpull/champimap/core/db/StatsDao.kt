package fr.pushpull.champimap.core.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Requêtes de statistiques (module stats). */
@Dao
interface StatsDao {
    @Query("SELECT COALESCE(SUM(weightG), 0) FROM recolte_item WHERE deletedAt IS NULL")
    fun totalWeightG(): Flow<Long>
}
