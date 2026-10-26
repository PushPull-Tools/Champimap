package fr.pushpull.champimap.feature.reglages

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/*
 * Chiffrement des sauvegardes : AES-256-GCM, clé dérivée du mot de passe par PBKDF2-HMAC-SHA256.
 *
 * Format du fichier chiffré :
 *   "CHAMPIBK" (8 octets) | version (1) | itérations (int) | sel (16) | iv de base (12)
 *   puis des blocs : longueur (int) | données chiffrées + étiquette GCM (16)
 * Chaque bloc (1 Mo max en clair) a son propre nonce (iv de base XOR numéro de bloc) et des
 * données associées (numéro + « dernier bloc ») : un fichier tronqué ou réordonné est refusé.
 * Le découpage évite de charger toute la sauvegarde (photos) en mémoire.
 */
object BackupCrypto {

    val MAGIC = "CHAMPIBK".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 1
    private const val ITERATIONS = 120_000
    private const val CHUNK = 1 shl 20
    private const val TAG_BITS = 128

    /** Mot de passe incorrect ou fichier abîmé. */
    class BadPasswordException : IOException("Mot de passe incorrect ou fichier abîmé")

    /** Dérive la clé AES-256 depuis le mot de passe. */
    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(raw, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    /** Nonce du bloc n : iv de base dont les 4 derniers octets sont combinés au numéro. */
    private fun nonce(base: ByteArray, index: Int): ByteArray = base.copyOf().also {
        it[8] = (it[8].toInt() xor (index ushr 24)).toByte()
        it[9] = (it[9].toInt() xor (index ushr 16)).toByte()
        it[10] = (it[10].toInt() xor (index ushr 8)).toByte()
        it[11] = (it[11].toInt() xor index).toByte()
    }

    /** Données associées : numéro de bloc + indicateur de dernier bloc. */
    private fun aad(index: Int, last: Boolean): ByteArray =
        byteArrayOf((index ushr 24).toByte(), (index ushr 16).toByte(), (index ushr 8).toByte(), index.toByte(), (if (last) 1 else 0).toByte())

    /** Flux d'écriture qui chiffre tout ce qu'on y écrit. `close()` écrit le dernier bloc. */
    fun encrypt(out: OutputStream, password: CharArray): OutputStream {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val key = deriveKey(password, salt, ITERATIONS)
        val data = DataOutputStream(out)
        data.write(MAGIC); data.writeByte(VERSION.toInt()); data.writeInt(ITERATIONS); data.write(salt); data.write(iv)
        return object : OutputStream() {
            private val buf = ByteArray(CHUNK)
            private var len = 0
            private var index = 0
            private var closed = false

            private fun flushChunk(last: Boolean) {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(iv, index)))
                c.updateAAD(aad(index, last))
                val enc = c.doFinal(buf, 0, len)
                data.writeInt(enc.size); data.write(enc)
                index++; len = 0
            }

            override fun write(b: Int) {
                if (len == CHUNK) flushChunk(false)
                buf[len++] = b.toByte()
            }

            override fun write(b: ByteArray, off: Int, l: Int) {
                var o = off; var r = l
                while (r > 0) {
                    if (len == CHUNK) flushChunk(false)
                    val n = minOf(r, CHUNK - len)
                    System.arraycopy(b, o, buf, len, n)
                    len += n; o += n; r -= n
                }
            }

            override fun close() {
                if (closed) return
                closed = true
                flushChunk(true)
                data.flush()
                out.close()
            }
        }
    }

    /**
     * Flux de lecture déchiffré. L'en-tête (MAGIC compris) doit encore être dans `input`.
     * Lève BadPasswordException au premier bloc si le mot de passe est faux.
     */
    fun decrypt(input: InputStream, password: CharArray): InputStream {
        val data = DataInputStream(input)
        val magic = ByteArray(MAGIC.size); data.readFully(magic)
        if (!magic.contentEquals(MAGIC)) throw IOException("Ce fichier n'est pas une sauvegarde Champimap chiffrée")
        val version = data.readByte()
        if (version != VERSION) throw IOException("Version de sauvegarde inconnue")
        val iterations = data.readInt()
        if (iterations !in 10_000..10_000_000) throw IOException("En-tête de sauvegarde invalide")
        val salt = ByteArray(16); data.readFully(salt)
        val iv = ByteArray(12); data.readFully(iv)
        val key = deriveKey(password, salt, iterations)

        return object : InputStream() {
            private var plain = ByteArray(0)
            private var pos = 0
            private var index = 0
            private var finished = false

            /** Lit et déchiffre le bloc suivant ; false à la fin. */
            private fun nextChunk(): Boolean {
                if (finished) return false
                val size = try { data.readInt() } catch (e: EOFException) { throw IOException("Sauvegarde incomplète", e) }
                if (size < 16 || size > CHUNK + 16) throw IOException("Bloc de sauvegarde invalide")
                val enc = ByteArray(size); data.readFully(enc)
                // Essai « bloc intermédiaire », puis « dernier bloc » (l'étiquette dit lequel est le bon).
                plain = tryDecrypt(enc, false) ?: tryDecrypt(enc, true)?.also { finished = true }
                    ?: throw BadPasswordException()
                pos = 0; index++
                return true
            }

            private fun tryDecrypt(enc: ByteArray, last: Boolean): ByteArray? = try {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(iv, index)))
                c.updateAAD(aad(index, last))
                c.doFinal(enc)
            } catch (e: AEADBadTagException) {
                null
            }

            override fun read(): Int {
                while (pos >= plain.size) if (!nextChunk()) return -1
                return plain[pos++].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                while (pos >= plain.size) if (!nextChunk()) return -1
                val n = minOf(len, plain.size - pos)
                System.arraycopy(plain, pos, b, off, n)
                pos += n
                return n
            }

            override fun close() = input.close()
        }
    }
}
