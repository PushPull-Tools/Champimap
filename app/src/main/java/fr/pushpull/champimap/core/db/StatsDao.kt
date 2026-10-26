package fr.pushpull.champimap.core.db

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/*
 * Requêtes de statistiques (module stats).
 * Lecture seule : ce DAO ne modifie jamais les données des autres modules.
 */

/**
 * Une ligne de récolte « à plat » : une espèce d'une récolte, avec sa date, son spot et son auteur.
 * Base de tous les calculs (totaux, podium, calendrier, carte aux trésors, météo, export CSV).
 * Les champs du spot et de l'espèce sont nuls si l'élément lié n'existe pas (jointure externe).
 */
data class HarvestRow(
    val itemId: String,
    val recolteId: String,
    val sortieId: String,
    val spotId: String,
    /** Date de la récolte (millisecondes UTC). */
    val time: Long,
    val speciesId: String,
    val speciesName: String?,
    val speciesLatin: String?,
    val weightG: Int?,
    val stade: String?,
    val qualites: String?,
    val etats: String?,
    /** Personne qui a saisi l'espèce (stats par personne). */
    val authorId: String,
    val spotName: String?,
    /** Statut du spot (nom de l'énumération SpotStatus). */
    val spotStatus: String?,
    val lat: Double?,
    val lon: Double?,
)

/** Un passage sur un spot (pour compter les spots visités sur une période). */
data class VisitRow(
    val spotId: String,
    val sortieId: String,
    val time: Long,
)

/**
 * Requête commune des récoltes : exclut la corbeille (espèce, récolte, spot ou sortie supprimés).
 * Constante pour être partagée entre la version Flow et la version suspendue.
 */
internal const val STATS_HARVEST_SQL = """
    SELECT i.id AS itemId, r.id AS recolteId, r.sortieId AS sortieId, r.spotId AS spotId, r.time AS time,
           i.speciesId AS speciesId, sp.nameFr AS speciesName, sp.nameLatin AS speciesLatin,
           i.weightG AS weightG, i.stade AS stade, i.qualites AS qualites, i.etats AS etats,
           i.authorId AS authorId, s.name AS spotName, s.status AS spotStatus, s.lat AS lat, s.lon AS lon
    FROM recolte_item i
    JOIN recolte r ON r.id = i.recolteId
    LEFT JOIN spot s ON s.id = r.spotId
    LEFT JOIN species sp ON sp.id = i.speciesId
    WHERE i.deletedAt IS NULL AND r.deletedAt IS NULL AND s.deletedAt IS NULL
      AND r.sortieId NOT IN (SELECT id FROM sortie WHERE deletedAt IS NOT NULL)
    ORDER BY r.time
"""

@Dao
interface StatsDao {
    /** Poids total récolté (g), corbeille exclue. */
    @Query(
        "SELECT COALESCE(SUM(i.weightG), 0) FROM recolte_item i JOIN recolte r ON r.id = i.recolteId " +
            "WHERE i.deletedAt IS NULL AND r.deletedAt IS NULL",
    )
    fun totalWeightG(): Flow<Long>

    /** Toutes les récoltes à plat, mises à jour en direct. */
    @Query(STATS_HARVEST_SQL)
    fun harvestsFlow(): Flow<List<HarvestRow>>

    /** Toutes les récoltes à plat (lecture ponctuelle : export, alertes, météo). */
    @Query(STATS_HARVEST_SQL)
    suspend fun harvests(): List<HarvestRow>

    /** Sorties hors corbeille, de la plus ancienne à la plus récente. */
    @Query("SELECT * FROM sortie WHERE deletedAt IS NULL ORDER BY startedAt")
    fun sortiesFlow(): Flow<List<Sortie>>

    @Query("SELECT * FROM sortie WHERE deletedAt IS NULL ORDER BY startedAt")
    suspend fun sorties(): List<Sortie>

    /** Passages sur des spots hors corbeille. */
    @Query(
        "SELECT v.spotId AS spotId, v.sortieId AS sortieId, v.time AS time FROM spot_visit v " +
            "JOIN spot s ON s.id = v.spotId WHERE s.deletedAt IS NULL",
    )
    fun visitsFlow(): Flow<List<VisitRow>>

    /** Toutes les personnes connues (noms du podium et de l'export). */
    @Query("SELECT * FROM person")
    suspend fun persons(): List<Person>
}
