package fr.pushpull.champimap.feature.groupe

import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseCallback
import android.content.Context
import android.os.Build
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.ErrorLogger
import fr.pushpull.champimap.core.db.Photo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/*
 * Synchronisation en direct d'une sortie à plusieurs, en Bluetooth Low Energy
 * (canaux L2CAP, voir BleLink.kt) : aucun socket IP, aucun Internet.
 *
 * UTILISATION (API publique) :
 *  - `GroupSync.start(context, groupId)` : démarre (ou garde) la synchro du groupe. Appelé par
 *    les écrans « Sortie à plusieurs » et « Rejoindre » ; peut être rappelé sans risque
 *    (par ex. par l'écran de sortie en cours pour une sortie dont `groupId` n'est pas nul).
 *    Le rôle (hôte = créateur, ou invité) est mémorisé par le module : rien à préciser.
 *  - `GroupSync.stop()` : arrête la synchro.
 *  - `GroupSync.leave(context)` : arrête la synchro (et l'annonce Bluetooth) ; même effet que stop().
 *  - `GroupSync.syncNow()` : demande un échange immédiat (côté invité).
 *  - `GroupSync.state` : état observable (participants, « synchro OK »), voir [GroupState].
 *    Texte prêt à afficher dans la pastille de la sortie en cours : `state.value.label()`.
 *  La synchro tourne dans `AppGraph.appScope` : elle continue quand on quitte l'écran
 *  (pendant toute la sortie) et s'arrête d'elle-même quand la sortie du groupe est terminée
 *  ou mise à la corbeille sur ce téléphone.
 *
 * FONCTIONNEMENT : pas de maître pour les données (tous égaux, fusion « la plus récente
 * gagne », voir GroupRepository). Côté transport, le créateur de la sortie est l'hôte : il
 * s'annonce en BLE et accepte jusqu'à 6 invités sur son canal L2CAP, et RELAIE (il fusionne
 * tout et le retransmet aux autres au tour suivant). Chaque invité échange avec lui toutes
 * les 30 à 60 s, et dès la (re)connexion. Portée : quelques dizaines de mètres ; hors de
 * portée, chacun continue seul et tout se resynchronise au regroupement ou à la voiture.
 */

/** État de la synchro de groupe, pour l'interface. */
data class GroupState(
    val groupId: String? = null,
    val status: Status = Status.ARRETE,
    /** Ce téléphone est-il l'hôte (créateur de la sortie, qui relaie les autres) ? */
    val isHost: Boolean = false,
    /** Les autres participants (prénoms) avec leur dernier échange réussi. */
    val peers: List<PeerInfo> = emptyList(),
    /** Instant du dernier échange réussi (0 = jamais). */
    val lastSyncAt: Long = 0L,
    /** Photos qu'il reste à envoyer (elles partent par petits morceaux, en tâche de fond). */
    val photosPending: Int = 0,
    /** Le Bluetooth est coupé : l'écran propose de l'activer. */
    val bluetoothOff: Boolean = false,
    /** Dernier problème rencontré (texte lisible), null si tout va bien. */
    val message: String? = null,
) {
    enum class Status {
        /** Synchro arrêtée. */
        ARRETE,

        /** Android trop ancien (avant Android 10) : sortie à plusieurs impossible. */
        INCOMPATIBLE,

        /** Bluetooth coupé ou autorisation manquante. */
        BLUETOOTH_INDISPONIBLE,

        /** Hôte : en attente des invités. */
        ATTENTE_PARTICIPANTS,

        /** Invité : recherche de l'hôte. */
        RECHERCHE,

        /** Invité : hôte introuvable (trop loin). */
        HORS_PORTEE,

        /** Connexion en cours. */
        CONNEXION,

        /** Connecté et échanges en cours. */
        CONNECTE,
    }

    /** Synchro OK = connecté et dernier échange il y a moins de 2 minutes. */
    val syncOk: Boolean
        get() = status == Status.CONNECTE && System.currentTimeMillis() - lastSyncAt < SYNC_OK_MS

    /** Texte de la pastille : « Avec Julie · synchro OK », « Avec Julie, Marc · hors de portée »… */
    fun label(): String {
        val names = peers.map { it.name }.filter { it.isNotBlank() }
        val with = if (names.isEmpty()) "Sortie à plusieurs" else "Avec " + names.joinToString(", ")
        val st = when {
            syncOk -> "synchro OK"
            status == Status.ARRETE -> "synchro arrêtée"
            status == Status.INCOMPATIBLE -> "Android 10 minimum"
            status == Status.BLUETOOTH_INDISPONIBLE -> "Bluetooth coupé"
            status == Status.ATTENTE_PARTICIPANTS -> "en attente des autres"
            status == Status.RECHERCHE || status == Status.CONNEXION -> "connexion…"
            else -> "hors de portée"
        }
        return "$with · $st"
    }

    companion object {
        const val SYNC_OK_MS = 120_000L
    }
}

