package fr.pushpull.champimap.core.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Hôpitaux avec urgences, importés par Champimap Connect (module connect). */
@Dao
interface HospitalDao {
    @Upsert suspend fun upsertAll(list: List<Hospital>)
    @Query("SELECT * FROM hospital") suspend fun all(): List<Hospital>

    /** Nombre d'hôpitaux connus (affichage dans l'écran Connect). */
    @Query("SELECT COUNT(*) FROM hospital") fun countFlow(): Flow<Int>

    @Query("DELETE FROM hospital WHERE departement = :code") suspend fun deleteDepartement(code: String)

}
