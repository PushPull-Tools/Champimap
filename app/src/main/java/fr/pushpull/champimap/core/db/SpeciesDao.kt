package fr.pushpull.champimap.core.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/* Accès au catalogue des espèces (module spot), chargé depuis assets/species.json. */
@Dao
interface SpeciesDao {
    @Upsert suspend fun upsertAll(list: List<Species>)
    @Query("SELECT COUNT(*) FROM species") suspend fun count(): Int
    @Query("SELECT * FROM species WHERE id = :id") suspend fun get(id: String): Species?
    @Query("SELECT * FROM species WHERE id = :id") fun flow(id: String): Flow<Species?>
    @Query("SELECT * FROM species ORDER BY nameFr") fun all(): Flow<List<Species>>
    @Query("SELECT * FROM species WHERE id IN (:ids)") suspend fun byIds(ids: List<String>): List<Species>
}