object GroupSync {

    private val _state = MutableStateFlow(GroupState())

    /** État observable de la synchro (participants, « synchro OK »). */
    val state: StateFlow<GroupState> = _state.asStateFlow()

    private var job: Job? = null
    private var currentGroupId: String? = null

    /** Demandes d'échange immédiat. */
    private val trigger = Channel<Unit>(Channel.CONFLATED)

    /** Les fusions (plusieurs invités à la fois chez l'hôte) passent une par une. */
    private val mergeMutex = Mutex()

    /** Invités connectés à l'hôte (personId → infos). */
    private val roster = ConcurrentHashMap<String, PeerInfo>()

    /** Nombre maximum d'invités connectés en même temps à l'hôte. */
    private const val MAX_GUESTS = 6

    /** Durée d'un scan de recherche de l'hôte, et pause entre deux scans (batterie). */
    private const val SCAN_MS = 15_000L
    private const val SCAN_PAUSE_MS = 15_000L

    /** Délai avant le tour suivant quand des photos sont en route. */
    private const val PHOTO_ROUND_MS = 4_000L

    /** Fichier de préférences du module : groupes dont ce téléphone est l'hôte. */
    private const val PREFS = "groupe"
    private const val KEY_HOSTED = "hostedGroups"

    // ------------------------------------------------------------------------------- API publique

    /** Démarre la synchro du groupe (sans effet si elle tourne déjà pour ce groupe). */
    @Synchronized
    fun start(context: Context, groupId: String) {
        if (currentGroupId == groupId && job?.isActive == true) return
        job?.cancel()
        currentGroupId = groupId
        roster.clear()
        val app = context.applicationContext
        val host = isHostOf(app, groupId)
        _state.value = GroupState(groupId = groupId, isHost = host, status = GroupState.Status.CONNEXION)
        job = AppGraph.appScope.launch { runLoop(app, groupId, host) }
    }

