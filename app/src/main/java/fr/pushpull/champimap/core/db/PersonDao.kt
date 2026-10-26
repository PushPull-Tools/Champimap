package fr.pushpull.champimap.core.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/* Accès aux personnes : soi-même (isMe) et les proches rencontrés en sortie partagée (module sortie). */
@Dao
interface PersonDao {
    @Upsert suspend fun upsert(p: Person)
    @Query("SELECT * FROM person WHERE isMe = 1 LIMIT 1") suspend fun me(): Person?
    @Query("SELECT * FROM person WHERE isMe = 1 LIMIT 1") fun meFlow(): Flow<Person?>
    @Query("SELECT * FROM person WHERE id = :id") suspend fun get(id: String): Person?
    @Query("SELECT * FROM person") fun all(): Flow<List<Person>>

    /** Les autres participants d'une sortie partagée (auteurs des sorties du même groupe). */
    @Query(
        """
        SELECT DISTINCT p.* FROM person p JOIN sortie s ON s.authorId = p.id
        WHERE s.groupId = :groupId AND p.isMe = 0 ORDER BY p.name
        """
    )
    fun companionsOfGroup(groupId: String): Flow<List<Person>>
}
