package fr.pushpull.champimap.feature.groupe

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/*
 * Protocole d'échange entre téléphones d'une sortie à plusieurs (canal Bluetooth L2CAP,
 * voir BleLink.kt) et contenu du QR code d'invitation.
 *
 * Trame = 1 octet de type + 4 octets de longueur + contenu (JSON UTF-8, ou octets bruts
 * pour un morceau de photo).
 *
 * Déroulé d'une connexion (l'« invité » se connecte à l'« hôte », créateur de la sortie) :
 *   invité → HELLO            hôte → HELLO          (même groupe ? sinon fermeture)
 *   puis un « tour » toutes les 30–60 s (toutes les quelques secondes tant que des photos
 *   sont en route), et tout de suite à la (re)connexion :
 *   invité → SUMMARY          (ce que j'ai, photos à moitié reçues comprises)
 *   hôte   → REPLY            (ce que j'ai + ce qui te manque + liste des participants)
 *   invité → BUNDLE           (ce qui manque à l'hôte)
 *   invité → PHOTO/PHOTO_DATA… END   (quelques morceaux de photos pour l'hôte)
 *   hôte   → PHOTO/PHOTO_DATA… END   (quelques morceaux de photos pour l'invité)
 * Les DONNÉES passent toujours en premier ; les photos n'utilisent qu'un petit budget
 * d'octets par tour et reprennent là où elles s'étaient arrêtées (débit Bluetooth faible).
 * L'hôte fusionne tout ce qu'il reçoit : au tour suivant, il le retransmet aux autres (relais).
 */
internal object Proto {
    /** Version du protocole (pour refuser poliment une version incompatible plus tard). */
    const val VERSION = 1

    const val T_HELLO = 1
    const val T_SUMMARY = 2
    const val T_REPLY = 3
    const val T_BUNDLE = 4
    const val T_PHOTO = 5
    const val T_PHOTO_DATA = 6
    const val T_END = 7

    /** Taille d'un morceau de photo, et budget de photos par tour et par sens. */
    const val PHOTO_CHUNK = 16 * 1024
    const val PHOTO_BUDGET = 96 * 1024

    /** Taille maximale d'une trame (protège contre une trame corrompue ou malveillante). */
    const val MAX_FRAME = 40 * 1024 * 1024

    /** JSON tolérant : champs inconnus ignorés, valeurs par défaut non écrites (paquets plus légers). */
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** Une trame lue. */
    class Frame(val type: Int, val bytes: ByteArray)

    /** Écrit une trame et l'envoie tout de suite. */
    fun write(out: DataOutputStream, type: Int, bytes: ByteArray) {
        out.writeByte(type)
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
    }

    /** Écrit une trame JSON. */
    fun <T> writeJson(out: DataOutputStream, type: Int, serializer: KSerializer<T>, value: T) =
        write(out, type, json.encodeToString(serializer, value).toByteArray(Charsets.UTF_8))

    /** Lit une trame (bloquant). */
    fun read(inp: DataInputStream): Frame {
        val type = inp.readUnsignedByte()
        val size = inp.readInt()
        if (size < 0 || size > MAX_FRAME) throw IOException("Trame de taille invalide")
        val bytes = ByteArray(size)
        inp.readFully(bytes)
        return Frame(type, bytes)
    }

    /** Décode une trame JSON du type attendu (sinon erreur de protocole). */
    fun <T> decode(frame: Frame, expectedType: Int, serializer: KSerializer<T>): T {
        if (frame.type != expectedType) throw IOException("Trame inattendue : ${frame.type} au lieu de $expectedType")
        return json.decodeFromString(serializer, String(frame.bytes, Charsets.UTF_8))
    }
}

/** Présentation au début d'une connexion. */
@Serializable
internal data class Hello(val v: Int, val groupId: String, val personId: String, val name: String)

/** Un participant connu de l'hôte (pour la pastille « Avec Julie · synchro OK »). */
@Serializable
data class PeerInfo(val personId: String, val name: String, val lastSyncAt: Long = 0L)

/** Réponse de l'hôte à un résumé : son propre résumé, ce qui manque au client, les participants. */
@Serializable
internal data class RoundReply(
    val summary: SyncSummary,
    val bundle: SyncBundle,
    val roster: List<PeerInfo> = emptyList(),
)

/**
 * En-tête d'un morceau de photo (suivi d'une trame PHOTO_DATA avec les octets du morceau).
 * @param offset position du morceau dans le fichier, @param total taille du fichier complet.
 */
@Serializable
internal data class PhotoHeader(val photo: PhotoDto, val offset: Long = 0L, val total: Long = 0L)

/**
 * Contenu du QR code « Sortie à plusieurs » : de quoi retrouver l'hôte en Bluetooth et
 * rejoindre la sortie partagée.
 * @param hash hachage court du groupe (8 caractères hexa), le même que dans l'annonce BLE.
 * @param personId / @param name identifiant et prénom de l'hôte.
 */
@Serializable
data class GroupInvite(
    val v: Int = 2,
    val groupId: String,
    val sortieId: String,
    val personId: String,
    val name: String,
    val hash: String = "",
) {
    /** Texte à mettre dans le QR code. */
    fun encode(): String = PREFIX + Proto.json.encodeToString(GroupInvite.serializer(), this)

    companion object {
        const val PREFIX = "CHAMPIMAP-G2:"

        /** Lit un QR code d'invitation ; null si ce n'en est pas un (ou une ancienne version). */
        fun parse(text: String?): GroupInvite? {
            if (text == null || !text.startsWith(PREFIX)) return null
            return runCatching { Proto.json.decodeFromString(GroupInvite.serializer(), text.removePrefix(PREFIX)) }.getOrNull()
                ?.takeIf { it.groupId.isNotBlank() && it.sortieId.isNotBlank() }
        }
    }
}
