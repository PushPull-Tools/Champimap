package fr.pushpull.champimap.feature.ventes

import fr.pushpull.champimap.core.db.Species
import fr.pushpull.champimap.core.db.Vente
import fr.pushpull.champimap.feature.stats.DateRange
import fr.pushpull.champimap.feature.stats.StatsTime
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/*
 * Mode vendeur (expérimental) : calculs purs, sans interface.
 *  - montant d'une vente (poids × prix au kilo, arrondi au centime) ;
 *  - formats français (« 12,50 € », « 850 g », « 1,25 kg ») et lecture des saisies ;
 *  - résumé des ventes d'une période (chiffre d'affaires, poids, prix moyen, meilleure journée,
 *    part vendue, répartition par espèce).
 */

private val LOCALE_FR: Locale = Locale.FRANCE

/** Montant d'une vente en centimes : poids (g) × prix au kilo (centimes) / 1000, arrondi au centime. */
fun Vente.amountCents(): Long = amountCents(weightG, pricePerKgCents)

/** Même calcul à partir des valeurs saisies (aperçu en direct dans le formulaire). */
fun amountCents(weightG: Int, pricePerKgCents: Int): Long {
    val raw = weightG.toLong() * pricePerKgCents.toLong()
    return (raw + 500) / 1000 // arrondi au centime le plus proche (valeurs toujours positives)
}

/** Identifiants des sorties liées (champ texte séparé par des virgules). */
fun Vente.sortieIdList(): List<String> = parseIds(sortieIds)

/** « id1,id2 » → liste sans vides. */
fun parseIds(s: String?): List<String> = (s ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }

/** Formats d'affichage et lecture des saisies. */
object VentesFormat {

    /** « 12,50 € » (séparateur de milliers français). */
    fun euros(cents: Long): String = String.format(LOCALE_FR, "%,.2f €", cents / 100.0)

    /** « 850 g » ou « 1,25 kg ». */
    fun weight(g: Long): String =
        if (g < 1000) "$g g" else String.format(LOCALE_FR, "%,.2f kg", g / 1000.0).replace(",00 kg", " kg")

    /** Prix au kilo : « 15,00 €/kg ». */
    fun pricePerKg(cents: Long): String = euros(cents) + "/kg"

    /** Centimes → texte modifiable (« 12,5 » → « 12,50 »), vide si 0. */
    fun priceInput(cents: Int): String = if (cents <= 0) "" else String.format(LOCALE_FR, "%.2f", cents / 100.0)

    /**
     * Lit un prix saisi (« 12,5 », « 12.50 », « 12 € ») → centimes, ou null si illisible.
     * Arrondi au centime.
     */
    fun parsePrice(text: String): Int? {
        // Espaces (y compris insécables), symbole euro retirés ; virgule ou point acceptés.
        val clean = text.replace("€", "").filterNot { it.isWhitespace() }.replace(',', '.')
        if (clean.isEmpty()) return null
        val v = clean.toDoubleOrNull() ?: return null
        if (v < 0 || v > 100_000) return null
        return Math.round(v * 100).toInt()
    }

    /** Lit un poids saisi en grammes (chiffres seulement), ou null. */
    fun parseWeight(text: String): Int? = text.filter { it.isDigit() }.take(7).toIntOrNull()
}

/** Dates des ventes : une vente est rattachée à un jour (enregistré à midi, heure locale). */
object VentesTime {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** Jour → horodatage enregistré (midi : pas de bascule de jour au changement d'heure). */
    fun toMillis(d: LocalDate): Long = d.atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()

    /** Début et fin (exclue) d'une journée, en millisecondes (sorties de la journée). */
    fun dayBounds(d: LocalDate): Pair<Long, Long> =
        d.atStartOfDay(zone).toInstant().toEpochMilli() to d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /** Jour d'une vente. */
    fun dateOf(v: Vente): LocalDate = StatsTime.dateOf(v.soldAt)
}

/** Une part de la répartition par espèce. */
data class SpeciesShare(
    val speciesId: String?,
    val name: String,
    val weightG: Long,
    val amountCents: Long,
    /** Part du chiffre d'affaires (0..1). */
    val share: Float,
)

/** Meilleure journée de vente. */
data class BestDay(val date: LocalDate, val amountCents: Long, val weightG: Long)

/** Tout ce qu'affiche la section « Ventes » des stats pour une période. */
data class VentesSummary(
    val count: Int,
    val amountCents: Long,
    val weightG: Long,
    /** Prix moyen au kilo (centimes), pondéré par le poids ; null si rien de vendu. */
    val avgPricePerKgCents: Long?,
    val bestDay: BestDay?,
    /** Poids vendu / poids récolté sur la période (0..1+), null si rien de récolté. */
    val soldShare: Float?,
    val bySpecies: List<SpeciesShare>,
)

/**
 * Résumé des ventes sur une période.
 * @param harvestedG poids pesé récolté sur la même période (pour la part vendue).
 * @param speciesNames identifiant d'espèce → nom affiché.
 */
fun computeVentesSummary(
    ventes: List<Vente>,
    range: DateRange,
    harvestedG: Long,
    speciesNames: Map<String, String>,
): VentesSummary {
    val inRange = ventes.filter { VentesTime.dateOf(it) in range }
    val amount = inRange.sumOf { it.amountCents() }
    val weight = inRange.sumOf { it.weightG.toLong() }
    val avg = if (weight > 0) (amount * 1000 + weight / 2) / weight else null

    // Meilleure journée : plus gros chiffre d'affaires (à égalité, plus gros poids).
    val best = inRange.groupBy { VentesTime.dateOf(it) }
        .map { (d, list) -> BestDay(d, list.sumOf { it.amountCents() }, list.sumOf { it.weightG.toLong() }) }
        .maxWithOrNull(compareBy<BestDay> { it.amountCents }.thenBy { it.weightG })

    val bySpecies = inRange.groupBy { it.speciesId }
        .map { (id, list) ->
            val a = list.sumOf { it.amountCents() }
            SpeciesShare(
                speciesId = id,
                name = id?.let { speciesNames[it] } ?: "Sans espèce",
                weightG = list.sumOf { it.weightG.toLong() },
                amountCents = a,
                share = if (amount > 0) a.toFloat() / amount else 0f,
            )
        }
        .sortedWith(compareByDescending<SpeciesShare> { it.amountCents }.thenByDescending { it.weightG })

    return VentesSummary(
        count = inRange.size,
        amountCents = amount,
        weightG = weight,
        avgPricePerKgCents = avg,
        bestDay = best,
        soldShare = if (harvestedG > 0) weight.toFloat() / harvestedG else null,
        bySpecies = bySpecies,
    )
}

/** Nom affiché d'une espèce (ou « Sans espèce »). */
fun speciesLabel(id: String?, species: Map<String, Species>): String =
    id?.let { species[it]?.nameFr } ?: if (id == null) "Sans espèce" else "Espèce inconnue"
