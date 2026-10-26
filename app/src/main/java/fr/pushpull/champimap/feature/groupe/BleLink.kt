package fr.pushpull.champimap.feature.groupe

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.ParcelUuid
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.resume

/*
 * Transport Bluetooth Low Energy de la sortie à plusieurs : AUCUN socket IP, aucun Internet.
 *
 * Hôte (téléphone qui a créé la sortie) :
 *  - ouvre un canal L2CAP « insecure » (pas d'appairage) → numéro de canal (PSM) ;
 *  - publie une annonce BLE : UUID de service Champimap + données de service
 *    [hachage du groupe (4 octets) · PSM (2 octets)]. Pas de mode « visible » à demander.
 * Invités :
 *  - scannent les annonces filtrées sur l'UUID Champimap + le hachage du groupe (lu dans le QR),
 *  - se connectent au canal L2CAP de l'hôte.
 * Android 10 (API 29) minimum : les canaux L2CAP n'existent pas avant.
 * Portée : quelques dizaines de mètres en forêt.
 *
 * Autorisations (demandées par les écrans) : Android 12+ BLUETOOTH_SCAN, BLUETOOTH_CONNECT,
 * BLUETOOTH_ADVERTISE ; Android 10-11 : ACCESS_FINE_LOCATION (et localisation activée) pour le scan.
 * Sans autorisation, les appels lèvent SecurityException (gérée par GroupSync).
 */
internal object BleLink {

    /** UUID de service fixe de Champimap (identifie nos annonces BLE). */
    val SERVICE_UUID: UUID = UUID.fromString("6f1c2a52-3d0e-4b8e-9c41-c4a3b1f0e7d5")

    /** Version d'Android minimale pour la sortie à plusieurs. */
    const val MIN_SDK = Build.VERSION_CODES.Q

    /** Erreur lisible par l'utilisateur. */
    class BleException(message: String) : Exception(message)

    /** Un hôte trouvé au scan : l'appareil et son numéro de canal L2CAP. */
    class HostFound(val device: BluetoothDevice, val psm: Int)

    /** La sortie à plusieurs est-elle possible sur cette version d'Android ? */
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= MIN_SDK

    /** Adaptateur Bluetooth du téléphone (null s'il n'en a pas). */
    fun adapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /** Le Bluetooth est-il allumé ? */
    fun isEnabled(context: Context): Boolean = try {
        adapter(context)?.isEnabled == true
    } catch (e: SecurityException) {
        false
    }

