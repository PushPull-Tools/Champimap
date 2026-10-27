package fr.pushpull.champimap.feature.reglages

import android.content.Context
import android.content.Intent
import android.net.Uri
import fr.pushpull.champimap.BuildConfig
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.db.AppDatabase
import fr.pushpull.champimap.feature.ventes.VentesCsv
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/*
 * Sauvegarde complète et restauration (hors ligne, fichiers choisis par l'utilisateur via Android).
 *
 * Contenu du ZIP : `champimap.db` (base Room, après point de contrôle WAL), `photos/…` et
 * `memos/…` (dossiers privés de l'appli), `champimap-backup.txt` (version). Le ZIP peut être
 * chiffré en entier (BackupCrypto). Les cartes de Connect ne sont pas incluses (retéléchargeables).
 * La base contient tout, ventes du mode vendeur comprises ; une sauvegarde d'une version plus
 * ancienne de la base est mise à niveau par Room (migrations) à la réouverture après restauration.
 * À appeler hors du fil principal (Dispatchers.IO).
 */
object BackupManager {

    /** Dossiers de filesDir inclus dans la sauvegarde. */
    private val DIRS = listOf("photos", "memos")
    private const val INFO = "champimap-backup.txt"
    private const val DB_ENTRY = "champimap.db"

    /** Vrai dès que la base a été fermée par une restauration : l'appli doit redémarrer quoi qu'il arrive. */
    @Volatile var dbClosed: Boolean = false
        private set

    /** Avancement (0..1) et étape lisible. */
    fun interface Progress { fun update(fraction: Float, label: String) }

