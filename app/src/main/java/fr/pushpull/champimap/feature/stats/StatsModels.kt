package fr.pushpull.champimap.feature.stats

import fr.pushpull.champimap.core.db.HarvestRow
import fr.pushpull.champimap.core.db.Person
import fr.pushpull.champimap.core.db.Sortie
import fr.pushpull.champimap.core.db.VisitRow
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs

/*
 * Modèles et calculs purs du module stats (aucune interface ici) :
 *  - périodes Jour / Semaine / Mois / Saison (saison = 1er juin → 31 mai) ;
 *  - formats français (« 14,2 kg ») ;
 *  - résumé d'une période (total, sorties, spots, barres, podium) ;
 *  - spots qui ont donné à la même période les années précédentes (calendrier, alertes).
 */

/** Périodes du sélecteur segmenté. */
enum class StatsPeriod(val label: String) {
    JOUR("Jour"), SEMAINE("Semaine"), MOIS("Mois"), SAISON("Saison"),
}

/** Intervalle de dates [start ; endExclusive[. */
data class DateRange(val start: LocalDate, val endExclusive: LocalDate) {
    operator fun contains(d: LocalDate): Boolean = !d.isBefore(start) && d.isBefore(endExclusive)
}

/** Une case du graphique en barres (un mois ou un jour). */
data class Bucket(val range: DateRange, val label: String, val fullLabel: String)

/** Valeur d'une barre. `highlight` = la barre contient aujourd'hui. */
data class BarValue(val label: String, val fullLabel: String, val grams: Long, val highlight: Boolean)

/** Une marche du podium. */
data class PodiumEntry(val personId: String, val name: String, val grams: Long, val isMe: Boolean)

/** Tout ce qu'affiche l'écran Statistiques pour une période. */
data class PeriodSummary(
    val totalG: Long,
    val sorties: Int,
    val spots: Int,
    /** Espèces cueillies sans poids sur la période (non comptées dans le total). */
    val unweighed: Int,
    val bars: List<BarValue>,
    val podium: List<PodiumEntry>,
)

/** Un spot qui a donné autour d'une date, les années précédentes. */
data class PastSpot(
    val spotId: String,
    val spotName: String,
    val spotStatus: String?,
    val grams: Long,
    val items: Int,
    /** Date de la récolte la plus récente retenue (pour « l'an dernier » / « en 2024 »). */
    val lastDate: LocalDate,
)

private val LOCALE_FR: Locale = Locale.FRANCE

/** Formats d'affichage français. */
object StatsFormat {
    /** « 14,2 kg » (une décimale, virgule, séparateur de milliers français). */
    fun kg(grams: Long): String = String.format(LOCALE_FR, "%,.1f kg", grams / 1000.0)

    /** « 9 h 05 ». */
    fun time(millis: Long): String {
        val t = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime()
        return String.format(LOCALE_FR, "%d h %02d", t.hour, t.minute)
    }

    /** « 24 octobre 2025 ». */
    fun date(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("d MMMM yyyy", LOCALE_FR))

    /** « 24 octobre ». */
    fun dayMonth(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("d MMMM", LOCALE_FR))

    /** Jour seul à la française : « 1er », « 2 », « 26 ». */
    fun dayFr(d: LocalDate): String = if (d.dayOfMonth == 1) "1er" else d.dayOfMonth.toString()

    /** Jour et mois à la française : « 1er novembre », « 26 octobre ». */
    fun dayMonthFr(d: LocalDate): String = "${dayFr(d)} ${d.format(DateTimeFormatter.ofPattern("MMMM", LOCALE_FR))}"

    /** Première lettre en majuscule (« octobre 2026 » → « Octobre 2026 »). */
    fun capitalize(s: String): String = s.replaceFirstChar { if (it.isLowerCase()) it.titlecase(LOCALE_FR) else it.toString() }
}

