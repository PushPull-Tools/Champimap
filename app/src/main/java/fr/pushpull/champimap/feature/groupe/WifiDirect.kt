package fr.pushpull.champimap.feature.groupe

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/*
 * Accès au Wi-Fi Direct (WifiP2pManager) en fonctions « suspend » simples.
 * Aucun Internet, aucun service Google : les téléphones forment un petit réseau local
 * entre eux (l'hôte a l'adresse 192.168.49.1).
 *
 * Permissions nécessaires AVANT l'appel (demandées par les écrans) :
 *  - Android 13+ : NEARBY_WIFI_DEVICES ;
 *  - Android 8 à 12 : ACCESS_FINE_LOCATION (et la localisation activée sur certains modèles).
 * Sans permission, les appels échouent proprement (SecurityException interceptée).
 */
internal object WifiDirect {

    /** Erreur lisible par l'utilisateur. */
    class WifiDirectException(message: String) : Exception(message)

    /** Informations du réseau créé (pour le QR code). */
    data class GroupCreds(val networkName: String, val passphrase: String, val ownerAddress: String?)

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null

    /** Code d'échec maison : permission refusée. */
    private const val FAIL_PERMISSION = -1

    /** Code d'échec maison : pas de réponse du système. */
    private const val FAIL_TIMEOUT = -2

    /** Initialise (une seule fois) le gestionnaire Wi-Fi Direct et son canal. */
    @Synchronized
    private fun init(context: Context): Pair<WifiP2pManager, WifiP2pManager.Channel>? {
        val m0 = manager
        val c0 = channel
        if (m0 != null && c0 != null) return m0 to c0
        val app = context.applicationContext
        val m = app.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager ?: return null
        val c = m.initialize(app, Looper.getMainLooper()) {
            // Canal perdu (Wi-Fi coupé…) : il sera recréé au prochain appel.
            synchronized(this) { channel = null }
        } ?: return null
        manager = m
        channel = c
        return m to c
    }

    /** Le Wi-Fi est-il allumé ? (Android 10+ interdit aux applis de l'allumer elles-mêmes.) */
    fun isWifiEnabled(context: Context): Boolean = runCatching {
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
    }.getOrDefault(true)

