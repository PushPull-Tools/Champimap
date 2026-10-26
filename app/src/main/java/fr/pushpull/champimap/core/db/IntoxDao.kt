package fr.pushpull.champimap.core.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Fiches « En cas d'intoxication » (module connect). */
@Dao
interface IntoxDao {
    @Upsert suspend fun upsert(f: IntoxFiche)
    @Query("SELECT * FROM intox_fiche ORDER BY createdAt DESC") fun all(): Flow<List<IntoxFiche>>
    @Query("SELECT * FROM intox_fiche WHERE id = :id") suspend fun get(id: String): IntoxFiche?

    /** Fiche la plus récente pour exactement ces sorties (évite les doublons à la réouverture). */
    @Query("SELECT * FROM intox_fiche WHERE sortieIds = :sortieIds ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestFor(sortieIds: String): IntoxFiche?

    /** Suppression définitive d'une fiche (depuis la liste des fiches). */
    @Query("DELETE FROM intox_fiche WHERE id = :id") suspend fun delete(id: String)
}
