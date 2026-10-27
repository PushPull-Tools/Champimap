package fr.pushpull.champimap.feature.spot

import android.content.Context
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.db.PhotoOwner
import fr.pushpull.champimap.core.db.Photo
import fr.pushpull.champimap.core.db.PositionSource
import fr.pushpull.champimap.core.db.Recolte
import fr.pushpull.champimap.core.db.RecolteItem
import fr.pushpull.champimap.core.db.Spot
import fr.pushpull.champimap.core.db.SpotPosition
import fr.pushpull.champimap.core.db.SpotVisit
import fr.pushpull.champimap.core.newId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Calendar

/*
 * Opérations métier du module spot (base Room) : modification d'un spot, récoltes,
 * espèces récoltées, visites, positions et photos.
 * Toutes les écritures « lire puis modifier » passent par un verrou pour que deux
 * enregistrements automatiques rapprochés ne s'écrasent pas.
 */
object SpotRepository {

    /** `sortieId` d'une récolte ou d'une visite faite hors sortie (fiche ouverte depuis la carte, etc.). */
    const val HORS_SORTIE = ""

    private val db get() = AppGraph.db
    private val mutex = Mutex()

    private fun now() = System.currentTimeMillis()

    /** Identifiant de la personne « moi » (auteur des saisies). */
    suspend fun meId(): String = db.personDao().me()?.id ?: "moi"

    // ---------------------------------------------------------------------------------------
    // Spot
    // ---------------------------------------------------------------------------------------

    /** Modifie un spot (lecture fraîche + copie), et met à jour `updatedAt`. */
    suspend fun updateSpot(spotId: String, change: (Spot) -> Spot) = mutex.withLock {
        val s = db.spotDao().get(spotId) ?: return@withLock
        db.spotDao().upsert(change(s).copy(updatedAt = now()))
    }

    /** « Spot vide » : visite horodatée sans récolte. Renvoie la visite (pour « Annuler »). */
    suspend fun markEmpty(spotId: String, sortieId: String?): SpotVisit {
        val v = SpotVisit(
            id = newId(), spotId = spotId, sortieId = sortieId ?: HORS_SORTIE,
            time = now(), empty = true, authorId = meId(),
        )
        db.spotDao().upsertVisit(v)
        return v
    }

    suspend fun undoVisit(visitId: String) = db.spotDao().deleteVisit(visitId)

    // ---------------------------------------------------------------------------------------
    // Récoltes
    // ---------------------------------------------------------------------------------------

    /**
     * Récolte à afficher en ouvrant la fiche, SANS en créer :
     * celle de la sortie en cours si `sortieId` est fourni, sinon la dernière du spot.
     */
    suspend fun findRecolte(spotId: String, sortieId: String?): Recolte? =
        if (sortieId != null) db.recolteDao().ofSpotAndSortie(spotId, sortieId)
        else db.recolteDao().latestOfSpot(spotId)

    /**
     * Récolte du jour, créée à la volée si besoin (premier « Trouvé ! ») :
     * pour la sortie courante si `sortieId` est fourni (+ visite de la sortie), sinon hors sortie.
     * `forceNew` = nouvelle récolte hors sortie même s'il en existe déjà une.
     */
    suspend fun ensureRecolte(spotId: String, sortieId: String?, forceNew: Boolean = false): Recolte = mutex.withLock {
        if (!forceNew) {
            val existing = if (sortieId != null) db.recolteDao().ofSpotAndSortie(spotId, sortieId)
            else db.recolteDao().latestOfSpot(spotId)
            if (existing != null) return@withLock existing
        }
        val me = meId()
        val r = Recolte(id = newId(), spotId = spotId, sortieId = sortieId ?: HORS_SORTIE, time = now(), authorId = me)
        db.recolteDao().upsert(r)
        // Le spot est rattaché à la sortie par une visite (utilisée par la carte et le récap).
        if (sortieId != null && db.spotDao().visitOf(spotId, sortieId) == null) {
            db.spotDao().upsertVisit(SpotVisit(id = newId(), spotId = spotId, sortieId = sortieId, time = now(), authorId = me))
        }
        r
    }

    suspend fun updateRecolte(recolteId: String, change: (Recolte) -> Recolte) = mutex.withLock {
        val r = db.recolteDao().get(recolteId) ?: return@withLock
        db.recolteDao().upsert(change(r).copy(updatedAt = now()))
    }

