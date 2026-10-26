package fr.pushpull.champimap.feature.sortie

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.ErrorLogger
import fr.pushpull.champimap.core.db.DepartType
import fr.pushpull.champimap.core.db.Person
import fr.pushpull.champimap.core.db.PositionSource
import fr.pushpull.champimap.core.db.Sortie
import fr.pushpull.champimap.core.db.Spot
import fr.pushpull.champimap.core.db.SpotPosition
import fr.pushpull.champimap.core.db.SpotVisit
import fr.pushpull.champimap.core.newId
import kotlinx.coroutines.flow.first

/*
 * Opérations sur les données d'une sortie : démarrer, terminer, créer un spot, noter un passage.
 * Toutes les fonctions sont « suspend » : à appeler depuis une coroutine (jamais sur le fil principal bloqué).
 */
object SortieActions {

    /** Identifiant de « moi » (créé si l'accueil ne l'a pas encore fait). */
    suspend fun myId(): String {
        val dao = AppGraph.db.personDao()
        dao.me()?.let { return it.id }
        val name = runCatching { AppGraph.settings.flow.first().firstName }.getOrNull().orEmpty().ifBlank { "Moi" }
        val me = Person(id = newId(), name = name, isMe = true)
        dao.upsert(me)
        return me.id
    }

    /**
     * Crée une nouvelle sortie. La position de départ (voiture ou départ à pied) est celle du GPS
     * si elle est assez précise ; sinon le service la complétera au premier bon point.
     */
    suspend fun createSortie(departType: DepartType, start: GpsFix?): Sortie {
        val goodStart = start?.takeIf { it.accuracyM <= 50f && System.currentTimeMillis() - it.time < 120_000 }
        val sortie = Sortie(
            id = newId(),
            startedAt = System.currentTimeMillis(),
            departType = departType,
            startLat = goodStart?.lat,
            startLon = goodStart?.lon,
            authorId = myId(),
        )
        AppGraph.db.sortieDao().upsert(sortie)
        return sortie
    }

    /** Termine la sortie et arrête le suivi GPS. */
    suspend fun endSortie(context: Context, sortieId: String) {
        AppGraph.db.sortieDao().end(sortieId, System.currentTimeMillis())
        SortieTrackingService.stop(context)
    }

    /** Lance (ou relance) le service de suivi pour une sortie en cours. */
    fun startTracking(context: Context, sortieId: String) {
        if (!LocationUtils.hasFineLocation(context)) return
        try {
            val intent = Intent(context, SortieTrackingService::class.java)
                .setAction(SortieTrackingService.ACTION_START)
                .putExtra(SortieTrackingService.EXTRA_SORTIE_ID, sortieId)
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            ErrorLogger.log("Sortie", "Impossible de lancer le suivi GPS", e)
        }
    }

    /**
     * Crée un spot à une position, avec son historique de position et un passage pour la sortie.
     * Le nom par défaut est « Spot N (lat ; lon) ».
     * @return l'identifiant du nouveau spot.
     */
    suspend fun createSpot(
        lat: Double,
        lon: Double,
        accuracyM: Float?,
        sortieId: String?,
        source: PositionSource,
        placedManually: Boolean,
    ): String {
        val me = myId()
        val now = System.currentTimeMillis()
        val n = AppGraph.db.sortieDao().spotTotal() + 1
        val spot = Spot(
            id = newId(),
            name = "Spot $n (${Geo.formatCoords(lat, lon)})",
            lat = lat,
            lon = lon,
            accuracyM = accuracyM,
            placedManually = placedManually,
            authorId = me,
            createdAt = now,
            updatedAt = now,
        )
        val spotDao = AppGraph.db.spotDao()
        spotDao.upsert(spot)
        spotDao.upsertPosition(
            SpotPosition(
                id = newId(), spotId = spot.id, lat = lat, lon = lon, accuracyM = accuracyM,
                source = source, time = now, active = true, authorId = me,
            ),
        )
        if (sortieId != null) addVisit(spot.id, sortieId)
        return spot.id
    }

    /** Note un passage sur un spot existant pendant la sortie. */
    suspend fun addVisit(spotId: String, sortieId: String) {
        AppGraph.db.spotDao().upsertVisit(
            SpotVisit(id = newId(), spotId = spotId, sortieId = sortieId, time = System.currentTimeMillis(), authorId = myId()),
        )
    }
}