    /** Nom de fichier proposé : champimap-2026-10-26.zip (ou .champibak si chiffré). */
    fun suggestedName(encrypted: Boolean): String {
        val d = Instant.now().atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.FRANCE))
        return "champimap-$d." + if (encrypted) "champibak" else "zip"
    }

    /** Export complet vers l'URI choisi. `password` vide = non chiffré. */
    fun export(context: Context, uri: Uri, password: CharArray?, progress: Progress) {
        progress.update(0f, "Préparation de la base")
        // Tout le journal WAL est recopié dans le fichier principal : la copie est alors complète.
        AppGraph.db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
        val dbFile = context.getDatabasePath(AppDatabase.FILE_NAME)
        val files = DIRS.flatMap { dir -> File(context.filesDir, dir).walkTopDown().filter { it.isFile }.toList() }
        val total = (files.size + 1).toFloat()

        val raw = context.contentResolver.openOutputStream(uri, "w") ?: throw IOException("Emplacement d'enregistrement indisponible")
        val sink = if (password != null && password.isNotEmpty()) BackupCrypto.encrypt(raw, password) else raw
        ZipOutputStream(BufferedOutputStream(sink, 64 * 1024)).use { zip ->
            zip.putNextEntry(ZipEntry(INFO))
            zip.write("Champimap ${BuildConfig.VERSION_NAME}\nformat=1\ndate=${System.currentTimeMillis()}\n".toByteArray())
            zip.closeEntry()

            progress.update(0.02f, "Base de données")
            zip.putNextEntry(ZipEntry(DB_ENTRY))
            dbFile.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()

            files.forEachIndexed { i, f ->
                zip.putNextEntry(ZipEntry(f.relativeTo(context.filesDir).invariantSeparatorsPath))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                progress.update((i + 2) / total, "Photos ${i + 1} / ${files.size}")
            }
        }
        progress.update(1f, "Terminé")
    }

    /** Le fichier choisi est-il chiffré ? (lecture des 8 premiers octets) */
    fun isEncrypted(context: Context, uri: Uri): Boolean =
        context.contentResolver.openInputStream(uri)?.use { s ->
            val head = ByteArray(BackupCrypto.MAGIC.size)
            var n = 0
            while (n < head.size) { val r = s.read(head, n, head.size - n); if (r < 0) break; n += r }
            n == head.size && head.contentEquals(BackupCrypto.MAGIC)
        } ?: throw IOException("Fichier illisible")

    /**
     * Restauration : extrait dans un dossier temporaire, vérifie, puis remplace la base et les
     * photos. L'appli DOIT redémarrer ensuite (`restartApp`) : la base ouverte est fermée.
     */
    fun restore(context: Context, uri: Uri, password: CharArray?, progress: Progress) {
        val staging = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        try {
            progress.update(0f, "Lecture de la sauvegarde")
            val raw: InputStream = context.contentResolver.openInputStream(uri) ?: throw IOException("Fichier illisible")
            val source = if (password != null && password.isNotEmpty()) BackupCrypto.decrypt(raw, password) else raw
            var count = 0
            ZipInputStream(BufferedInputStream(source, 64 * 1024)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val out = safeChild(staging, e.name)
                    if (e.isDirectory) out.mkdirs() else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                        count++
                        if (count % 10 == 0) progress.update(0.5f, "$count fichiers lus")
                    }
                }
            }
            val newDb = File(staging, DB_ENTRY)
            if (!newDb.isFile || !looksLikeSqlite(newDb)) throw IOException("Ce fichier ne contient pas de base Champimap")

            progress.update(0.8f, "Remplacement des données")
            // Base fermée : plus aucune écriture ne doit partir vers l'ancien fichier.
            dbClosed = true
            runCatching { AppGraph.db.close() }
            val dbFile = context.getDatabasePath(AppDatabase.FILE_NAME)
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()
            newDb.copyTo(dbFile, overwrite = true)
            DIRS.forEach { dir ->
                val target = File(context.filesDir, dir)
                val restored = File(staging, dir)
                target.deleteRecursively()
                if (restored.isDirectory) restored.copyRecursively(target, overwrite = true)
            }
            progress.update(1f, "Terminé")
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Protection « zip slip » : refuse les chemins qui sortiraient du dossier temporaire. */
    private fun safeChild(dir: File, name: String): File {
        val f = File(dir, name)
        if (!f.canonicalPath.startsWith(dir.canonicalPath + File.separator)) throw IOException("Chemin interdit dans la sauvegarde")
        return f
    }

    /** En-tête « SQLite format 3 ». */
    private fun looksLikeSqlite(f: File): Boolean = f.inputStream().use { s ->
        val h = ByteArray(16)
        s.read(h) == 16 && String(h, 0, 15, Charsets.US_ASCII) == "SQLite format 3"
    }

    /** Relance l'appli (nouveau processus) pour rouvrir la base restaurée. */
    fun restartApp(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        val component = launch.component ?: return
        context.startActivity(Intent.makeRestartActivityTask(component))
        Runtime.getRuntime().exit(0)
    }

    /** Export CSV des récoltes (séparateur « ; », lisible par un tableur français). */
    suspend fun exportCsv(context: Context, uri: Uri) {
        val rows = AppGraph.db.backupDao().csvRows()
        val fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.FRANCE)
        fun cell(v: Any?): String {
            val s = v?.toString() ?: ""
            return if (s.any { it == ';' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
        }
        val text = buildString {
            append('﻿') // BOM : accents corrects à l'ouverture dans un tableur
            appendLine("Date;Espèce;Nom latin;Poids (g);Spot;Stade;Qualité;État")
            rows.forEach { r ->
                val d = Instant.ofEpochMilli(r.time).atZone(ZoneId.systemDefault()).format(fmt)
                appendLine(listOf(d, r.species, r.latin, r.weightG, r.spotName, r.stade, r.qualites, r.etats).joinToString(";") { cell(it) })
            }
            // Mode vendeur (expérimental) : bloc « Ventes » après une ligne vide, s'il y a des ventes.
            val ventes = VentesCsv.rows()
            if (ventes.isNotEmpty()) {
                appendLine()
                appendLine(VentesCsv.TITLE)
                appendLine(VentesCsv.HEADER.joinToString(";") { cell(it) })
                ventes.forEach { cols -> appendLine(cols.joinToString(";") { cell(it) }) }
            }
        }
        context.contentResolver.openOutputStream(uri, "w")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: throw IOException("Emplacement d'enregistrement indisponible")
    }
}
