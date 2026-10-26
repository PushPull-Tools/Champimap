package fr.pushpull.champimap.core.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface WeatherDao {
    @Upsert suspend fun upsertAll(days: List<WeatherDay>)
    @Query("SELECT * FROM weather_day WHERE zoneId = :zoneId AND date BETWEEN :from AND :to ORDER BY date")
    suspend fun range(zoneId: String, from: String, to: String): List<WeatherDay>
}
