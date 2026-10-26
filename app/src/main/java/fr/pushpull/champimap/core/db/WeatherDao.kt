package fr.pushpull.champimap.core.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/*
 * Météo par zone large (~25 km), importée par Champimap Connect (module stats).
 * Les dates sont des chaînes ISO « AAAA-MM-JJ » : l'ordre alphabétique = l'ordre chronologique.
 */
@Dao
interface WeatherDao {
    @Upsert suspend fun upsertAll(days: List<WeatherDay>)

    @Query("SELECT * FROM weather_day WHERE zoneId = :zoneId AND date BETWEEN :from AND :to ORDER BY date")
    suspend fun range(zoneId: String, from: String, to: String): List<WeatherDay>

    /** Météo de plusieurs zones sur une période (moteur de corrélation). */
    @Query("SELECT * FROM weather_day WHERE zoneId IN (:zoneIds) AND date BETWEEN :from AND :to")
    suspend fun forZones(zoneIds: List<String>, from: String, to: String): List<WeatherDay>

    /** Nombre de jours déjà connus pour une zone (pour ne redemander que ce qui manque). */
    @Query("SELECT COUNT(*) FROM weather_day WHERE zoneId = :zoneId AND date BETWEEN :from AND :to")
    suspend fun countInRange(zoneId: String, from: String, to: String): Int

    /** Nombre total de jours de météo en base (affichage « météo disponible ou non »). */
    @Query("SELECT COUNT(*) FROM weather_day")
    fun countFlow(): Flow<Int>
}
