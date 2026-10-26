package fr.pushpull.champimap.feature.groupe

import android.content.Context
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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/*
 * Synchronisation en direct d'une sortie à plusieurs (Wi-Fi Direct, sans Internet).
 *
 * UTILISATION (API publique) :
 *  - `GroupSync.start(context, groupId)` : démarre (ou garde) la synchro du groupe. Appelé par
 *    les écrans « Sortie à plusieurs » et « Rejoindre » ; peut être rappelé sans risque
 *    (par ex. par l'écran de sortie en cours pour une sortie dont `groupId` n'est pas nul).
 *  - `GroupSync.stop()` : arrête la synchro (le réseau Wi-Fi Direct reste ouvert).
 *  - `GroupSync.leave(context)` : arrête la synchro ET quitte/ferme le réseau Wi-Fi Direct.
 *  - `GroupSync.syncNow()` : demande un échange immédiat.
 *  - `GroupSync.state` : état observable (participants, « synchro OK »), voir [GroupState].
 *    Texte prêt à afficher dans la pastille de la sortie en cours : `state.value.label()`.
 *  La synchro tourne dans `AppGraph.appScope` : elle continue quand on quitte l'écran
 *  (pendant toute la sortie) et s'arrête d'elle-même (en quittant le réseau) quand la sortie
 *  du groupe est terminée ou mise à la corbeille sur ce téléphone.
 *
 * FONCTIONNEMENT : pas de maître pour les données (tous égaux, fusion « la plus récente
 * gagne », voir GroupRepository). Côté réseau, le téléphone hôte du Wi-Fi Direct écoute
 * sur le port [Proto.PORT] et RELAIE : chacun échange avec lui toutes les 30 à 60 s (et dès
 * la (re)connexion), il fusionne tout et le retransmet aux autres au tour suivant.
 */

