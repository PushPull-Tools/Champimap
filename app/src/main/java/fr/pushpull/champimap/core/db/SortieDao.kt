package fr.pushpull.champimap.core.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/*
 * Accès aux sorties et à leurs traces GPS (module sortie).
 * Contient aussi les requêtes de lecture dont la carte a besoin (épingles colorées par espèce).
 */

/**
 * Un spot tel qu'affiché sur la carte : position, et espèce de la DERNIÈRE récolte
 * (couleur de l'épingle). `colorHex` nul = spot sans récolte (épingle grise).
 */
data class SpotMarker(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val status: SpotStatus,
    val colorHex: String?,
    val speciesId: String?,
    val speciesName: String?,
)

/** Un spot visité pendant une sortie, pour le récapitulatif de fin de sortie. */
data class SortieSpotSummary(
    val id: String,
    val name: String,
    /** Nombre d'espèces saisies sur ce spot pendant la sortie. */
    val items: Int,
    /** Poids total saisi (g), 0 si rien n'est pesé. */
    val weightG: Int,
    /** Passage noté « Spot vide ». */
    val emptyVisit: Boolean,
)

@Dao
interface SortieDao {
    @Upsert suspend fun upsert(s: Sortie)
    @Upsert suspend fun upsertPoints(points: List<TrackPoint>)
    @Query("SELECT * FROM sortie WHERE id = :id") suspend fun get(id: String): Sortie?
    @Query("SELECT * FROM sortie WHERE id = :id") fun flow(id: String): Flow<Sortie?>
    @Query("SELECT * FROM sortie WHERE deletedAt IS NULL ORDER BY startedAt DESC")
    fun all(): Flow<List<Sortie>>
    @Query("SELECT * FROM sortie WHERE endedAt IS NULL AND deletedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    fun current(): Flow<Sortie?>
    @Query("SELECT * FROM track_point WHERE sortieId = :sortieId ORDER BY time")
    fun track(sortieId: String): Flow<List<TrackPoint>>

    /** Sortie en cours (lecture ponctuelle, pour le service relancé par Android). */
    @Query("SELECT * FROM sortie WHERE endedAt IS NULL AND deletedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun currentOnce(): Sortie?

    /** Trace complète (lecture ponctuelle, pour le calcul de distance hors écran). */
    @Query("SELECT * FROM track_point WHERE sortieId = :sortieId ORDER BY time")
    suspend fun trackOnce(sortieId: String): List<TrackPoint>

    /** Termine une sortie. */
    @Query("UPDATE sortie SET endedAt = :at, updatedAt = :at WHERE id = :id AND endedAt IS NULL")
    suspend fun end(id: String, at: Long)

    /** Enregistre la position de départ (voiture) si elle n'était pas encore connue. */
    @Query("UPDATE sortie SET startLat = :lat, startLon = :lon, updatedAt = :at WHERE id = :id AND startLat IS NULL")
    suspend fun setStartIfMissing(id: String, lat: Double, lon: Double, at: Long)

    /** Nombre de spots différents visités pendant une sortie. */
    @Query("SELECT COUNT(DISTINCT spotId) FROM spot_visit WHERE sortieId = :sortieId")
    fun spotCount(sortieId: String): Flow<Int>

    /** Nombre total de spots jamais créés (sert à nommer « Spot N »). */
    @Query("SELECT COUNT(*) FROM spot")
    suspend fun spotTotal(): Int

    /** Tous les spots pour la carte, avec la couleur de l'espèce de leur dernière récolte. */
    @Query(
        """
        SELECT s.id AS id, s.name AS name, s.lat AS lat, s.lon AS lon, s.status AS status,
            (SELECT sp.colorHex FROM recolte r
                JOIN recolte_item ri ON ri.recolteId = r.id AND ri.deletedAt IS NULL
                JOIN species sp ON sp.id = ri.speciesId
                WHERE r.spotId = s.id AND r.deletedAt IS NULL
                ORDER BY r.time DESC, ri.weightG DESC LIMIT 1) AS colorHex,
            (SELECT sp.id FROM recolte r
                JOIN recolte_item ri ON ri.recolteId = r.id AND ri.deletedAt IS NULL
                JOIN species sp ON sp.id = ri.speciesId
                WHERE r.spotId = s.id AND r.deletedAt IS NULL
                ORDER BY r.time DESC, ri.weightG DESC LIMIT 1) AS speciesId,
            (SELECT sp.nameFr FROM recolte r
                JOIN recolte_item ri ON ri.recolteId = r.id AND ri.deletedAt IS NULL
                JOIN species sp ON sp.id = ri.speciesId
                WHERE r.spotId = s.id AND r.deletedAt IS NULL
                ORDER BY r.time DESC, ri.weightG DESC LIMIT 1) AS speciesName
        FROM spot s WHERE s.deletedAt IS NULL
        """
    )
    fun spotMarkers(): Flow<List<SpotMarker>>

    /** Spots visités pendant une sortie, avec ce qui y a été saisi (fin de sortie). */
    @Query(
        """
        SELECT s.id AS id, s.name AS name,
            (SELECT COUNT(*) FROM recolte_item ri JOIN recolte r ON r.id = ri.recolteId
                WHERE r.spotId = s.id AND r.sortieId = :sortieId AND r.deletedAt IS NULL AND ri.deletedAt IS NULL) AS items,
            (SELECT COALESCE(SUM(ri.weightG), 0) FROM recolte_item ri JOIN recolte r ON r.id = ri.recolteId
                WHERE r.spotId = s.id AND r.sortieId = :sortieId AND r.deletedAt IS NULL AND ri.deletedAt IS NULL) AS weightG,
            EXISTS(SELECT 1 FROM spot_visit v WHERE v.spotId = s.id AND v.sortieId = :sortieId AND v.empty = 1) AS emptyVisit
        FROM spot s
        WHERE s.deletedAt IS NULL AND s.id IN (SELECT spotId FROM spot_visit WHERE sortieId = :sortieId)
        ORDER BY (SELECT MIN(time) FROM spot_visit v2 WHERE v2.spotId = s.id AND v2.sortieId = :sortieId)
        """
    )
    fun spotsSummary(sortieId: String): Flow<List<SortieSpotSummary>>
}