    /** Texte d'une raison d'échec du système. */
    private fun reasonText(reason: Int?): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "Ce téléphone ne gère pas le Wi-Fi Direct."
        WifiP2pManager.BUSY -> "Le Wi-Fi Direct est occupé. Réessaie dans quelques secondes."
        FAIL_PERMISSION -> "Autorisation « appareils à proximité » refusée."
        FAIL_TIMEOUT -> "Le Wi-Fi Direct ne répond pas. Vérifie que le Wi-Fi est activé."
        else -> "Le Wi-Fi Direct n'a pas fonctionné. Vérifie que le Wi-Fi est activé."
    }

    /** Valeur interne « réussite » (les codes d'échec du système sont 0, 1, 2, 3). */
    private const val OK = Int.MIN_VALUE

    /** Lance une action Wi-Fi Direct et attend son résultat : null = réussite, sinon code d'échec. */
    private suspend fun action(block: (WifiP2pManager.ActionListener) -> Unit): Int? {
        val code = withTimeoutOrNull(10_000L) {
            suspendCancellableCoroutine<Int> { cont ->
                val listener = object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (cont.isActive) cont.resume(OK)
                    }

                    override fun onFailure(reason: Int) {
                        if (cont.isActive) cont.resume(reason)
                    }
                }
                try {
                    block(listener)
                } catch (e: SecurityException) {
                    if (cont.isActive) cont.resume(FAIL_PERMISSION)
                }
            }
        } ?: FAIL_TIMEOUT
        return if (code == OK) null else code
    }

    /** Informations du groupe Wi-Fi Direct actuel (null si aucun). */
    @SuppressLint("MissingPermission")
    private suspend fun groupInfo(m: WifiP2pManager, c: WifiP2pManager.Channel): WifiP2pGroup? =
        withTimeoutOrNull(5_000L) {
            suspendCancellableCoroutine<WifiP2pGroup?> { cont ->
                try {
                    m.requestGroupInfo(c) { g -> if (cont.isActive) cont.resume(g) }
                } catch (e: SecurityException) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

    /** État de la connexion Wi-Fi Direct (groupe formé ? suis-je l'hôte ? adresse de l'hôte). */
    suspend fun connectionInfo(context: Context): WifiP2pInfo? {
        val (m, c) = init(context) ?: return null
        return withTimeoutOrNull(5_000L) {
            suspendCancellableCoroutine<WifiP2pInfo?> { cont ->
                try {
                    m.requestConnectionInfo(c) { info -> if (cont.isActive) cont.resume(info) }
                } catch (e: SecurityException) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    /** Extrait les informations utiles d'un groupe dont on est l'hôte. */
    private fun credsOf(g: WifiP2pGroup?): GroupCreds? {
        if (g == null || !g.isGroupOwner) return null
        val name = g.networkName ?: return null
        val pass = g.passphrase ?: return null
        val addr = g.owner?.deviceAddress?.takeIf { it.isNotBlank() && it != "02:00:00:00:00:00" }
        return GroupCreds(name, pass, addr)
    }

    /**
     * Crée le réseau Wi-Fi Direct dont ce téléphone est l'hôte (ou réutilise celui déjà créé).
     * @throws WifiDirectException avec un message lisible en cas d'échec.
     */
    @SuppressLint("MissingPermission")
    suspend fun createGroup(context: Context): GroupCreds {
        val (m, c) = init(context) ?: throw WifiDirectException(reasonText(WifiP2pManager.P2P_UNSUPPORTED))
        if (!isWifiEnabled(context)) throw WifiDirectException("Active le Wi-Fi (pas besoin d'Internet).")

        // Réseau déjà créé par nous : on le garde (le QR code reste valable).
        credsOf(groupInfo(m, c))?.let { return it }

        // Sinon on quitte un éventuel ancien groupe, puis on crée le nôtre (3 essais).
        action { m.removeGroup(c, it) }
        delay(500)
        var reason: Int? = null
        repeat(3) {
            reason = action { m.createGroup(c, it) }
            if (reason == null) {
                // Le système met parfois quelques secondes à fournir le nom et la phrase de passe.
                repeat(20) {
                    delay(500)
                    credsOf(groupInfo(m, c))?.let { creds -> return creds }
                }
                throw WifiDirectException(reasonText(FAIL_TIMEOUT))
            }
            delay(1_500)
        }
        throw WifiDirectException(reasonText(reason))
    }

    /**
     * Rejoint le réseau de l'hôte décrit par le QR code.
     * Android 10+ : connexion directe par nom de réseau + phrase de passe (rien à valider chez l'hôte).
     * Repli (Android 8-9, ou si refusé) : recherche des appareils puis connexion par adresse.
     * @return null si la demande est acceptée (la connexion se termine ensuite en arrière-plan),
     *   sinon un message d'erreur.
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(context: Context, invite: GroupInvite): String? {
        val (m, c) = init(context) ?: return reasonText(WifiP2pManager.P2P_UNSUPPORTED)
        if (!isWifiEnabled(context)) return "Active le Wi-Fi (pas besoin d'Internet)."

        // Déjà dans ce réseau ? Rien à faire. Dans un autre ? On le quitte.
        val current = groupInfo(m, c)
        if (current != null && !current.isGroupOwner && current.networkName == invite.ssid) return null
        if (current != null) {
            action { m.removeGroup(c, it) }
            delay(500)
        }

        var lastReason: Int? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val config = try {
                WifiP2pConfig.Builder()
                    .setNetworkName(invite.ssid)
                    .setPassphrase(invite.pass)
                    .enablePersistentMode(false)
                    .build()
            } catch (e: RuntimeException) {
                null // nom ou phrase de passe refusés : on passe au repli
            }
            if (config != null) {
                lastReason = action { m.connect(c, config, it) }
                if (lastReason == null) return null
            }
        }

        // Repli par adresse de l'appareil hôte (l'hôte peut devoir accepter l'invitation).
        val addr = invite.addr?.takeIf { it.isNotBlank() } ?: return reasonText(lastReason)
        action { m.discoverPeers(c, it) }
        delay(3_000)
        @Suppress("DEPRECATION")
        val legacy = WifiP2pConfig().apply {
            deviceAddress = addr
            groupOwnerIntent = 0 // on veut rejoindre, pas héberger
        }
        val r = action { m.connect(c, legacy, it) }
        return if (r == null) null else reasonText(r)
    }

    /** Quitte (ou ferme, pour l'hôte) le réseau Wi-Fi Direct actuel. */
    @SuppressLint("MissingPermission")
    suspend fun removeGroup(context: Context) {
        val (m, c) = init(context) ?: return
        action { m.removeGroup(c, it) }
    }
}
