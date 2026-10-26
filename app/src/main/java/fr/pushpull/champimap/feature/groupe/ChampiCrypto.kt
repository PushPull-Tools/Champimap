package fr.pushpull.champimap.feature.groupe

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/*
 * Chiffrement des fichiers de spot partagés (.champi).
 *
 * Le code court (8 caractères, ex. « K7MP-3XQH ») est affiché à l'expéditeur, qui le donne
 * de vive voix au destinataire. La clé AES-256 en est dérivée par PBKDF2-HMAC-SHA256
 * (sel aléatoire, nombreuses itérations : deviner le code coûte cher).
 *
 * Format chiffré (tout en clair sauf les blocs) :
 *   "CHAMPI1E" (8 octets) · sel (16) · préfixe d'IV (8) · itérations (4)
 *   puis des blocs : longueur (4) · données AES-GCM (≤ 1 Mo + 16 octets d'étiquette).
 * IV d'un bloc = préfixe (8) + numéro du bloc (4). Données associées (AAD) = numéro du bloc
 * + drapeau « dernier bloc » : un fichier tronqué ou des blocs inversés sont détectés.
 * Le découpage en blocs évite de charger tout le fichier (photos) en mémoire.
 */
object ChampiCrypto {

    /** Signature d'un fichier .champi chiffré. */
    val MAGIC: ByteArray = "CHAMPI1E".toByteArray(Charsets.US_ASCII)

    /** Alphabet du code : sans 0/O, 1/I/L, pour éviter les confusions à l'oral. */
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private const val CODE_LEN = 8
    private const val ITERATIONS = 150_000
    private const val CHUNK = 1024 * 1024
    private const val TAG_BITS = 128

    /** Code faux, ou fichier abîmé. */
    class WrongCodeException : Exception("Code incorrect ou fichier abîmé")

    private val random = SecureRandom()

    /** Nouveau code court, présenté « ABCD-EFGH ». */
    fun newCode(): String {
        val raw = (1..CODE_LEN).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        return raw.substring(0, 4) + "-" + raw.substring(4)
    }

    /** Le fichier commence-t-il par la signature d'un .champi chiffré ? */
    fun isEncrypted(header: ByteArray): Boolean =
        header.size >= MAGIC.size && MAGIC.indices.all { header[it] == MAGIC[it] }

    /** Dérive la clé AES-256 du code et du sel. */
    private fun deriveKey(code: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(normalizeForKey(code).toCharArray(), salt, iterations, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    /** Forme canonique du code pour la clé : majuscules, lettres et chiffres seulement. */
    private fun normalizeForKey(code: String): String = code.uppercase().filter { it.isLetterOrDigit() }

    /** IV d'un bloc : préfixe aléatoire + numéro du bloc. */
    private fun ivFor(prefix: ByteArray, index: Int): ByteArray =
        ByteBuffer.allocate(12).put(prefix).putInt(index).array()

    /** Données associées d'un bloc : numéro + « dernier bloc ». */
    private fun aadFor(index: Int, last: Boolean): ByteArray =
        ByteBuffer.allocate(5).putInt(index).put(if (last) 1.toByte() else 0.toByte()).array()

    /** Chiffre `input` vers `output` avec le code donné. */
    fun encryptFile(input: File, output: File, code: String) {
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val prefix = ByteArray(8).also { random.nextBytes(it) }
        val key = deriveKey(code, salt, ITERATIONS)
        val total = input.length()
        DataOutputStream(output.outputStream().buffered()).use { out ->
            out.write(MAGIC)
            out.write(salt)
            out.write(prefix)
            out.writeInt(ITERATIONS)
            input.inputStream().buffered().use { inp ->
                val buf = ByteArray(CHUNK)
                var index = 0
                var done = 0L
                while (true) {
                    val n = readFully(inp, buf)
                    done += n
                    val last = done >= total || n < CHUNK
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, ivFor(prefix, index)))
                    cipher.updateAAD(aadFor(index, last))
                    val enc = cipher.doFinal(buf, 0, n)
                    out.writeInt(enc.size)
                    out.write(enc)
                    index++
                    if (last) break
                }
            }
        }
    }

    /**
     * Déchiffre `input` (flux complet, signature comprise) vers `output`.
     * @throws WrongCodeException si le code est faux ou le fichier abîmé.
     */
    fun decryptStream(input: InputStream, output: File, code: String) {
        val inp = DataInputStream(input.buffered())
        try {
            val magic = ByteArray(MAGIC.size).also { inp.readFully(it) }
            if (!isEncrypted(magic)) throw WrongCodeException()
            val salt = ByteArray(16).also { inp.readFully(it) }
            val prefix = ByteArray(8).also { inp.readFully(it) }
            val iterations = inp.readInt()
            if (iterations !in 10_000..5_000_000) throw WrongCodeException()
            val key = deriveKey(code, salt, iterations)
            output.outputStream().buffered().use { out ->
                var index = 0
                while (true) {
                    val size = inp.readInt()
                    if (size <= 0 || size > CHUNK + 16) throw WrongCodeException()
                    val enc = ByteArray(size).also { inp.readFully(it) }
                    // Dernier bloc ? On regarde s'il reste des données après celui-ci.
                    inp.mark(1)
                    val last = inp.read() == -1
                    if (!last) inp.reset()
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, ivFor(prefix, index)))
                    cipher.updateAAD(aadFor(index, last))
                    out.write(cipher.doFinal(enc))
                    index++
                    if (last) break
                }
            }
        } catch (e: AEADBadTagException) {
            output.delete()
            throw WrongCodeException()
        } catch (e: EOFException) {
            output.delete()
            throw WrongCodeException()
        } catch (e: WrongCodeException) {
            output.delete()
            throw e
        } catch (e: java.security.GeneralSecurityException) {
            output.delete()
            throw WrongCodeException()
        }
    }

    /** Lit jusqu'à remplir `buf` (ou fin de flux). Renvoie le nombre d'octets lus. */
    private fun readFully(inp: InputStream, buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) {
            val r = inp.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    /** Lit les premiers octets d'un flux (pour détecter le format). */
    @Throws(IOException::class)
    fun peek(input: InputStream, count: Int): ByteArray {
        val buf = ByteArray(count)
        val n = readFully(input, buf)
        return buf.copyOf(n)
    }
}
