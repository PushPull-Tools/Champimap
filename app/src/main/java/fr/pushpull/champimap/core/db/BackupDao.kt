package fr.pushpull.champimap.core.db

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/*
 * Requêtes du module « réglages » : accueil (chiffres de la saison), corbeille
 * (liste, restauration, suppression définitive), export CSV et jours de battue.
 * Les suppressions en cascade sont orchestrées par le module (transaction Room).
 */

/** Ligne de la corbeille, tous types confondus (SPOT, SORTIE, RECOLTE, ITEM, PHOTO). */
data class TrashEntry(
    val kind: String,
    val id: String,
    /** Libellé lisible : nom du spot, lieu de la sortie, espèce… (peut être nul). */
    val label: String?,
    /** Date de l'élément lui-même (création, début de sortie…). */
    val time: Long,
    val deletedAt: Long,
)

/** Poids total récolté pendant une sortie (accueil, « Dernières sorties »). */
data class SortieWeight(
    val sortieId: String,
    val weightG: Long,
)

/** Une ligne de l'export CSV des récoltes. */
data class CsvRow(
    val time: Long,
    val species: String?,
    val latin: String?,
    val weightG: Int?,
    val spotName: String?,
    val stade: String?,
    val qualites: String?,
    val etats: String?,
)

/** Requête commune « tout ce qui est dans la corbeille », du plus récent au plus ancien. */
private const val TRASH_SQL = """
    SELECT 'SPOT' AS kind, id, name AS label, createdAt AS time, deletedAt FROM spot WHERE deletedAt IS NOT NULL
    UNION ALL
    SELECT 'SORTIE' AS kind, id, placeName AS label, startedAt AS time, deletedAt FROM sortie WHERE deletedAt IS NOT NULL
    UNION ALL
    SELECT 'RECOLTE' AS kind, r.id AS id, sp.name AS label, r.time AS time, r.deletedAt AS deletedAt
        FROM recolte r LEFT JOIN spot sp ON sp.id = r.spotId WHERE r.deletedAt IS NOT NULL
    UNION ALL
    SELECT 'ITEM' AS kind, ri.id AS id, s.nameFr AS label, ri.updatedAt AS time, ri.deletedAt AS deletedAt
        FROM recolte_item ri LEFT JOIN species s ON s.id = ri.speciesId WHERE ri.deletedAt IS NOT NULL
    UNION ALL
    SELECT 'PHOTO' AS kind, id, ownerType AS label, createdAt AS time, deletedAt FROM photo WHERE deletedAt IS NOT NULL
    ORDER BY deletedAt DESC
"""

@Dao
interface BackupDao {

    // ---------- Accueil : « Cette saison » ----------

    /** Poids total (g) récolté depuis `since`. */
    @Query(
        """SELECT COALESCE(SUM(ri.weightG), 0) FROM recolte_item ri JOIN recolte r ON r.id = ri.recolteId
           WHERE ri.deletedAt IS NULL AND r.deletedAt IS NULL AND r.time >= :since""",
    )
    fun seasonWeightG(since: Long): Flow<Long>

    /** Nombre de sorties commencées depuis `since`. */
    @Query("SELECT COUNT(*) FROM sortie WHERE deletedAt IS NULL AND startedAt >= :since")
    fun seasonSorties(since: Long): Flow<Int>

    /** Nombre de spots créés, visités ou récoltés depuis `since`. */
    @Query(
        """SELECT COUNT(*) FROM spot WHERE deletedAt IS NULL AND (createdAt >= :since
           OR id IN (SELECT spotId FROM spot_visit WHERE time >= :since)
           OR id IN (SELECT spotId FROM recolte WHERE deletedAt IS NULL AND time >= :since))""",
    )
    fun seasonSpots(since: Long): Flow<Int>

    /** Poids récolté par sortie (pour les dernières sorties de l'accueil). */
    @Query(
        """SELECT r.sortieId AS sortieId, COALESCE(SUM(ri.weightG), 0) AS weightG
           FROM recolte r LEFT JOIN recolte_item ri ON ri.recolteId = r.id AND ri.deletedAt IS NULL
           WHERE r.deletedAt IS NULL GROUP BY r.sortieId""",
    )
    fun sortieWeights(): Flow<List<SortieWeight>>