    /**
     * Android 10-11 : le scan BLE ne renvoie rien si la localisation du téléphone est coupée.
     * (Android 12+ avec BLUETOOTH_SCAN n'en a plus besoin.)
     */
    fun locationNeededButOff(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return false
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) !lm.isLocationEnabled else false
    }

    // ------------------------------------------------------------------------------- Hachage du groupe

    /** Hachage court (4 octets) de l'identifiant de groupe : seul ce hachage passe dans l'annonce. */
    fun groupHash(groupId: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(groupId.toByteArray(Charsets.UTF_8)).copyOf(4)

    /** Même hachage en hexadécimal (8 caractères, mis dans le QR code). */
    fun hashHex(groupId: String): String = groupHash(groupId).joinToString("") { "%02x".format(it) }

    /** Données de service de l'annonce : hachage (4) puis PSM (2, gros-boutiste). */
    private fun serviceData(groupId: String, psm: Int): ByteArray =
        groupHash(groupId) + byteArrayOf((psm shr 8).toByte(), psm.toByte())

    // ------------------------------------------------------------------------------- Hôte

    /** Ouvre le canal L2CAP d'écoute (sans appairage). Son numéro est `server.psm`. */
    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    fun openServer(context: Context): BluetoothServerSocket {
        val a = adapter(context) ?: throw BleException("Ce téléphone n'a pas de Bluetooth.")
        return try {
            a.listenUsingInsecureL2capChannel()
        } catch (e: IOException) {
            throw BleException("Le Bluetooth n'a pas pu ouvrir de canal. Éteins puis rallume le Bluetooth.")
        }
    }

    /**
     * Lance l'annonce BLE de l'hôte. Renvoie le « callback » à passer à [stopAdvertising].
     * @throws BleException si le téléphone ne sait pas émettre d'annonces.
     */
    @SuppressLint("MissingPermission")
    suspend fun startAdvertising(context: Context, groupId: String, psm: Int): AdvertiseCallback {
        val advertiser = adapter(context)?.bluetoothLeAdvertiser
            ?: throw BleException("Ce téléphone ne peut pas héberger la sortie en Bluetooth : fais-la créer par un autre téléphone.")
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH) // portée maximale en forêt
            .setConnectable(true) // indispensable pour que les invités ouvrent le canal L2CAP
            .setTimeout(0)
            .build()
        // Annonce courte (≤ 31 octets) : pas de nom d'appareil, seulement les données de service.
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceData(ParcelUuid(SERVICE_UUID), serviceData(groupId, psm))
            .build()

        var callback: AdvertiseCallback? = null
        val code = withTimeoutOrNull(5_000L) {
            suspendCancellableCoroutine<Int> { cont ->
                val cb = object : AdvertiseCallback() {
                    override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                        if (cont.isActive) cont.resume(0)
                    }

                    override fun onStartFailure(errorCode: Int) {
                        if (cont.isActive) cont.resume(errorCode)
                    }
                }
                callback = cb
                try {
                    advertiser.startAdvertising(settings, data, cb)
                } catch (e: SecurityException) {
                    if (cont.isActive) cont.resume(-1)
                }
            }
        } ?: -2
        val cb = callback
        return when {
            cb != null && (code == 0 || code == AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED) -> cb
            code == -1 -> throw SecurityException("Autorisation Bluetooth refusée")
            code == AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED ->
                throw BleException("Ce téléphone ne peut pas héberger la sortie en Bluetooth : fais-la créer par un autre téléphone.")
            else -> {
                cb?.let { stopAdvertising(context, it) }
                throw BleException("L'annonce Bluetooth n'a pas démarré. Nouvel essai…")
            }
        }
    }

    /** Arrête l'annonce BLE. */
    @SuppressLint("MissingPermission")
    fun stopAdvertising(context: Context, callback: AdvertiseCallback) {
        try {
            adapter(context)?.bluetoothLeAdvertiser?.stopAdvertising(callback)
        } catch (e: Exception) {
            // Bluetooth coupé entre-temps : rien à arrêter.
        }
    }

    // ------------------------------------------------------------------------------- Invité

    /** Lit une annonce : est-ce l'hôte de ce groupe ? Si oui, renvoie l'appareil et son PSM. */
    private fun parse(result: ScanResult, hash: ByteArray): HostFound? {
        val bytes = result.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID)) ?: return null
        if (bytes.size < 6) return null
        for (i in 0 until 4) if (bytes[i] != hash[i]) return null
        val psm = ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF)
        if (psm <= 0) return null
        return HostFound(result.device, psm)
    }

    /**
     * Cherche l'hôte du groupe par scan BLE (filtré sur l'UUID Champimap + le hachage).
     * @return l'hôte, ou null s'il n'est pas à portée dans le délai.
     */
    @SuppressLint("MissingPermission")
    suspend fun findHost(context: Context, groupId: String, timeoutMs: Long): HostFound? {
        val scanner = adapter(context)?.bluetoothLeScanner ?: return null
        val hash = groupHash(groupId)
        val filter = ScanFilter.Builder().setServiceData(ParcelUuid(SERVICE_UUID), hash).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        var callback: ScanCallback? = null
        try {
            return withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<HostFound?> { cont ->
                    val cb = object : ScanCallback() {
                        override fun onScanResult(callbackType: Int, result: ScanResult) {
                            val found = parse(result, hash) ?: return
                            if (cont.isActive) cont.resume(found)
                        }

                        override fun onBatchScanResults(results: MutableList<ScanResult>) {
                            val found = results.firstNotNullOfOrNull { parse(it, hash) } ?: return
                            if (cont.isActive) cont.resume(found)
                        }

                        override fun onScanFailed(errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                    callback = cb
                    try {
                        scanner.startScan(listOf(filter), settings, cb)
                    } catch (e: SecurityException) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        } finally {
            // Toujours arrêter le scan (batterie), avant toute connexion.
            callback?.let { c ->
                try {
                    scanner.stopScan(c)
                } catch (e: Exception) {
                    // Bluetooth coupé : le scan est déjà arrêté.
                }
            }
        }
    }

    /** Prépare (sans la connecter) la socket L2CAP vers l'hôte trouvé. */
    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    fun createSocket(found: HostFound): BluetoothSocket = found.device.createInsecureL2capChannel(found.psm)
}