    /** Arrête la synchro. */
    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        currentGroupId = null
        roster.clear()
        _state.value = GroupState()
    }

    /** Arrête la synchro et l'annonce Bluetooth (rien d'autre à fermer en Bluetooth). */
    fun leave(context: Context) {
        stop()
    }

    /** Demande un échange tout de suite. */
    fun syncNow() {
        trigger.trySend(Unit)
    }

    /** Ce téléphone devient l'hôte de ce groupe (appelé par l'écran « Sortie à plusieurs »). */
    internal fun markHost(context: Context, groupId: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_HOSTED, emptySet()).orEmpty().toMutableSet()
        // On ne garde que les 20 derniers groupes (la liste ne grossit pas indéfiniment).
        if (set.size > 20) set.clear()
        set += groupId
        prefs.edit().putStringSet(KEY_HOSTED, set).apply()
    }

    /** Ce téléphone est-il l'hôte de ce groupe ? */
    private fun isHostOf(context: Context, groupId: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_HOSTED, emptySet()).orEmpty().contains(groupId)

    // ------------------------------------------------------------------------------- Boucle principale

    /**
     * Boucle de vie : vérifie Android 10+, le Bluetooth et la sortie, puis joue le rôle
     * d'hôte ou d'invité ; recommence après chaque coupure.
     */
    private suspend fun runLoop(context: Context, groupId: String, host: Boolean) = coroutineScope {
        if (!BleLink.isSupported()) {
            _state.update { it.copy(status = GroupState.Status.INCOMPATIBLE, message = "Sortie à plusieurs : Android 10 minimum.") }
            return@coroutineScope
        }
        while (isActive) {
            if (!sortieActive(groupId)) {
                // Sortie terminée (ou supprimée) : on arrête tout.
                _state.value = GroupState()
                synchronized(this@GroupSync) { if (currentGroupId == groupId) currentGroupId = null }
                break
            }
            if (!BleLink.isEnabled(context)) {
                _state.update { it.copy(status = GroupState.Status.BLUETOOTH_INDISPONIBLE, bluetoothOff = true, message = "Active le Bluetooth.") }
                delay(3_000)
                continue
            }
            _state.update { it.copy(bluetoothOff = false) }
            try {
                if (host) runHost(context, groupId) else runGuest(context, groupId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                _state.update {
                    it.copy(status = GroupState.Status.BLUETOOTH_INDISPONIBLE, message = "Autorisation Bluetooth refusée : ouvre à nouveau l'écran de la sortie à plusieurs.")
                }
                delay(10_000)
            } catch (e: BleLink.BleException) {
                _state.update { it.copy(message = e.message) }
                delay(5_000)
            } catch (e: IOException) {
                // Coupure, téléphone hors de portée… : normal en forêt, on réessaie.
                _state.update { it.copy(status = if (host) GroupState.Status.ATTENTE_PARTICIPANTS else GroupState.Status.HORS_PORTEE) }
            } catch (e: Exception) {
                ErrorLogger.log("Groupe", "Synchro de groupe interrompue", e)
                _state.update { it.copy(message = "Connexion perdue, nouvel essai…") }
            }
            delay(2_000)
        }
    }

    /** La sortie de ce groupe est-elle toujours en cours sur ce téléphone ? */
    private suspend fun sortieActive(groupId: String): Boolean {
        val sorties = AppGraph.db.syncDao().sortiesOfGroup(groupId)
        return sorties.any { it.endedAt == null && it.deletedAt == null }
    }

    /**
     * Exécute `block` en fermant `c` si la coroutine est annulée (les lectures Bluetooth
     * bloquantes ne réagissent pas à l'annulation : fermer la socket les débloque).
     * `c` est aussi fermé à la fin normale de `block`.
     */
    private suspend fun <T> closingOnCancel(c: Closeable, block: suspend () -> T): T = coroutineScope {
        // UNDISPATCHED : le « try » est atteint tout de suite, la fermeture est donc garantie.
        val closer = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                runCatching { c.close() }
            }
        }
        try {
            block()
        } finally {
            closer.cancel()
        }
    }

    // ------------------------------------------------------------------------------- Rôle d'hôte

    /** Hôte : ouvre le canal L2CAP, s'annonce en BLE et sert les invités (relais). */
    private suspend fun runHost(context: Context, groupId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val server = BleLink.openServer(context)
        var advert: AdvertiseCallback? = null
        roster.clear()
        _state.update { it.copy(status = GroupState.Status.ATTENTE_PARTICIPANTS, isHost = true, peers = emptyList(), message = null) }
        try {
            advert = BleLink.startAdvertising(context, groupId, server.psm)
            closingOnCancel(server) {
                coroutineScope {
                    // Surveillance : Bluetooth toujours allumé et sortie toujours en cours ? Sinon on ferme.
                    val watchdog = launch {
                        while (isActive) {
                            delay(10_000)
                            if (!BleLink.isEnabled(context) || !sortieActive(groupId)) {
                                runCatching { server.close() }
                                break
                            }
                            refreshHostState()
                        }
                    }
                    val guests = ArrayList<Job>()
                    while (isActive) {
                        val socket = try {
                            server.accept()
                        } catch (e: IOException) {
                            break // canal fermé (Bluetooth coupé, sortie terminée…)
                        }
                        guests.removeAll { !it.isActive }
                        if (guests.size >= MAX_GUESTS) {
                            runCatching { socket.close() } // complet : l'invité réessaiera plus tard
                            continue
                        }
                        guests += launch { serveGuest(context, groupId, socket) }
                    }
                    watchdog.cancel()
                    guests.forEach { it.cancel() }
                }
            }
        } finally {
            advert?.let { BleLink.stopAdvertising(context, it) }
            runCatching { server.close() }
        }
    }

    /** Hôte : met à jour la liste des participants affichée. */
    private fun refreshHostState() {
        val peers = roster.values.sortedBy { it.name }
        val last = peers.maxOfOrNull { it.lastSyncAt } ?: 0L
        _state.update {
            it.copy(
                peers = peers, lastSyncAt = maxOf(it.lastSyncAt, last),
                status = if (peers.isEmpty()) GroupState.Status.ATTENTE_PARTICIPANTS else GroupState.Status.CONNECTE,
            )
        }
    }

    /** Hôte : dialogue avec un invité, tour après tour, jusqu'à la coupure. */
    private suspend fun serveGuest(context: Context, groupId: String, socket: BluetoothSocket) {
        var peerId: String? = null
        try {
            closingOnCancel(socket) {
                val inp = DataInputStream(BufferedInputStream(socket.inputStream))
                val out = DataOutputStream(BufferedOutputStream(socket.outputStream))
                val me = GroupRepository.me()

                // Présentations : on vérifie que c'est bien la même sortie (le groupId sert de secret).
                val hello = Proto.decode(Proto.read(inp), Proto.T_HELLO, Hello.serializer())
                if (hello.groupId != groupId) return@closingOnCancel
                Proto.writeJson(out, Proto.T_HELLO, Hello.serializer(), Hello(Proto.VERSION, groupId, me.id, me.name))
                GroupRepository.rememberPerson(hello.personId, hello.name)
                peerId = hello.personId
                roster[hello.personId] = PeerInfo(hello.personId, hello.name.take(40), 0L)
                refreshHostState()

                while (true) {
                    // 1. L'invité envoie ce qu'il a.
                    val summary = Proto.decode(Proto.read(inp), Proto.T_SUMMARY, SyncSummary.serializer())
                    // 2. On répond : ce qu'on a, ce qui lui manque, les participants.
                    val data = GroupRepository.collect(groupId)
                    val toHim = GroupRepository.diff(data, summary)
                    val list = listOf(PeerInfo(me.id, me.name, System.currentTimeMillis())) + roster.values
                    Proto.writeJson(
                        out, Proto.T_REPLY, RoundReply.serializer(),
                        RoundReply(GroupRepository.summaryOf(data), toHim, list),
                    )
                    // 3. Il envoie ce qui nous manque : on fusionne (et on le relaiera aux autres).
                    val fromHim = Proto.decode(Proto.read(inp), Proto.T_BUNDLE, SyncBundle.serializer())
                    mergeMutex.withLock { GroupRepository.merge(fromHim) }
                    // 4. Photos par petits morceaux : les siennes d'abord, puis les nôtres.
                    receivePhotoChunks(context, inp)
                    val photos = GroupRepository.photosToSend(data, summary)
                    sendPhotoChunks(out, photos, summary.photoPartial)
                    // Tour réussi.
                    val now = System.currentTimeMillis()
                    roster[hello.personId] = PeerInfo(hello.personId, hello.name.take(40), now)
                    _state.update { it.copy(lastSyncAt = now, message = null, photosPending = photos.size) }
                    refreshHostState()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // Invité parti ou hors de portée : normal en forêt, pas d'entrée au journal.
        } catch (e: Exception) {
            ErrorLogger.log("Groupe", "Échange avec un participant interrompu", e)
        } finally {
            peerId?.let { roster.remove(it) }
            refreshHostState()
        }
    }

    // ------------------------------------------------------------------------------- Rôle d'invité

    /** Invité : cherche l'hôte en BLE, se connecte et échange toutes les 30 à 60 s. */
    private suspend fun runGuest(context: Context, groupId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        _state.update { it.copy(status = GroupState.Status.RECHERCHE, isHost = false) }
        if (BleLink.locationNeededButOff(context)) {
            _state.update { it.copy(message = "Active la localisation du téléphone : Android en a besoin pour trouver les autres en Bluetooth.") }
        }
        val found = BleLink.findHost(context, groupId, SCAN_MS)
        if (found == null) {
            _state.update { it.copy(status = GroupState.Status.HORS_PORTEE) }
            // Pause entre deux recherches (batterie), écourtée par syncNow().
            withTimeoutOrNull(SCAN_PAUSE_MS) { trigger.receive() }
            return
        }

        _state.update { it.copy(status = GroupState.Status.CONNEXION) }
        val socket = BleLink.createSocket(found)
        closingOnCancel(socket) {
            socket.connect() // bloquant ; IOException si l'hôte est parti ou complet
            val inp = DataInputStream(BufferedInputStream(socket.inputStream))
            val out = DataOutputStream(BufferedOutputStream(socket.outputStream))
            val me = GroupRepository.me()

            Proto.writeJson(out, Proto.T_HELLO, Hello.serializer(), Hello(Proto.VERSION, groupId, me.id, me.name))
            val hello = Proto.decode(Proto.read(inp), Proto.T_HELLO, Hello.serializer())
            if (hello.groupId != groupId) return@closingOnCancel
            GroupRepository.rememberPerson(hello.personId, hello.name)

            // Premier échange tout de suite (reconnexion), puis toutes les 30 à 60 s
            // (toutes les quelques secondes tant que des photos sont en route).
            while (true) {
                val photosMoving = guestRound(context, groupId, me.id, inp, out)
                val wait = if (photosMoving) PHOTO_ROUND_MS else Random.nextLong(30_000L, 60_001L)
                withTimeoutOrNull(wait) { trigger.receive() }
                if (!sortieActive(groupId)) break
            }
        }
    }

    /**
     * Invité : un tour d'échange complet avec l'hôte.
     * @return true si des photos ont circulé (il en reste sans doute : tour suivant rapproché).
     */
    private suspend fun guestRound(context: Context, groupId: String, myId: String, inp: DataInputStream, out: DataOutputStream): Boolean {
        // 1. Ce que j'ai.
        val data = GroupRepository.collect(groupId)
        Proto.writeJson(out, Proto.T_SUMMARY, SyncSummary.serializer(), GroupRepository.summaryOf(data))
        // 2. Réponse de l'hôte : ce qui me manque (fusionné) + participants.
        val reply = Proto.decode(Proto.read(inp), Proto.T_REPLY, RoundReply.serializer())
        mergeMutex.withLock { GroupRepository.merge(reply.bundle) }
        reply.roster.forEach { GroupRepository.rememberPerson(it.personId, it.name) }
        // 3. Ce qui manque à l'hôte.
        Proto.writeJson(out, Proto.T_BUNDLE, SyncBundle.serializer(), GroupRepository.diff(data, reply.summary))
        // 4. Photos par petits morceaux : les miennes d'abord, puis celles de l'hôte.
        val photos = GroupRepository.photosToSend(data, reply.summary)
        val sent = sendPhotoChunks(out, photos, reply.summary.photoPartial)
        val received = receivePhotoChunks(context, inp)

        val now = System.currentTimeMillis()
        _state.update {
            it.copy(
                status = GroupState.Status.CONNECTE, isHost = false, lastSyncAt = now, message = null,
                peers = reply.roster.filter { p -> p.personId != myId }.sortedBy { p -> p.name },
                photosPending = photos.size,
            )
        }
        return sent > 0 || received > 0
    }

    // ------------------------------------------------------------------------------- Photos

    /**
     * Envoie des morceaux de photos dans la limite du budget du tour, en reprenant chaque
     * photo là où l'autre en est (`partial`), puis la fin de liste.
     * @return nombre de morceaux envoyés.
     */
    private fun sendPhotoChunks(out: DataOutputStream, photos: List<Photo>, partial: Map<String, Long>): Int {
        var budget = Proto.PHOTO_BUDGET
        var count = 0
        for (p in photos) {
            if (budget <= 0) break
            val f = File(p.path)
            val total = f.length()
            if (!f.exists() || total <= 0 || total > Proto.MAX_FRAME) continue
            var offset = (partial[p.id] ?: 0L).coerceIn(0L, total)
            if (offset >= total) offset = 0L
            try {
                RandomAccessFile(f, "r").use { raf ->
                    while (offset < total && budget > 0) {
                        val size = minOf(Proto.PHOTO_CHUNK.toLong(), total - offset).toInt()
                        val buf = ByteArray(size)
                        raf.seek(offset)
                        raf.readFully(buf)
                        Proto.writeJson(out, Proto.T_PHOTO, PhotoHeader.serializer(), PhotoHeader(p.toDto(), offset, total))
                        Proto.write(out, Proto.T_PHOTO_DATA, buf)
                        offset += size
                        budget -= size
                        count++
                    }
                }
            } catch (e: IOException) {
                // Fichier illisible : on passe à la photo suivante (l'erreur d'envoi, elle, remonte).
                if (e is java.io.EOFException || e is java.io.FileNotFoundException) continue else throw e
            }
        }
        Proto.write(out, Proto.T_END, ByteArray(0))
        return count
    }

    /**
     * Reçoit des morceaux de photos jusqu'à la fin de liste et les ajoute aux fichiers temporaires.
     * @return nombre de morceaux reçus.
     */
    private suspend fun receivePhotoChunks(context: Context, inp: DataInputStream): Int {
        var count = 0
        while (true) {
            val frame = Proto.read(inp)
            if (frame.type == Proto.T_END) return count
            val header = Proto.decode(frame, Proto.T_PHOTO, PhotoHeader.serializer())
            val data = Proto.read(inp)
            if (data.type != Proto.T_PHOTO_DATA) throw IOException("Morceau de photo attendu")
            mergeMutex.withLock {
                GroupRepository.savePhotoChunk(context, header.photo, header.offset, header.total, data.bytes)
            }
            count++
        }
    }
}
