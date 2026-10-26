package fr.pushpull.champimap.core.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Fusion des données entre téléphones (module groupe). */
@Dao
interface SyncDao {
    @Query("SELECT * FROM sortie WHERE groupId = :groupId")
    suspend fun sortiesOfGroup(groupId: String): List<Sortie>
}
