package fr.pushpull.champimap.core.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/*
 * Mode vendeur (expérimental) : accès aux ventes (table `vente`).
 * Écriture uniquement dans `vente` ; lecture seule des sorties, récoltes et espèces
 * (sorties d'une journée, poids récolté, espèces récoltées pour les suggestions).
 */

/** Poids pesé récolté pendant une sortie (g). */
data class VenteSortieWeight(
    val sortieId: String,
    val weightG: Long,
)

@Dao
interface VenteDao {

    // ------------------------------------------------------------------ Ventes

    @Upsert suspend fun upsert(v: Vente)

    @Query("SELECT * FROM vente WHERE id = :id") suspend fun get(id: String): Vente?

    /** Ventes hors corbeille, de la plus récente à la plus ancienne (mises à jour en direct). */
    @Query("SELECT * FROM vente WHERE deletedAt IS NULL ORDER BY soldAt DESC, updatedAt DESC")
    fun allFlow(): Flow<List<Vente>>

    /** Ventes hors corbeille, de la plus ancienne à la plus récente (export CSV). */
    @Query("SELECT * FROM vente WHERE deletedAt IS NULL ORDER BY soldAt, updatedAt")
    suspend fun all(): List<Vente>

    /** Mise à la corbeille (restaurable 30 jours). */
    @Query("UPDATE vente SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    /** Sortie de la corbeille. */
    @Query("UPDATE vente SET deletedAt = NULL, updatedAt = :now WHERE id = :id")
    suspend fun restore(id: String, now: Long)

    /** Suppression définitive (corbeille vidée ou purge après 30 jours). */
    @Query("DELETE FROM vente WHERE id = :id")
    suspend fun deleteForever(id: String)

    // ------------------------------------------------------------------ Lectures pour la saisie

    @Query("SELECT * FROM sortie WHERE id = :id") suspend fun sortie(id: String): Sortie?

    /** Sorties hors corbeille commencées dans [from ; to[ (une journée). */
    @Query("SELECT * FROM sortie WHERE deletedAt IS NULL AND startedAt >= :from AND startedAt < :to ORDER BY startedAt")
    suspend fun sortiesBetween(from: Long, to: Long): List<Sortie>

    /** Poids pesé récolté par sortie (corbeille exclue). */
    @Query(
        """SELECT r.sortieId AS sortieId, COALESCE(SUM(ri.weightG), 0) AS weightG
           FROM recolte r JOIN recolte_item ri ON ri.recolteId = r.id
           WHERE r.sortieId IN (:sortieIds) AND r.deletedAt IS NULL AND ri.deletedAt IS NULL
           GROUP BY r.sortieId""",
    )
    suspend fun sortieWeights(sortieIds: List<String>): List<VenteSortieWeight>

    /** Espèces récoltées pendant ces sorties (suggestions de l'espèce vendue). */
    @Query(
        """SELECT DISTINCT sp.* FROM species sp
           JOIN recolte_item ri ON ri.speciesId = sp.id
           JOIN recolte r ON r.id = ri.recolteId
           WHERE r.sortieId IN (:sortieIds) AND r.deletedAt IS NULL AND ri.deletedAt IS NULL
           ORDER BY sp.nameFr""",
    )
    suspend fun speciesOfSorties(sortieIds: List<String>): List<Species>
}
