package fr.pushpull.champimap.core.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonDao {
    @Upsert suspend fun upsert(p: Person)
    @Query("SELECT * FROM person WHERE isMe = 1 LIMIT 1") suspend fun me(): Person?
    @Query("SELECT * FROM person WHERE isMe = 1 LIMIT 1") fun meFlow(): Flow<Person?>
    @Query("SELECT * FROM person WHERE id = :id") suspend fun get(id: String): Person?
    @Query("SELECT * FROM person") fun all(): Flow<List<Person>>
}