/** Calculs de dates (fuseau du téléphone). */
object StatsTime {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** Date locale d'un horodatage en millisecondes. */
    fun dateOf(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    /** Début de la saison mycologique contenant `d` : le 1er juin précédent (ou le jour même). */
    fun seasonStart(d: LocalDate): LocalDate =
        if (d.monthValue >= 6) LocalDate.of(d.year, 6, 1) else LocalDate.of(d.year - 1, 6, 1)

    /** Période affichée : `offset` = 0 pour la période en cours, -1 pour la précédente, etc. */
    fun rangeFor(period: StatsPeriod, offset: Int, today: LocalDate): DateRange = when (period) {
        StatsPeriod.JOUR -> today.plusDays(offset.toLong()).let { DateRange(it, it.plusDays(1)) }
        StatsPeriod.SEMAINE -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .plusWeeks(offset.toLong()).let { DateRange(it, it.plusWeeks(1)) }
        StatsPeriod.MOIS -> today.withDayOfMonth(1).plusMonths(offset.toLong()).let { DateRange(it, it.plusMonths(1)) }
        StatsPeriod.SAISON -> seasonStart(today).plusYears(offset.toLong()).let { DateRange(it, it.plusYears(1)) }
    }

    /** Libellé lisible de la période (« Aujourd'hui », « Semaine du 19 au 25 octobre », « Semaine du 26 octobre au 1er novembre », « Saison 2026-2027 »…). */
    fun label(period: StatsPeriod, range: DateRange, offset: Int): String = when (period) {
        StatsPeriod.JOUR -> when (offset) {
            0 -> "Aujourd'hui"
            -1 -> "Hier"
            else -> StatsFormat.capitalize(range.start.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", LOCALE_FR)))
        }
        StatsPeriod.SEMAINE -> {
            val last = range.endExclusive.minusDays(1)
            val start = range.start
            // Mois répété quand la semaine change de mois (« du 26 octobre au 1er novembre »).
            val from = if (start.month == last.month) StatsFormat.dayFr(start) else StatsFormat.dayMonthFr(start)
            "Semaine du $from au ${StatsFormat.dayMonthFr(last)}"
        }
        StatsPeriod.MOIS -> StatsFormat.capitalize(range.start.format(DateTimeFormatter.ofPattern("MMMM yyyy", LOCALE_FR)))
        StatsPeriod.SAISON -> "Saison ${range.start.year}-${range.start.year + 1}"
    }

    /** Cases du graphique : les 12 mois de la saison, ou les jours du mois / de la semaine. */
    fun buckets(period: StatsPeriod, range: DateRange): List<Bucket> = when (period) {
        StatsPeriod.SAISON -> (0 until 12).map { i ->
            val m = range.start.plusMonths(i.toLong())
            Bucket(
                DateRange(m, m.plusMonths(1)),
                label = MONTH_LETTERS[m.monthValue - 1],
                fullLabel = StatsFormat.capitalize(m.format(DateTimeFormatter.ofPattern("MMMM yyyy", LOCALE_FR))),
            )
        }
        StatsPeriod.MOIS, StatsPeriod.SEMAINE -> {
            val days = ChronoUnit.DAYS.between(range.start, range.endExclusive).toInt()
            (0 until days).map { i ->
                val d = range.start.plusDays(i.toLong())
                val label = if (period == StatsPeriod.SEMAINE) WEEKDAY_LETTERS[d.dayOfWeek.value - 1]
                else if (d.dayOfMonth == 1 || d.dayOfMonth % 5 == 0) d.dayOfMonth.toString() else ""
                Bucket(DateRange(d, d.plusDays(1)), label, StatsFormat.capitalize(d.format(DateTimeFormatter.ofPattern("EEEE d MMMM", LOCALE_FR))))
            }
        }
        StatsPeriod.JOUR -> emptyList()
    }

    /**
     * Écart en jours entre deux dates en ignorant l'année (24 oct. 2025 et 26 oct. 2026 → 2).
     * Gère le passage d'année (30 déc. / 2 janv. → 3).
     */
    fun daysApartIgnoringYear(a: LocalDate, b: LocalDate): Int {
        val base = a.withYear(b.year) // le 29 février devient le 28 si besoin
        return listOf(-1L, 0L, 1L).minOf { y -> abs(ChronoUnit.DAYS.between(base.plusYears(y), b)) }.toInt()
    }

    val MONTH_LETTERS = listOf("J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D")
    val WEEKDAY_LETTERS = listOf("L", "M", "M", "J", "V", "S", "D")
}

/** Calcule le résumé d'une période à partir des données à plat. */
fun computeSummary(
    harvests: List<HarvestRow>,
    sorties: List<Sortie>,
    visits: List<VisitRow>,
    persons: List<Person>,
    period: StatsPeriod,
    range: DateRange,
    today: LocalDate,
): PeriodSummary {
    // Récoltes de la période, avec leur date locale pré-calculée.
    val inRange = harvests.mapNotNull { h -> StatsTime.dateOf(h.time).takeIf { it in range }?.let { it to h } }
    val total = inRange.sumOf { (it.second.weightG ?: 0).toLong() }
    val unweighed = inRange.count { (it.second.weightG ?: 0) <= 0 }

    val sortieCount = sorties.count { StatsTime.dateOf(it.startedAt) in range }

    // Spots = spots où l'on a récolté OU qu'on a visités (même « spot vide ») sur la période.
    val spotIds = HashSet<String>()
    inRange.forEach { spotIds += it.second.spotId }
    visits.forEach { v -> if (StatsTime.dateOf(v.time) in range) spotIds += v.spotId }

    val bars = StatsTime.buckets(period, range).map { b ->
        BarValue(
            label = b.label,
            fullLabel = b.fullLabel,
            grams = inRange.filter { it.first in b.range }.sumOf { (it.second.weightG ?: 0).toLong() },
            highlight = today in b.range,
        )
    }

    // Podium : poids par personne (authorId de l'espèce saisie), trois premiers.
    val byId = persons.associateBy { it.id }
    val podium = inRange.map { it.second }
        .filter { (it.weightG ?: 0) > 0 }
        .groupBy { it.authorId }
        .map { (id, list) ->
            val p = byId[id]
            PodiumEntry(id, p?.name?.takeIf { it.isNotBlank() } ?: "Inconnu", list.sumOf { (it.weightG ?: 0).toLong() }, p?.isMe == true)
        }
        .sortedByDescending { it.grams }
        .take(3)

    return PeriodSummary(total, sortieCount, spotIds.size, unweighed, bars, podium)
}

/** Spots qui ont donné à ± `windowDays` jours de la même date, lors des saisons précédentes. */
object PastYears {
    fun goodSpotsAround(harvests: List<HarvestRow>, target: LocalDate, windowDays: Int): List<PastSpot> =
        harvests
            .mapNotNull { h ->
                val d = StatsTime.dateOf(h.time)
                // « Années précédentes » = au moins ~10 mois avant la date visée.
                val previousSeason = ChronoUnit.DAYS.between(d, target) > 300
                if (previousSeason && StatsTime.daysApartIgnoringYear(d, target) <= windowDays) d to h else null
            }
            .groupBy { it.second.spotId }
            .map { (spotId, list) ->
                val first = list.first().second
                PastSpot(
                    spotId = spotId,
                    spotName = first.spotName ?: "Spot",
                    spotStatus = first.spotStatus,
                    grams = list.sumOf { (it.second.weightG ?: 0).toLong() },
                    items = list.size,
                    lastDate = list.maxBy { it.first.toEpochDay() }.first,
                )
            }
            .sortedWith(compareByDescending<PastSpot> { it.grams }.thenByDescending { it.items })
}
