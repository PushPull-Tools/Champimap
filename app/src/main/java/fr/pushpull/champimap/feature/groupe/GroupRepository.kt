package fr.pushpull.champimap.feature.groupe

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import androidx.room.withTransaction
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.db.DepartType
import fr.pushpull.champimap.core.db.Person
import fr.pushpull.champimap.core.db.Photo
import fr.pushpull.champimap.core.db.PositionSource
import fr.pushpull.champimap.core.db.Recolte
import fr.pushpull.champimap.core.db.RecolteItem
import fr.pushpull.champimap.core.db.Sortie
import fr.pushpull.champimap.core.db.Spot
import fr.pushpull.champimap.core.db.SpotPosition
import fr.pushpull.champimap.core.db.SpotVisit
import fr.pushpull.champimap.core.newId
import kotlinx.coroutines.flow.first
import java.io.File

/*
 * Données du module groupe : ce qu'un groupe (sortie à plusieurs) contient, résumé de ce
 * qu'on possède, calcul des changements à envoyer, et FUSION des données reçues.
 *
 * Règle de fusion : pour chaque élément, la version dont `updatedAt` est la plus récente
 * gagne. Exceptions :
 *  - la personne « moi » n'est jamais écrasée par une donnée reçue ;
 *  - dans une sortie partagée, la voiture, le type de départ, la fin et la corbeille restent
 *    personnels (chacun termine SA sortie, chacun a SA voiture) ;
 *  - positions de spot et points de trace sont immuables : ajoutés s'ils manquent ;
 *  - photos : seule la mise à la corbeille se propage (le fichier voyage à part).
 */
object GroupRepository {

    private val db get() = AppGraph.db
    private val dao get() = AppGraph.db.syncDao()

    /** Taille des paquets pour les requêtes `IN (...)` (limite SQLite : 999 paramètres). */
    private const val CHUNK = 500

    /** Nombre maximum de points de trace envoyés par auteur et par échange. */
    private const val TRACK_LIMIT = 3000

    /** Exécute une requête `IN (:ids)` par paquets et concatène les résultats. */
    private suspend fun <T> inChunks(ids: Collection<String>, query: suspend (List<String>) -> List<T>): List<T> {
        if (ids.isEmpty()) return emptyList()
        val out = ArrayList<T>()
        for (part in ids.distinct().chunked(CHUNK)) out += query(part)
        return out
    }

    // ------------------------------------------------------------------------------- Moi

    /** La personne « moi » (créée à partir du prénom des réglages si elle manque). */
    suspend fun me(): Person {
        dao.me()?.let { return it }
        val name = AppGraph.settings.flow.first().firstName.ifBlank { "Moi" }
        val p = Person(id = newId(), name = name, isMe = true)
        dao.upsertPerson(p)
        return p
    }

    /** Enregistre le prénom d'un proche s'il est encore inconnu (reçu par QR ou bonjour réseau). */
    suspend fun rememberPerson(personId: String, name: String) {
        if (personId.isBlank() || name.isBlank()) return
        val local = dao.person(personId)
        if (local == null) dao.upsertPerson(Person(id = personId, name = name, isMe = false, updatedAt = 0L))
    }

    // ------------------------------------------------------------------------------- Photos

    /** Dossier des photos de l'appli (le même que celui du module spot). */
    fun photosDir(context: Context): File = File(context.filesDir, "photos").apply { mkdirs() }

    /** Emplacement local d'une photo reçue d'un autre téléphone. */
    fun receivedPhotoFile(context: Context, photoId: String): File = File(photosDir(context), "$photoId.jpg")

