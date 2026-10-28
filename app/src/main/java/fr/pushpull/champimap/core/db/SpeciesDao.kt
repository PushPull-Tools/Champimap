package fr.pushpull.champimap.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/* Accès au catalogue des espèces (module spot), chargé depuis assets/species.json (+ espèces du modèle d'IA). */
@Dao
interface SpeciesDao {
    @Upsert suspend fun upsertAll(list: List<Species>)
    /** Ajout sans écraser : une espèce déjà en base (même id) est laissée telle quelle. */
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnore(list: List<Species>)
    @Query("SELECT COUNT(*) FROM species") suspend fun count(): Int
    @Query("SELECT * FROM species WHERE id = :id") suspend fun get(id: String): Species?
    @Query("SELECT * FROM species WHERE id = :id") fun flow(id: String): Flow<Species?>
    @Query("SELECT * FROM species ORDER BY nameFr") fun all(): Flow<List<Species>>
    @Query("SELECT * FROM species WHERE id IN (:ids)") suspend fun byIds(ids: List<String>): List<Species>
}
