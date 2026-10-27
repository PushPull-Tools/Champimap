package fr.pushpull.champimap.feature.ventes

import fr.pushpull.champimap.core.AppGraph
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Mode vendeur (expérimental) : lignes « Ventes » ajoutées aux exports CSV
 * (Stats → Export CSV et Sauvegarde → Exporter en CSV), après les récoltes.
 * Chaque export échappe lui-même les cellules (séparateur « ; »). Nombres au format français.
 * Les ventes sont exportées même si le mode est coupé : ce sont des données de l'utilisateur.
 */
object VentesCsv {

    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRANCE)

    /** Titre du bloc et en-tête des colonnes. */
    const val TITLE = "Ventes"
    val HEADER = listOf("Date", "Espèce", "Poids vendu (g)", "Prix au kilo (€)", "Montant (€)", "Sorties liées", "Note")

    /** Une ligne par vente hors corbeille (de la plus ancienne à la plus récente). Vide si aucune vente. */
    suspend fun rows(): List<List<String>> {
        val db = AppGraph.db
        val ventes = db.venteDao().all()
        if (ventes.isEmpty()) return emptyList()
        val ids = ventes.mapNotNull { it.speciesId }.distinct()
        val names = if (ids.isEmpty()) emptyMap() else db.venteDao().speciesByIds(ids).associate { it.id to it.nameFr }
        return ventes.map { v ->
            listOf(
                VentesTime.dateOf(v).format(DATE_FMT),
                v.speciesId?.let { names[it] ?: it }.orEmpty(),
                v.weightG.toString(),
                number(v.pricePerKgCents.toLong()),
                number(v.amountCents()),
                v.sortieIdList().size.toString(),
                v.note.orEmpty(),
            )
        }
    }

    /** Centimes → « 12,50 » (sans symbole, lisible par un tableur français). */
    private fun number(cents: Long): String = String.format(Locale.FRANCE, "%.2f", cents / 100.0)
}