    /** Un identifiant reçu n'est utilisé comme nom de fichier que s'il est « propre ». */
    fun isSafeId(id: String): Boolean = id.length in 1..64 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    /**
     * Enregistre une photo reçue (fichier + fiche). Le fichier est écrit dans un fichier
     * temporaire puis renommé : jamais de photo à moitié écrite.
     */
    suspend fun savePhoto(context: Context, dto: PhotoDto, bytes: ByteArray) {
        if (!isSafeId(dto.id)) return
        val local = dao.photo(dto.id)
        if (local != null && File(local.path).exists()) return // déjà là
        val target = receivedPhotoFile(context, dto.id)
        if (!target.exists()) {
            val tmp = File(target.parentFile, "${dto.id}.part")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return
            }
        }
        dao.upsertPhoto(local?.copy(path = target.absolutePath) ?: dto.toEntity(target.absolutePath))
    }

    // ------------------------------------------------------------------------------- Sorties

    /** Donne un identifiant de groupe à la sortie (s'il n'en a pas). Renvoie null si la sortie n'existe pas. */
    suspend fun ensureGroupId(sortieId: String): String? {
        val s = dao.sortie(sortieId) ?: return null
        s.groupId?.let { return it }
        val groupId = newId()
        dao.upsertSortie(s.copy(groupId = groupId, updatedAt = System.currentTimeMillis()))
        return groupId
    }

    /**
     * Rejoindre une sortie : crée localement la sortie partagée (même identifiant et même
     * groupe que le créateur), départ « à pied » depuis la dernière position connue.
     */
    suspend fun joinSortie(context: Context, invite: GroupInvite) {
        val me = me()
        rememberPerson(invite.personId, invite.name)
        val existing = dao.sortie(invite.sortieId)
        val now = System.currentTimeMillis()
        if (existing == null) {
            val loc = lastKnownLocation(context)
            dao.upsertSortie(
                Sortie(
                    id = invite.sortieId, startedAt = now, departType = DepartType.FOOT,
                    startLat = loc?.first, startLon = loc?.second, groupId = invite.groupId,
                    authorId = me.id, updatedAt = 0L, // 0 : les infos partagées du créateur gagneront
                ),
            )
        } else if (existing.groupId != invite.groupId || existing.endedAt != null) {
            dao.upsertSortie(existing.copy(groupId = invite.groupId, endedAt = null, deletedAt = null))
        }
    }

    /** Dernière position connue (GPS puis réseau), ou null sans autorisation. */
    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(context: Context): Pair<Double, Double>? = try {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        l?.let { it.latitude to it.longitude }
    } catch (e: SecurityException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    // ------------------------------------------------------------------------------- Contenu d'un groupe

    /** Tout ce qui appartient à un groupe (hors points de trace, traités à part). */
    class GroupData(
        val sortieIds: List<String>,
        val persons: List<Person>,
        val sorties: List<Sortie>,
        val spots: List<Spot>,
        val positions: List<SpotPosition>,
        val visits: List<SpotVisit>,
        val recoltes: List<Recolte>,
        val items: List<RecolteItem>,
        val photos: List<Photo>,
        val trackMax: Map<String, Long>,
    )

    /**
     * Rassemble les données du groupe : ses sorties, les spots visités ou récoltés pendant
     * ces sorties (avec leurs positions et photos), les récoltes de ces sorties, et les
     * personnes concernées.
     */
    suspend fun collect(groupId: String): GroupData {
        val sorties = dao.sortiesOfGroup(groupId)
        val sortieIds = sorties.map { it.id }
        val visits = inChunks(sortieIds) { dao.visitsOfSorties(it) }
        val recoltes = inChunks(sortieIds) { dao.recoltesOfSorties(it) }
        val spotIds = (visits.map { it.spotId } + recoltes.map { it.spotId }).toSet()
        val spots = inChunks(spotIds) { dao.spots(it) }
        val positions = inChunks(spotIds) { dao.positionsOfSpots(it) }
        val items = inChunks(recoltes.map { it.id }) { dao.itemsOfRecoltes(it) }
        val photos = inChunks(spotIds + items.map { it.id }) { dao.photosOfOwners(it) }
        val trackMax = inChunks(sortieIds) { dao.trackMaxByAuthor(it) }
            .groupBy { it.authorId }.mapValues { e -> e.value.maxOf { it.maxTime } }

        // Personnes : auteurs de toutes ces données + moi.
        val authorIds = HashSet<String>().apply {
            add(me().id)
            sorties.forEach { add(it.authorId) }
            spots.forEach { add(it.authorId) }
            visits.forEach { add(it.authorId) }
            recoltes.forEach { add(it.authorId) }
            items.forEach { add(it.authorId) }
            photos.forEach { add(it.authorId) }
            addAll(trackMax.keys)
        }
        val persons = inChunks(authorIds) { dao.persons(it) }
        return GroupData(sortieIds, persons, sorties, spots, positions, visits, recoltes, items, photos, trackMax)
    }

    /** Résumé de ce que l'on possède (envoyé à l'autre au début de chaque échange). */
    fun summaryOf(d: GroupData): SyncSummary {
        val v = HashMap<String, Long>()
        d.persons.forEach { v["P:${it.id}"] = it.updatedAt }
        d.sorties.forEach { v["S:${it.id}"] = it.updatedAt }
        d.spots.forEach { v["T:${it.id}"] = it.updatedAt }
        d.positions.forEach { v["O:${it.id}"] = 0L }
        d.visits.forEach { v["V:${it.id}"] = it.updatedAt }
        d.recoltes.forEach { v["R:${it.id}"] = it.updatedAt }
        d.items.forEach { v["I:${it.id}"] = it.updatedAt }
        d.photos.forEach { v["F:${it.id}"] = it.deletedAt ?: 0L }
        val files = d.photos.filter { File(it.path).exists() }.map { it.id }
        return SyncSummary(versions = v, trackMax = d.trackMax, photoFiles = files)
    }

    /**
     * Changements à envoyer à l'autre : ce qui lui manque ou ce qui est plus récent chez nous.
     * Les nouvelles photos ne sont pas dans ce lot : elles partent avec leur fichier (voir
     * [photosToSend]) ; seule leur mise à la corbeille passe par ici.
     */
    suspend fun diff(d: GroupData, remote: SyncSummary): SyncBundle {
        val rv = remote.versions
        fun newer(key: String, mine: Long): Boolean = rv[key]?.let { mine > it } ?: true

        val track = ArrayList<TrackDto>()
        for ((author, myMax) in d.trackMax) {
            val theirMax = remote.trackMax[author]
            if (theirMax == null || myMax > theirMax) {
                val after = theirMax ?: Long.MIN_VALUE
                track += inChunks(d.sortieIds) { dao.trackAfter(it, author, after, TRACK_LIMIT) }
                    .sortedBy { it.time }.take(TRACK_LIMIT).map { it.toDto() }
            }
        }

        return SyncBundle(
            persons = d.persons.filter { newer("P:${it.id}", it.updatedAt) }.map { it.toDto() },
            sorties = d.sorties.filter { newer("S:${it.id}", it.updatedAt) }.map { it.toDto() },
            spots = d.spots.filter { newer("T:${it.id}", it.updatedAt) }.map { it.toDto() },
            positions = d.positions.filter { "O:${it.id}" !in rv }.map { it.toDto() },
            visits = d.visits.filter { newer("V:${it.id}", it.updatedAt) }.map { it.toDto() },
            recoltes = d.recoltes.filter { newer("R:${it.id}", it.updatedAt) }.map { it.toDto() },
            items = d.items.filter { newer("I:${it.id}", it.updatedAt) }.map { it.toDto() },
            photos = d.photos.filter { p ->
                val theirs = rv["F:${p.id}"]
                theirs != null && (p.deletedAt ?: 0L) > theirs
            }.map { it.toDto() },
            track = track,
        )
    }

    /** Photos dont l'autre n'a pas le fichier (et que l'on possède, hors corbeille). */
    fun photosToSend(d: GroupData, remote: SyncSummary): List<Photo> {
        val theirs = remote.photoFiles.toHashSet()
        return d.photos.filter { it.deletedAt == null && it.id !in theirs && File(it.path).exists() }
            .sortedBy { it.createdAt }
    }

    // ------------------------------------------------------------------------------- Fusion

    /**
     * Fusionne un lot reçu dans la base, en une seule transaction.
     * @return le nombre d'éléments réellement ajoutés ou modifiés.
     */
    suspend fun merge(b: SyncBundle): Int = db.withTransaction {
        var changed = 0
        val myId = dao.me()?.id
        val touchedSpots = HashSet<String>()

        // Personnes (prénoms partagés) — « moi » n'est jamais écrasé.
        for (p in b.persons) {
            if (p.id == myId) continue
            val local = dao.person(p.id)
            if (local?.isMe == true) continue
            if (local == null || p.updatedAt > local.updatedAt) { dao.upsertPerson(p.toEntity()); changed++ }
        }

        // Sorties : les infos personnelles (voiture, fin, corbeille, auteur) restent locales.
        for (s in b.sorties) {
            val local = dao.sortie(s.id)
            if (local == null) { dao.upsertSortie(s.toEntity()); changed++ }
            else if (s.updatedAt > local.updatedAt) {
                dao.upsertSortie(
                    s.toEntity().copy(
                        endedAt = local.endedAt, departType = local.departType,
                        startLat = local.startLat, startLon = local.startLon,
                        authorId = local.authorId, deletedAt = local.deletedAt,
                        groupId = local.groupId ?: s.groupId,
                    ),
                )
                changed++
            }
        }

        // Spots.
        for (s in b.spots) {
            val local = dao.spot(s.id)
            if (local == null || s.updatedAt > local.updatedAt) {
                dao.upsertSpot(s.toEntity(local)); changed++; touchedSpots += s.id
            }
        }

        // Positions (immuables) : ajoutées si absentes.
        for (p in b.positions) {
            if (dao.position(p.id) == null) { dao.insertPosition(p.toEntity()); changed++; touchedSpots += p.spotId }
        }

        // Visites, récoltes, espèces récoltées : la plus récente gagne.
        // (Un spot partagé arrive sans ses sorties : `sortieId` vide ; on garde alors celui connu ici.)
        for (v in b.visits) {
            val local = dao.visit(v.id)
            if (local == null || v.updatedAt > local.updatedAt) {
                val e = v.toEntity()
                dao.upsertVisit(if (local != null && e.sortieId.isEmpty()) e.copy(sortieId = local.sortieId) else e)
                changed++
            }
        }
        for (r in b.recoltes) {
            val local = dao.recolte(r.id)
            if (local == null || r.updatedAt > local.updatedAt) {
                val e = r.toEntity()
                dao.upsertRecolte(if (local != null && e.sortieId.isEmpty()) e.copy(sortieId = local.sortieId) else e)
                changed++
            }
        }
        for (i in b.items) {
            val local = dao.item(i.id)
            if (local == null || i.updatedAt > local.updatedAt) { dao.upsertItem(i.toEntity()); changed++ }
        }

        // Photos : seule la mise à la corbeille se propage.
        for (p in b.photos) {
            val local = dao.photo(p.id) ?: continue
            if (p.deletedAt != null && local.deletedAt == null) { dao.upsertPhoto(local.copy(deletedAt = p.deletedAt)); changed++ }
        }

        // Traces GPS (immuables).
        if (b.track.isNotEmpty()) {
            b.track.chunked(CHUNK).forEach { part -> dao.insertTrackPoints(part.map { it.toEntity() }) }
            changed += b.track.size
        }

        // La position « active » de chaque spot touché suit sa position affichée ; un spot reçu
        // sans historique de positions en reçoit une (origine « importée »).
        for (spotId in touchedSpots) {
            val s = dao.spot(spotId) ?: continue
            if (dao.countPositionsAt(spotId, s.lat, s.lon) == 0) {
                dao.insertPosition(
                    SpotPosition(
                        id = newId(), spotId = spotId, lat = s.lat, lon = s.lon, accuracyM = s.accuracyM,
                        source = PositionSource.IMPORT, active = true, authorId = s.authorId,
                    ),
                )
            }
            dao.setActivePositionAt(spotId, s.lat, s.lon)
        }
        changed
    }
}
