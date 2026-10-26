package fr.pushpull.champimap.feature.carnet

import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.ErrorLogger
import fr.pushpull.champimap.core.db.CarnetHarvest
import fr.pushpull.champimap.core.db.PhotoOwner
import fr.pushpull.champimap.core.db.Sortie
import fr.pushpull.champimap.core.db.UnlockedBadge
import fr.pushpull.champimap.core.db.WeatherDay
import fr.pushpull.champimap.feature.sortie.Geo
import fr.pushpull.champimap.feature.stats.zoneIdFor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/*
 * Moteur du carnet : lit les données (CarnetDao), calcule l'XP et les succès (fonctions pures
 * CarnetXp et CarnetRules), enregistre les NOUVEAUX succès avec leur date, et expose l'état aux écrans.
 * Rien n'est incrémenté à la main : tout est recalculé à chaque changement des données.
 */
object CarnetEngine {

    /** Un seul recalcul à la fois (plusieurs écrans peuvent le demander en même temps). */
    private val mutex = Mutex()

    /** Distance par sortie, gardée tant que le nombre de points de trace ne change pas. */
    private val distanceCache = ConcurrentHashMap<String, Pair<Int, Double>>()

    /** Jours de météo nécessaires avant une récolte (succès « Sourcier »). */
    private const val WEATHER_DAYS_BEFORE = 16L

    /**
     * État du carnet, recalculé à chaque changement des sorties, spots, passages, récoltes,
     * réglages ou succès. Les rafales de changements sont regroupées (conflate).
     */
    fun stateFlow(): Flow<CarnetState> {
        val db = AppGraph.db
        val triggers: List<Flow<Any?>> = listOf(
            db.statsDao().sortiesFlow(),
            db.statsDao().harvestsFlow(),
            db.statsDao().visitsFlow(),
            db.spotDao().all(),
            db.carnetDao().badgesFlow(),
            AppGraph.settings.flow,
        )
        return merge(*triggers.toTypedArray())
            .onStart { emit(Unit) }
            .conflate()
            .map { refreshSafely() } // une erreur est journalisée, l'écran garde le dernier état
            .filterNotNull()
            .flowOn(Dispatchers.IO)
    }

    /**
     * Recalcule tout, enregistre les nouveaux succès (date = maintenant, non annoncés)
     * et renvoie l'état complet.
     */
    suspend fun refresh(): CarnetState = mutex.withLock {
        val data = loadData()
        val xp = CarnetXp.compute(data)
        val reached = CarnetRules.evaluate(data)
        val dao = AppGraph.db.carnetDao()
        val known = dao.badges().associateBy { it.id }
        val fresh = reached.filter { it !in known && it in CarnetBadges.BY_ID }
        if (fresh.isNotEmpty()) {
            val now = System.currentTimeMillis()
            // Ordre d'affichage des annonces = ordre de la liste des succès (Premières fois d'abord).
            val ordered = CarnetBadges.ALL.map { it.id }.filter { it in fresh }
            dao.insertBadges(ordered.mapIndexed { i, id -> UnlockedBadge(id = id, unlockedAt = now + i, announced = false) })
        }
        val all = dao.badges()
        CarnetState(
            level = CarnetLevels.levelInfo(xp.total),
            xp = xp,
            unlocked = all.associate { it.id to it.unlockedAt },
            toAnnounce = all.filter { !it.announced }.sortedBy { it.unlockedAt }.map { it.id },
        )
    }

    /** Recalcul sans faire planter l'écran appelant (erreur → journal, état null). */
    suspend fun refreshSafely(): CarnetState? =
        runCatching { refresh() }.onFailure { ErrorLogger.log("Carnet", "Calcul du carnet impossible", it) }.getOrNull()

    /** Marque des succès comme annoncés (carte fermée ou carnet consulté). */
    suspend fun markAnnounced(ids: List<String>) {
        if (ids.isEmpty()) return
        runCatching { AppGraph.db.carnetDao().markAnnounced(ids) }
            .onFailure { ErrorLogger.log("Carnet", "Succès non marqués comme vus", it) }
    }