/** État de la synchro de groupe, pour l'interface. */
data class GroupState(
    val groupId: String? = null,
    val status: Status = Status.ARRETE,
    /** Ce téléphone héberge-t-il le réseau Wi-Fi Direct (et relaie-t-il les autres) ? */
    val isHost: Boolean = false,
    /** Les autres participants (prénoms) avec leur dernier échange réussi. */
    val peers: List<PeerInfo> = emptyList(),
    /** Instant du dernier échange réussi (0 = jamais). */
    val lastSyncAt: Long = 0L,
    /** Photos qu'il reste à envoyer (elles partent quelques-unes à chaque échange). */
    val photosPending: Int = 0,
    /** Dernier problème rencontré (texte lisible), null si tout va bien. */
    val message: String? = null,
) {
    enum class Status { ARRETE, ATTENTE_RESEAU, ATTENTE_PARTICIPANTS, CONNEXION, CONNECTE }

    /** Synchro OK = connecté et dernier échange il y a moins de 2 minutes. */
    val syncOk: Boolean
        get() = status == Status.CONNECTE && System.currentTimeMillis() - lastSyncAt < SYNC_OK_MS

    /** Texte de la pastille : « Avec Julie · synchro OK », « Avec Julie, Marc · hors de portée »… */
    fun label(): String {
        val names = peers.map { it.name }.filter { it.isNotBlank() }
        val with = if (names.isEmpty()) "Sortie à plusieurs" else "Avec " + names.joinToString(", ")
        val st = when {
            status == Status.ARRETE -> "synchro arrêtée"
            syncOk -> "synchro OK"
            status == Status.ATTENTE_PARTICIPANTS -> "en attente des autres"
            status == Status.CONNEXION -> "connexion…"
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

    /** Demandes d'échange immédiat (bouton « Synchroniser », nouvelle saisie importante…). */
    private val trigger = Channel<Unit>(Channel.CONFLATED)

    /** Les fusions (plusieurs participants à la fois chez l'hôte) passent une par une. */
    private val mergeMutex = Mutex()

    /** Participants connectés à l'hôte (personId → infos). */
    private val roster = ConcurrentHashMap<String, PeerInfo>()

    /** Nombre de photos envoyées au maximum par échange (le reste au tour suivant). */
    private const val PHOTOS_PER_ROUND = 4

    /** Délai maximum d'attente d'une réponse sur le réseau. */
    private const val SOCKET_TIMEOUT_MS = 150_000

    // ------------------------------------------------------------------------------- API publique

    /** Démarre la synchro du groupe (sans effet si elle tourne déjà pour ce groupe). */
    @Synchronized
    fun start(context: Context, groupId: String) {
        if (currentGroupId == groupId && job?.isActive == true) return
        job?.cancel()
        currentGroupId = groupId
        roster.clear()
        _state.value = GroupState(groupId = groupId, status = GroupState.Status.ATTENTE_RESEAU)
        val app = context.applicationContext
        job = AppGraph.appScope.launch { runLoop(app, groupId) }
    }

    /** Arrête la synchro (le réseau Wi-Fi Direct n'est pas fermé). */
    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        currentGroupId = null
        roster.clear()
        _state.value = GroupState()
    }

    /** Arrête la synchro et quitte (ou ferme, pour l'hôte) le réseau Wi-Fi Direct. */
    fun leave(context: Context) {
        stop()
        val app = context.applicationContext
        AppGraph.appScope.launch { runCatching { WifiDirect.removeGroup(app) } }
    }

    /** Demande un échange tout de suite (côté participant ; l'hôte attend les autres). */
    fun syncNow() {
        trigger.trySend(Unit)
    }

    // ------------------------------------------------------------------------------- Boucle principale

    /**
     * Boucle de vie : attend que le réseau Wi-Fi Direct soit formé, puis joue le rôle d'hôte
     * ou de participant selon ce que le système a décidé ; recommence après chaque coupure.
     */
    private suspend fun runLoop(context: Context, groupId: String) = coroutineScope {
        while (isActive) {
            if (!sortieActive(groupId)) {
                // Sortie terminée (ou supprimée) : on quitte proprement le groupe.
                runCatching { WifiDirect.removeGroup(context) }
                _state.value = GroupState()
                synchronized(this@GroupSync) { if (currentGroupId == groupId) currentGroupId = null }
                break
            }
            val info = runCatching { WifiDirect.connectionInfo(context) }.getOrNull()
            val owner = info?.groupOwnerAddress
            if (info == null || !info.groupFormed || owner == null) {
                _state.update {
                    it.copy(
                        status = GroupState.Status.ATTENTE_RESEAU, isHost = false,
                        message = if (WifiDirect.isWifiEnabled(context)) null else "Active le Wi-Fi (pas besoin d'Internet).",
                    )
                }
                delay(3_000)
                continue
            }
            try {
                if (info.isGroupOwner) runHost(context, groupId, owner) else runClient(context, groupId, owner)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isSocketForbidden(e)) {
                    // Android refuse d'ouvrir une socket sans la permission INTERNET (voir rapport du module).
                    ErrorLogger.log("Groupe", "Sockets refusées par Android (permission INTERNET absente)", e)
                    _state.update {
                        it.copy(status = GroupState.Status.ATTENTE_RESEAU, message = "Échange impossible : Android bloque le réseau local de l'appli.")
                    }
                    delay(60_000)
                } else {
                    // Coupure réseau, téléphone hors de portée… : on réessaie.
                    _state.update { it.copy(status = GroupState.Status.ATTENTE_RESEAU, message = "Connexion perdue, nouvel essai…") }
                }
            }
            delay(2_000)
        }
    }

    /** Erreur « EACCES » : Android interdit les sockets à une appli sans permission INTERNET. */
    private fun isSocketForbidden(e: Throwable): Boolean =
        e is SecurityException || (e.message?.contains("EACCES") == true)

    /** La sortie de ce groupe est-elle toujours en cours sur ce téléphone ? */
    private suspend fun sortieActive(groupId: String): Boolean {
        val sorties = AppGraph.db.syncDao().sortiesOfGroup(groupId)
        return sorties.any { it.endedAt == null && it.deletedAt == null }
    }

    /**
     * Exécute `block` en fermant `c` si la coroutine est annulée (les lectures réseau
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

    /** Hôte : écoute les participants (uniquement sur l'adresse Wi-Fi Direct) et relaie leurs données. */
    private suspend fun runHost(context: Context, groupId: String, address: InetAddress) {
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(address, Proto.PORT))
        roster.clear()
        _state.update { it.copy(status = GroupState.Status.ATTENTE_PARTICIPANTS, isHost = true, peers = emptyList(), message = null) }
        closingOnCancel(server) {
            coroutineScope {
                // Surveillance : réseau toujours là et sortie toujours en cours ? Sinon on ferme.
                val watchdog = launch {
                    while (isActive) {
                        delay(10_000)
                        val i = runCatching { WifiDirect.connectionInfo(context) }.getOrNull()
                        if (i == null || !i.groupFormed || !i.isGroupOwner || !sortieActive(groupId)) {
                            runCatching { server.close() }
                            break
                        }
                        refreshHostState()
                    }
                }
                val clients = ArrayList<Job>()
                while (isActive) {
                    val socket = try {
                        server.accept()
                    } catch (e: IOException) {
                        break // serveur fermé
                    }
                    clients += launch { serveClient(context, groupId, socket) }
                }
                watchdog.cancel()
                clients.forEach { it.cancel() }
            }
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

    /** Hôte : dialogue avec un participant, tour après tour, jusqu'à la coupure. */
    private suspend fun serveClient(context: Context, groupId: String, socket: Socket) {
        var peerId: String? = null
        try {
            closingOnCancel(socket) {
                socket.soTimeout = SOCKET_TIMEOUT_MS
                socket.tcpNoDelay = true
                val inp = DataInputStream(BufferedInputStream(socket.getInputStream()))
                val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
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
                    // 1. Le participant envoie ce qu'il a.
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
                    // 4. Échange des photos : les siennes d'abord, puis les nôtres.
                    receivePhotos(context, inp)
                    val photos = GroupRepository.photosToSend(data, summary)
                    sendPhotos(out, photos.take(PHOTOS_PER_ROUND))
                    // Tour réussi.
                    val now = System.currentTimeMillis()
                    roster[hello.personId] = PeerInfo(hello.personId, hello.name.take(40), now)
                    _state.update { it.copy(lastSyncAt = now, message = null, photosPending = maxOf(0, photos.size - PHOTOS_PER_ROUND)) }
                    refreshHostState()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // Participant parti ou hors de portée : normal en forêt, pas d'entrée au journal.
        } catch (e: Exception) {
            ErrorLogger.log("Groupe", "Échange avec un participant interrompu", e)
        } finally {
            peerId?.let { roster.remove(it) }
            refreshHostState()
        }
    }

    // ------------------------------------------------------------------------------- Rôle de participant

    /** Participant : se connecte à l'hôte et échange toutes les 30 à 60 s (et à la demande). */
    private suspend fun runClient(context: Context, groupId: String, host: InetAddress) {
        _state.update { it.copy(status = GroupState.Status.CONNEXION, isHost = false) }
        val socket = connectWithRetry(host)
        closingOnCancel(socket) {
            socket.soTimeout = SOCKET_TIMEOUT_MS
            socket.tcpNoDelay = true
            val inp = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            val me = GroupRepository.me()

            Proto.writeJson(out, Proto.T_HELLO, Hello.serializer(), Hello(Proto.VERSION, groupId, me.id, me.name))
            val hello = Proto.decode(Proto.read(inp), Proto.T_HELLO, Hello.serializer())
            if (hello.groupId != groupId) {
                _state.update { it.copy(message = "Ce réseau n'est pas celui de la sortie.") }
                delay(10_000)
                return@closingOnCancel
            }
            GroupRepository.rememberPerson(hello.personId, hello.name)

            // Premier échange tout de suite (reconnexion), puis toutes les 30 à 60 s.
            while (true) {
                clientRound(context, groupId, me.id, inp, out)
                withTimeoutOrNull(Random.nextLong(30_000L, 60_001L)) { trigger.receive() }
                if (!sortieActive(groupId)) break
            }
        }
    }

    /**
     * Ouvre la connexion vers l'hôte, avec quelques essais (il peut mettre quelques secondes
     * à ouvrir son port). Java ferme une socket dont la connexion échoue : nouvelle socket à
     * chaque essai.
     */
    private suspend fun connectWithRetry(host: InetAddress): Socket {
        var attempt = 0
        while (true) {
            attempt++
            val s = Socket()
            try {
                s.connect(InetSocketAddress(host, Proto.PORT), 5_000)
                return s
            } catch (e: IOException) {
                runCatching { s.close() }
                if (attempt >= 5) throw e
                delay(2_000)
            }
        }
    }

    /** Participant : un tour d'échange complet avec l'hôte. */
    private suspend fun clientRound(context: Context, groupId: String, myId: String, inp: DataInputStream, out: DataOutputStream) {
        // 1. Ce que j'ai.
        val data = GroupRepository.collect(groupId)
        Proto.writeJson(out, Proto.T_SUMMARY, SyncSummary.serializer(), GroupRepository.summaryOf(data))
        // 2. Réponse de l'hôte : ce qui me manque (fusionné) + participants.
        val reply = Proto.decode(Proto.read(inp), Proto.T_REPLY, RoundReply.serializer())
        mergeMutex.withLock { GroupRepository.merge(reply.bundle) }
        reply.roster.forEach { GroupRepository.rememberPerson(it.personId, it.name) }
        // 3. Ce qui manque à l'hôte.
        Proto.writeJson(out, Proto.T_BUNDLE, SyncBundle.serializer(), GroupRepository.diff(data, reply.summary))
        // 4. Photos : les miennes d'abord, puis celles de l'hôte.
        val photos = GroupRepository.photosToSend(data, reply.summary)
        sendPhotos(out, photos.take(PHOTOS_PER_ROUND))
        receivePhotos(context, inp)

        val now = System.currentTimeMillis()
        _state.update {
            it.copy(
                status = GroupState.Status.CONNECTE, isHost = false, lastSyncAt = now, message = null,
                peers = reply.roster.filter { p -> p.personId != myId }.sortedBy { p -> p.name },
                photosPending = maxOf(0, photos.size - PHOTOS_PER_ROUND),
            )
        }
    }

    // ------------------------------------------------------------------------------- Photos

    /** Envoie quelques photos (en-tête + fichier JPEG), puis la fin de liste. */
    private fun sendPhotos(out: DataOutputStream, photos: List<Photo>) {
        for (p in photos) {
            val f = File(p.path)
            if (!f.exists() || f.length() > Proto.MAX_FRAME) continue
            val bytes = try {
                f.readBytes()
            } catch (e: IOException) {
                continue
            }
            Proto.writeJson(out, Proto.T_PHOTO, PhotoHeader.serializer(), PhotoHeader(p.toDto()))
            Proto.write(out, Proto.T_PHOTO_DATA, bytes)
        }
        Proto.write(out, Proto.T_END, ByteArray(0))
    }

    /** Reçoit les photos jusqu'à la fin de liste et les enregistre. */
    private suspend fun receivePhotos(context: Context, inp: DataInputStream) {
        while (true) {
            val frame = Proto.read(inp)
            if (frame.type == Proto.T_END) return
            val header = Proto.decode(frame, Proto.T_PHOTO, PhotoHeader.serializer())
            val data = Proto.read(inp)
            if (data.type != Proto.T_PHOTO_DATA) throw IOException("Photo attendue")
            mergeMutex.withLock { GroupRepository.savePhoto(context, header.photo, data.bytes) }
        }
    }
}