    /**
     * Ajoute une espèce à une récolte. Les photos « en attente » (prises avec « Trouvé ! → Photo »
     * avant le choix de l'espèce, rattachées à la récolte) sont transférées sur cette espèce.
     */
    suspend fun addItem(recolteId: String, speciesId: String): RecolteItem? = mutex.withLock {
        val r = db.recolteDao().get(recolteId) ?: return@withLock null
        if (r.locked) return@withLock null
        // Espèce déjà présente dans cette récolte : on y rattache la photo au lieu de créer un doublon.
        val existing = db.recolteDao().itemsList(recolteId).firstOrNull { it.speciesId == speciesId }
        val item = existing ?: RecolteItem(id = newId(), recolteId = recolteId, speciesId = speciesId, authorId = meId())
            .also { db.recolteDao().upsertItem(it) }
        db.photoDao().reassign(from = recolteId, to = item.id)
        db.recolteDao().upsert(r.copy(updatedAt = now()))
        item
    }

    /** Vrai si la récolte est verrouillée (cadenas fermé) : rien ne doit y être modifié. */
    private suspend fun recolteLocked(recolteId: String): Boolean =
        db.recolteDao().get(recolteId)?.locked == true

    /** Vrai si l'espèce récoltée appartient à une récolte verrouillée. */
    private suspend fun itemLocked(itemId: String): Boolean {
        val i = db.recolteDao().item(itemId) ?: return false
        return recolteLocked(i.recolteId)
    }

    /** Vrai si la photo appartient à une récolte verrouillée (photo d'espèce ou photo « en attente »). */
    private suspend fun photoLocked(photoId: String): Boolean {
        val p = db.backupDao().photo(photoId) ?: return false
        return p.ownerType == PhotoOwner.RECOLTE_ITEM && (itemLocked(p.ownerId) || recolteLocked(p.ownerId))
    }

    /** Modifie une espèce récoltée (ignoré si la récolte est verrouillée). */
    suspend fun updateItem(itemId: String, change: (RecolteItem) -> RecolteItem) = mutex.withLock {
        val i = db.recolteDao().item(itemId) ?: return@withLock
        if (recolteLocked(i.recolteId)) return@withLock
        db.recolteDao().upsertItem(change(i).copy(updatedAt = now()))
    }

    /** Met une espèce récoltée à la corbeille (ignoré si la récolte est verrouillée). */
    suspend fun trashItem(itemId: String) {
        if (itemLocked(itemId)) return
        db.recolteDao().trashItem(itemId, now())
    }
    suspend fun restoreItem(itemId: String) = db.recolteDao().restoreItem(itemId, now())

    /** Vrai si la date donnée est aujourd'hui (titre « Récolte du jour »). */
    fun isToday(time: Long): Boolean {
        val a = Calendar.getInstance().apply { timeInMillis = time }
        val b = Calendar.getInstance()
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    }

    // ---------------------------------------------------------------------------------------
    // Positions
    // ---------------------------------------------------------------------------------------

    /** Sources de position mesurées par le GPS (prises en compte dans la moyenne). */
    private val MEASURED = setOf(PositionSource.GPS_PRECIS, PositionSource.AU_PIED)

    /**
     * Si l'historique est vide (spot créé sans historique), on y inscrit la position d'origine
     * pour qu'un retour arrière reste possible.
     */
    private suspend fun ensureInitialPosition(s: Spot) {
        if (db.spotDao().positionsList(s.id).isNotEmpty()) return
        db.spotDao().upsertPosition(
            SpotPosition(
                id = newId(), spotId = s.id, lat = s.lat, lon = s.lon, accuracyM = s.accuracyM,
                source = if (s.placedManually) PositionSource.MANUEL else PositionSource.GPS_PRECIS,
                time = s.createdAt, active = true, authorId = s.authorId,
            )
        )
    }