    /** Date de début de la dernière sortie (pour réafficher le bandeau chasse masqué). */
    @Query("SELECT MAX(startedAt) FROM sortie WHERE deletedAt IS NULL")
    fun lastSortieStart(): Flow<Long?>

    // ---------- Corbeille ----------

    @Query(TRASH_SQL) fun trash(): Flow<List<TrashEntry>>
    @Query(TRASH_SQL) suspend fun trashOnce(): List<TrashEntry>

    @Query("UPDATE spot SET deletedAt = NULL, updatedAt = :now WHERE id = :id") suspend fun restoreSpot(id: String, now: Long)
    @Query("UPDATE sortie SET deletedAt = NULL, updatedAt = :now WHERE id = :id") suspend fun restoreSortie(id: String, now: Long)
    @Query("UPDATE recolte SET deletedAt = NULL, updatedAt = :now WHERE id = :id") suspend fun restoreRecolte(id: String, now: Long)
    @Query("UPDATE recolte_item SET deletedAt = NULL, updatedAt = :now WHERE id = :id") suspend fun restoreItem(id: String, now: Long)
    @Query("UPDATE photo SET deletedAt = NULL WHERE id = :id") suspend fun restorePhoto(id: String)

    /** Photos d'un propriétaire, y compris celles déjà dans la corbeille (pour effacer les fichiers). */
    @Query("SELECT * FROM photo WHERE ownerId = :ownerId") suspend fun photosOfOwner(ownerId: String): List<Photo>
    @Query("SELECT * FROM photo WHERE id = :id") suspend fun photo(id: String): Photo?
    @Query("SELECT id FROM recolte WHERE spotId = :spotId") suspend fun recolteIdsOfSpot(spotId: String): List<String>
    @Query("SELECT id FROM recolte WHERE sortieId = :sortieId") suspend fun recolteIdsOfSortie(sortieId: String): List<String>
    @Query("SELECT id FROM recolte_item WHERE recolteId = :recolteId") suspend fun itemIdsOfRecolte(recolteId: String): List<String>

    @Query("DELETE FROM photo WHERE id = :id") suspend fun deletePhoto(id: String)
    @Query("DELETE FROM recolte_item WHERE id = :id") suspend fun deleteItem(id: String)
    @Query("DELETE FROM recolte WHERE id = :id") suspend fun deleteRecolte(id: String)
    @Query("DELETE FROM spot_position WHERE spotId = :spotId") suspend fun deletePositionsOfSpot(spotId: String)
    @Query("DELETE FROM spot_visit WHERE spotId = :spotId") suspend fun deleteVisitsOfSpot(spotId: String)
    @Query("DELETE FROM spot WHERE id = :id") suspend fun deleteSpot(id: String)
    @Query("DELETE FROM spot_visit WHERE sortieId = :sortieId") suspend fun deleteVisitsOfSortie(sortieId: String)
    @Query("DELETE FROM track_point WHERE sortieId = :sortieId") suspend fun deleteTrack(sortieId: String)
    @Query("DELETE FROM sortie WHERE id = :id") suspend fun deleteSortie(id: String)

    // ---------- Export CSV ----------

    @Query(
        """SELECT r.time AS time, s.nameFr AS species, s.nameLatin AS latin, ri.weightG AS weightG,
                  sp.name AS spotName, ri.stade AS stade, ri.qualites AS qualites, ri.etats AS etats
           FROM recolte_item ri JOIN recolte r ON r.id = ri.recolteId
           LEFT JOIN species s ON s.id = ri.speciesId LEFT JOIN spot sp ON sp.id = r.spotId
           WHERE ri.deletedAt IS NULL AND r.deletedAt IS NULL ORDER BY r.time""",
    )
    suspend fun csvRows(): List<CsvRow>

    // ---------- Jours de battue (BattueDao n'a pas de suppression) ----------

    @Query("DELETE FROM battue WHERE id = :id") suspend fun deleteBattue(id: String)
}