    /**
     * Fiche de niveau échangeable (prévu pour la sortie à plusieurs, pas encore utilisé) :
     * prénom, niveau, titre, étoiles, XP et succès obtenus. AUCUN spot ni aucune position.
     * À transmettre en JSON : `Json.encodeToString(CarnetSnapshot.serializer(), snapshot())`.
     */
    suspend fun snapshot(): CarnetSnapshot {
        val state = refresh()
        val name = runCatching { AppGraph.settings.flow.first().firstName }.getOrDefault("")
        return CarnetSnapshot(
            firstName = name,
            level = state.level.level,
            title = state.level.title,
            stars = state.level.stars,
            totalXp = state.level.totalXp,
            badges = state.unlocked.entries.sortedBy { it.value }.map { SnapshotBadge(it.key, it.value) },
        )
    }

    // ------------------------------------------------------------------ Lecture des données

    /**
     * Rassemble toutes les données utiles au calcul, limitées à « moi » (auteur = ma Person) :
     * en sortie à plusieurs ou après un import, les saisies des autres ne comptent pas.
     */
    private suspend fun loadData(): CarnetData {
        val dao = AppGraph.db.carnetDao()
        val settings = AppGraph.settings.flow.first()
        val me = dao.me()?.id
        val now = System.currentTimeMillis()

        val sorties = dao.sorties().filter { it.authorId == me }
        val spots = dao.spots().filter { it.authorId == me }
        val visits = dao.visits().filter { it.authorId == me }
        val harvests = dao.harvests().filter { it.authorId == me }
        val photos = dao.photos().filter { it.authorId == me }
        val myItems = harvests.map { it.itemId }.toSet()
        val mySpots = spots.map { it.id }.toSet()

        return CarnetData(
            now = now,
            sorties = sorties,
            spots = spots,
            visits = visits,
            harvests = harvests,
            speciesPhotos = photos.filter { it.ownerType == PhotoOwner.RECOLTE_ITEM && it.ownerId in myItems },
            spotsWithPhoto = photos.filter { it.ownerType == PhotoOwner.SPOT && it.ownerId in mySpots }.map { it.ownerId }.toSet(),
            bestAccuracy = dao.bestAccuracies().mapNotNull { b -> b.best?.let { b.spotId to it } }.toMap(),
            auPiedCount = if (me != null) dao.auPiedCount(me) else 0,
            distanceM = if (me != null) distances(sorties.map { it.id }, me) else emptyMap(),
            weather = loadWeather(sorties, harvests),
            easterUnlocked = settings.easterUnlocked,
            psycheUnlocked = settings.psycheUnlocked,
            champignong = settings.champignong,
            rainsSeen = settings.rainsSeen,
        )
    }

    /** Distance parcourue par sortie (ma trace), avec cache tant que la trace n'a pas changé. */
    private suspend fun distances(sortieIds: List<String>, me: String): Map<String, Double> {
        val dao = AppGraph.db.carnetDao()
        val counts = dao.trackCounts().associate { it.sortieId to it.n }
        val result = HashMap<String, Double>()
        for (id in sortieIds) {
            val n = counts[id] ?: 0
            if (n == 0) continue
            val cached = distanceCache[id]
            val m = if (cached != null && cached.first == n) cached.second
            else Geo.trackLengthM(dao.track(id, me)).also { distanceCache[id] = n to it }
            result[id] = m
        }
        return result
    }

    /** Météo des zones de mes sorties et récoltes (vide si Champimap Connect n'a rien importé). */
    private suspend fun loadWeather(
        sorties: List<Sortie>,
        harvests: List<CarnetHarvest>,
    ): Map<String, WeatherDay> {
        val zone = ZoneId.systemDefault()
        fun day(t: Long): LocalDate = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
        // Points utiles : départs de sortie (s'ils sont connus) et spots récoltés, avec leur date.
        val points = ArrayList<Triple<Double, Double, Long>>()
        for (s in sorties) {
            val la = s.startLat
            val lo = s.startLon
            if (la != null && lo != null) points += Triple(la, lo, s.startedAt)
        }
        harvests.forEach { points += Triple(it.lat, it.lon, it.time) }
        if (points.isEmpty()) return emptyMap()
        val zones = points.map { zoneIdFor(it.first, it.second) }.distinct()
        val from = day(points.minOf { it.third }).minusDays(WEATHER_DAYS_BEFORE).toString()
        val to = day(points.maxOf { it.third }).toString()
        val out = HashMap<String, WeatherDay>()
        return runCatching {
            zones.chunked(400).forEach { chunk ->
                AppGraph.db.carnetDao().weather(chunk, from, to).forEach { out["${it.zoneId}|${it.date}"] = it }
            }
            out
        }.getOrDefault(emptyMap())
    }
}
