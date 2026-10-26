package fr.pushpull.champimap.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/*
 * Accès aux données du carnet (module carnet) : succès obtenus, et lectures « à plat »
 * de toutes les données dont le calcul de l'XP et des succès a besoin.
 * Lecture seule sur les tables des autres modules : seul `unlocked_badge` est modifié ici.
 */

/** Une espèce récoltée, à plat : sa récolte, son spot (position pour la météo) et son auteur. */
data class CarnetHarvest(
    val itemId: String,
    val sortieId: String,
    val spotId: String,
    /** Date de la récolte (millisecondes UTC). */
    val time: Long,
    val speciesId: String,
    val weightG: Int?,
    val authorId: String,
    val lat: Double,
    val lon: Double,
)

/** Meilleure précision (m) jamais mesurée pour un spot. */
data class SpotBestAccuracy(val spotId: String, val best: Float?)

/** Nombre de points de trace d'une sortie (sert à savoir si la distance en cache est à jour). */
data class TrackCount(val sortieId: String, val n: Int)

@Dao
interface CarnetDao {

    // ------------------------------------------------------------------ Succès obtenus

    @Query("SELECT * FROM unlocked_badge ORDER BY unlockedAt")
    fun badgesFlow(): Flow<List<UnlockedBadge>>

    @Query("SELECT * FROM unlocked_badge ORDER BY unlockedAt")
    suspend fun badges(): List<UnlockedBadge>

    /** Ajoute les nouveaux succès ; un succès déjà obtenu garde sa date d'origine. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBadges(list: List<UnlockedBadge>)

    /** Marque des succès comme annoncés (carte de fin de sortie fermée, ou carnet consulté). */
    @Query("UPDATE unlocked_badge SET announced = 1 WHERE id IN (:ids)")
    suspend fun markAnnounced(ids: List<String>)

    // ------------------------------------------------------------------ Données lues pour le calcul

    @Query("SELECT * FROM person WHERE isMe = 1 LIMIT 1")
    suspend fun me(): Person?

    @Query("SELECT * FROM sortie WHERE deletedAt IS NULL ORDER BY startedAt")
    suspend fun sorties(): List<Sortie>

    @Query("SELECT * FROM spot WHERE deletedAt IS NULL")
    suspend fun spots(): List<Spot>

    /** Passages sur des spots hors corbeille. */
    @Query("SELECT v.* FROM spot_visit v JOIN spot s ON s.id = v.spotId WHERE s.deletedAt IS NULL ORDER BY v.time")
    suspend fun visits(): List<SpotVisit>

    /** Espèces récoltées (corbeille exclue : espèce, récolte, spot ou sortie supprimés). */
    @Query(
        """
        SELECT i.id AS itemId, r.sortieId AS sortieId, r.spotId AS spotId, r.time AS time,
               i.speciesId AS speciesId, i.weightG AS weightG, i.authorId AS authorId, s.lat AS lat, s.lon AS lon
        FROM recolte_item i
        JOIN recolte r ON r.id = i.recolteId
        JOIN spot s ON s.id = r.spotId
        WHERE i.deletedAt IS NULL AND r.deletedAt IS NULL AND s.deletedAt IS NULL
          AND r.sortieId NOT IN (SELECT id FROM sortie WHERE deletedAt IS NOT NULL)
        ORDER BY r.time
        """
    )
    suspend fun harvests(): List<CarnetHarvest>

    /** Photos hors corbeille (spots et espèces). */
    @Query("SELECT * FROM photo WHERE deletedAt IS NULL")
    suspend fun photos(): List<Photo>

    /** Meilleure précision mesurée pour chaque spot. */
    @Query("SELECT spotId, MIN(accuracyM) AS best FROM spot_position WHERE accuracyM IS NOT NULL GROUP BY spotId")
    suspend fun bestAccuracies(): List<SpotBestAccuracy>

    /** Nombre de mesures « Je suis au pied » faites par une personne. */
    @Query("SELECT COUNT(*) FROM spot_position WHERE source = 'AU_PIED' AND authorId = :authorId")
    suspend fun auPiedCount(authorId: String): Int

    /** Nombre de points de trace par sortie. */
    @Query("SELECT sortieId, COUNT(*) AS n FROM track_point GROUP BY sortieId")
    suspend fun trackCounts(): List<TrackCount>

    /** Trace d'une sortie pour un auteur (en sortie à plusieurs, chacun sa trace). */
    @Query("SELECT * FROM track_point WHERE sortieId = :sortieId AND authorId = :authorId ORDER BY time")
    suspend fun track(sortieId: String, authorId: String): List<TrackPoint>

    /** Météo de plusieurs zones sur une période (dates ISO « AAAA-MM-JJ »). */
    @Query("SELECT * FROM weather_day WHERE zoneId IN (:zoneIds) AND date BETWEEN :from AND :to")
    suspend fun weather(zoneIds: List<String>, from: String, to: String): List<WeatherDay>
}
