package fr.pushpull.champimap.feature.stats

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import fr.pushpull.champimap.core.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * Export CSV des récoltes, partagé via le menu de partage Android.
 * Format pensé pour un tableur français : séparateur « ; », encodage UTF-8 avec BOM.
 * Les coordonnées GPS des spots ne sont PAS exportées (seulement leur nom) :
 * le fichier quitte l'appli, il ne doit pas révéler où sont les coins.
 */
object CsvExport {

    private const val HEADER =
        "Date;Heure;Spot;Espèce;Nom latin;Poids (g);Stade;Qualité;État;Saisi par"

    /** Crée le fichier dans cacheDir/exports/ et renvoie son URI partageable (FileProvider). */
    suspend fun writeHarvestsCsv(context: Context): Uri = withContext(Dispatchers.IO) {
        val dao = AppGraph.db.statsDao()
        val rows = dao.harvests()
        val names = dao.persons().associate { it.id to it.name }
        val zone = ZoneId.systemDefault()
        val dateFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

        val sb = StringBuilder()
        sb.append('﻿') // BOM : accents corrects à l'ouverture dans un tableur
        sb.append(HEADER).append("\r\n")
        rows.forEach { r ->
            val dt = Instant.ofEpochMilli(r.time).atZone(zone)
            val cols = listOf(
                dt.format(dateFmt),
                dt.format(timeFmt),
                r.spotName.orEmpty(),
                r.speciesName ?: r.speciesId,
                r.speciesLatin.orEmpty(),
                r.weightG?.toString().orEmpty(),
                r.stade.orEmpty(),
                r.qualites.orEmpty(),
                r.etats.orEmpty(),
                names[r.authorId].orEmpty(),
            )
            sb.append(cols.joinToString(";") { escape(it) }).append("\r\n")
        }

        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "champimap-recoltes-${LocalDate.now()}.csv")
        file.writeText(sb.toString(), Charsets.UTF_8)
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    /** Ouvre le menu de partage Android avec le fichier CSV. */
    fun share(context: Context, uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Récoltes Champimap")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Exporter les récoltes").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    /** Échappement CSV : guillemets si le texte contient « ; », un guillemet ou un retour à la ligne. */
    private fun escape(s: String): String =
        if (s.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