    /**
     * « Je suis au pied » : enregistre la nouvelle mesure, puis place le spot à la moyenne
     * (pondérée par 1/précision²) de toutes les mesures GPS faites depuis le dernier placement
     * à la main / sur la carte (un placement manuel « remet à zéro » la moyenne).
     * La nouvelle mesure devient la position active de l'historique.
     */
    suspend fun addAuPied(spotId: String, fix: PreciseFix) = mutex.withLock {
        val s = db.spotDao().get(spotId) ?: return@withLock
        ensureInitialPosition(s)
        val p = SpotPosition(
            id = newId(), spotId = spotId, lat = fix.lat, lon = fix.lon, accuracyM = fix.accuracyM,
            source = PositionSource.AU_PIED, time = now(), active = false, authorId = meId(),
        )
        db.spotDao().upsertPosition(p)
        db.spotDao().clearActivePositions(spotId)
        db.spotDao().setActivePosition(p.id)

        // Mesures retenues : après le dernier placement manuel (liste triée du plus récent au plus ancien).
        val all = db.spotDao().positionsList(spotId)
        val sinceManual = all.takeWhile { it.source in MEASURED }
        var sw = 0.0; var sLat = 0.0; var sLon = 0.0
        sinceManual.forEach {
            val acc = (it.accuracyM ?: 20f).coerceAtLeast(1f).toDouble()
            val w = 1.0 / (acc * acc)
            sw += w; sLat += w * it.lat; sLon += w * it.lon
        }
        val lat = if (sw > 0) sLat / sw else fix.lat
        val lon = if (sw > 0) sLon / sw else fix.lon
        // Précision combinée (bornée par la meilleure mesure).
        val acc = if (sw > 0) kotlin.math.sqrt(1.0 / sw).toFloat().coerceIn(1f, fix.accuracyM.coerceAtLeast(1f)) else fix.accuracyM
        db.spotDao().upsert(s.copy(lat = lat, lon = lon, accuracyM = acc, placedManually = false, updatedAt = now()))
    }

    /** « Ajuster la position » : nouvelle position choisie sur la carte. */
    suspend fun moveOnMap(spotId: String, lat: Double, lon: Double) = mutex.withLock {
        val s = db.spotDao().get(spotId) ?: return@withLock
        ensureInitialPosition(s)
        val p = SpotPosition(
            id = newId(), spotId = spotId, lat = lat, lon = lon, accuracyM = null,
            source = PositionSource.CARTE, time = now(), active = false, authorId = meId(),
        )
        db.spotDao().upsertPosition(p)
        db.spotDao().clearActivePositions(spotId)
        db.spotDao().setActivePosition(p.id)
        db.spotDao().upsert(s.copy(lat = lat, lon = lon, accuracyM = null, placedManually = true, updatedAt = now()))
    }

    /** « Revenir à cette position » : réactive une position de l'historique. */
    suspend fun revertTo(spotId: String, positionId: String) = mutex.withLock {
        val s = db.spotDao().get(spotId) ?: return@withLock
        val p = db.spotDao().position(positionId) ?: return@withLock
        db.spotDao().clearActivePositions(spotId)
        db.spotDao().setActivePosition(p.id)
        db.spotDao().upsert(
            s.copy(
                lat = p.lat, lon = p.lon, accuracyM = p.accuracyM,
                placedManually = p.source !in MEASURED, updatedAt = now(),
            )
        )
    }

    // ---------------------------------------------------------------------------------------
    // Photos (fichiers dans filesDir/photos/, partageables par le FileProvider « photos/ »)
    // ---------------------------------------------------------------------------------------

    fun photosDir(context: Context): File = File(context.filesDir, "photos").apply { mkdirs() }

    /** Nouveau fichier vide pour l'appareil photo. */
    fun newPhotoFile(context: Context): File = File(photosDir(context), "${newId()}.jpg")

    /**
     * Enregistre une photo prise (fichier déjà écrit) pour un spot, une récolte ou une espèce récoltée.
     * Renvoie null (photo non enregistrée) si elle vise une récolte verrouillée.
     */
    suspend fun addPhoto(ownerType: PhotoOwner, ownerId: String, file: File): Photo? {
        if (ownerType == PhotoOwner.RECOLTE_ITEM && (itemLocked(ownerId) || recolteLocked(ownerId))) return null
        val p = Photo(id = newId(), ownerType = ownerType, ownerId = ownerId, path = file.absolutePath, authorId = meId())
        db.photoDao().upsert(p)
        return p
    }

    /** Met des photos à la corbeille (le fichier reste 30 jours, purge par le module réglages). */
    /** Les photos d'une récolte verrouillée sont ignorées. Renvoie le nombre de photos réellement supprimées. */
    suspend fun trashPhotos(ids: List<String>): Int {
        val allowed = ids.filterNot { photoLocked(it) }
        if (allowed.isNotEmpty()) db.photoDao().trash(allowed, now())
        return allowed.size
    }
}
