package fr.pushpull.champimap.feature.stats

import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.db.HarvestRow
import fr.pushpull.champimap.core.db.WeatherDay
import fr.pushpull.champimap.core.util.AstroCalc
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * Moteur « Météo et récoltes » (expérimental).
 *
 * Principe : pour chaque récolte pesée, on regarde la météo de sa ZONE LARGE (carré de ~25 km,
 * jamais la position du spot) dans les 30 jours d'avant : pluie cumulée 7/10/15/30 j, températures
 * minimales et maximales, gel, écart jour/nuit, et la phase de lune du jour de la récolte.
 * Pour chaque espèce qui a au moins 10 récoltes avec météo, on compare les MEILLEURES récoltes
 * (le tiers le plus lourd) aux autres, et on décrit les fenêtres où elles se concentrent.
 *
 * Tout est calculé sur les seules récoltes de l'utilisateur : aucune donnée inventée.
 * Sans météo (Champimap Connect pas encore synchronisé), rien n'est affiché comme conclusion.
 */

/** Fiabilité annoncée sous chaque conclusion. */
enum class Reliability(val label: String) { FAIBLE("faible"), MOYENNE("moyenne"), BONNE("bonne") }

/** Conclusion pour une espèce. */
data class SpeciesInsight(
    val speciesId: String,
    val speciesName: String,
    /** Phrases lisibles, dans l'ordre d'affichage. */
    val sentences: List<String>,
    val harvestCount: Int,
    val seasonCount: Int,
    val reliability: Reliability,
) {
    /** « Calculé sur 14 récoltes (2 saisons) · fiabilité moyenne ». */
    val footer: String
        get() = "Calculé sur $harvestCount récoltes ($seasonCount saison${if (seasonCount > 1) "s" else ""}) · fiabilité ${reliability.label}"
}

/** Espèce suivie mais pas encore assez documentée. */
data class PendingSpecies(val speciesName: String, val withWeather: Int, val weighed: Int)

/** Résultat complet affiché dans la carte « Météo et récoltes ». */
data class CorrelationReport(
    val insights: List<SpeciesInsight>,
    val pending: List<PendingSpecies>,
    /** Récoltes pesées et localisées au total. */
    val weighedHarvests: Int,
    /** Récoltes pesées pour lesquelles la météo des 30 jours d'avant est connue. */
    val harvestsWithWeather: Int,
)

/** Zone météo à demander à Champimap Connect (centre du carré + période). */
data class WeatherRequest(
    val zoneId: String,
    val centerLat: Double,
    val centerLon: Double,
    /** Dates ISO « AAAA-MM-JJ » incluses. */
    val from: String,
    val to: String,
)

object WeatherCorrelation {

    /** Taille d'une zone météo en degrés (0,25° ≈ 28 km en latitude, ~19 km en longitude en France). */
    const val ZONE_STEP_DEG = 0.25

    /** Nombre minimal de récoltes d'une espèce (avec météo) pour oser une conclusion. */
    const val MIN_HARVESTS = 10

    /** Jours de météo nécessaires avant chaque récolte. */
    const val DAYS_BEFORE = 30

    /**
     * Identifiant de la zone météo (~25 km) contenant un point : latitude et longitude arrondies
     * au 0,25° le plus proche, écrites avec un point décimal, ex. « 45.75_1.25 ».
     * Partagé avec le module connect : c'est la SEULE information de lieu qui sort de l'appli.
     */
    fun zoneIdFor(lat: Double, lon: Double): String {
        val la = snap(lat)
        val lo = snap(lon)
        return String.format(Locale.US, "%.2f_%.2f", la, lo)
    }

    /** Centre (latitude, longitude) de la zone d'un point. */
    fun zoneCenterFor(lat: Double, lon: Double): Pair<Double, Double> = snap(lat) to snap(lon)

    /** Arrondi au pas de zone le plus proche (−0.0 ramené à 0.0 pour un identifiant stable). */
    private fun snap(v: Double): Double = (Math.round(v / ZONE_STEP_DEG) * ZONE_STEP_DEG) + 0.0

    /**
     * Zones et périodes de météo utiles (30 jours avant chaque récolte, jusqu'au jour même),
     * fusionnées par zone. À transmettre à Champimap Connect pour la synchro.
     */
    suspend fun weatherRequests(): List<WeatherRequest> {
        val harvests = AppGraph.db.statsDao().harvests()
        return harvests
            .filter { it.lat != null && it.lon != null }
            .groupBy { zoneIdFor(it.lat!!, it.lon!!) }
            .map { (zone, list) ->
                val first = list.first()
                val (cLat, cLon) = zoneCenterFor(first.lat!!, first.lon!!)
                val minDate = list.minOf { StatsTime.dateOf(it.time).toEpochDay() }
                val maxDate = list.maxOf { StatsTime.dateOf(it.time).toEpochDay() }
                WeatherRequest(
                    zoneId = zone,
                    centerLat = cLat,
                    centerLon = cLon,
                    from = LocalDate.ofEpochDay(minDate - DAYS_BEFORE).toString(),
                    to = LocalDate.ofEpochDay(maxDate).toString(),
                )
            }
    }

    // ---------------------------------------------------------------------------------------
    // Calcul des indicateurs météo de chaque récolte
    // ---------------------------------------------------------------------------------------

    /** Une récolte (une espèce, un jour, un spot) avec ses indicateurs météo. */
    private data class Sample(
        val weightG: Int,
        val date: LocalDate,
        val rain7: Double,
        val rain10: Double,
        val rain15: Double,
        val rain30: Double,
        /** Moyenne des minimales / maximales des 7 jours d'avant. */
        val tMin7: Double,
        val tMax7: Double,
        /** Nombre de jours de gel (Tmin ≤ 0 °C) dans les 15 jours d'avant. */
        val frostDays15: Int,
        /** Écart moyen jour/nuit (Tmax − Tmin) des 7 jours d'avant. */
        val gap7: Double,
        /** Jours écoulés depuis la fin de la semaine la plus pluvieuse du mois (null si mois sec). */
        val lagDays: Int?,
        /** Pluie de cette semaine-là (mm). */
        val lagRainMm: Double?,
        /** Âge de la lune le jour de la récolte (0 = nouvelle lune). */
        val moonAge: Double,
    )

    /**
     * Calcule les indicateurs d'une récolte, ou null si la météo des 30 jours d'avant est incomplète
     * (on n'extrapole jamais un jour manquant).
     */
    private fun sampleOf(weightG: Int, date: LocalDate, zone: String, weather: Map<String, WeatherDay>): Sample? {
        // days[k] = météo du jour (date − k), pour k = 1..30.
        val days = arrayOfNulls<WeatherDay>(DAYS_BEFORE + 1)
        for (k in 1..DAYS_BEFORE) {
            days[k] = weather[key(zone, date.minusDays(k.toLong()).toString())] ?: return null
        }
        fun rain(n: Int) = (1..n).sumOf { days[it]!!.rainMm }
        val last7 = (1..7).map { days[it]!! }

        // Semaine (7 jours glissants) la plus pluvieuse du mois, et délai depuis sa fin.
        var bestEnd = -1
        var bestSum = 0.0
        for (end in 1..(DAYS_BEFORE - 6)) {
            val sum = (end until end + 7).sumOf { days[it]!!.rainMm }
            if (sum > bestSum) { bestSum = sum; bestEnd = end }
        }
        val significant = bestEnd > 0 && bestSum >= 10.0 // une semaine à moins de 10 mm n'est pas un épisode pluvieux

        return Sample(
            weightG = weightG,
            date = date,
            rain7 = rain(7),
            rain10 = rain(10),
            rain15 = rain(15),
            rain30 = rain(30),
            tMin7 = last7.map { it.tMinC }.average(),
            tMax7 = last7.map { it.tMaxC }.average(),
            frostDays15 = (1..15).count { days[it]!!.tMinC <= 0.0 },
            gap7 = last7.map { it.tMaxC - it.tMinC }.average(),
            lagDays = if (significant) bestEnd else null,
            lagRainMm = if (significant) bestSum else null,
            moonAge = AstroCalc.moonAgeDays(date),
        )
    }

    private fun key(zone: String, date: String) = "$zone|$date"

    // ---------------------------------------------------------------------------------------
    // Calcul complet
    // ---------------------------------------------------------------------------------------

    /**
     * Calcule le rapport pour toutes les espèces. À appeler hors du fil principal (lit la base).
     * @param harvests toutes les récoltes à plat (StatsDao.harvests / harvestsFlow).
     */
    suspend fun compute(harvests: List<HarvestRow>): CorrelationReport {
        // 1. Une « récolte » = une espèce sur une récolte (plusieurs saisies de la même espèce additionnées).
        data class Entry(val speciesId: String, val name: String, val weightG: Int, val date: LocalDate, val zone: String)
        val entries = harvests
            .filter { (it.weightG ?: 0) > 0 && it.lat != null && it.lon != null }
            .groupBy { it.recolteId to it.speciesId }
            .map { (_, list) ->
                val h = list.first()
                Entry(
                    speciesId = h.speciesId,
                    name = h.speciesName ?: "cette espèce",
                    weightG = list.sumOf { it.weightG ?: 0 },
                    date = StatsTime.dateOf(h.time),
                    zone = zoneIdFor(h.lat!!, h.lon!!),
                )
            }
        if (entries.isEmpty()) return CorrelationReport(emptyList(), emptyList(), 0, 0)

        // 2. Météo de toutes les zones concernées, en une passe (par paquets pour la limite SQLite).
        val from = entries.minOf { it.date.toEpochDay() } - DAYS_BEFORE
        val to = entries.maxOf { it.date.toEpochDay() }
        val weather = HashMap<String, WeatherDay>()
        val dao = AppGraph.db.weatherDao()
        entries.map { it.zone }.distinct().chunked(400).forEach { zones ->
            dao.forZones(zones, LocalDate.ofEpochDay(from).toString(), LocalDate.ofEpochDay(to).toString())
                .forEach { weather[key(it.zoneId, it.date)] = it }
        }

        // 3. Indicateurs par récolte, puis conclusions par espèce.
        val insights = mutableListOf<SpeciesInsight>()
        val pending = mutableListOf<PendingSpecies>()
        var withWeather = 0
        entries.groupBy { it.speciesId }.forEach { (speciesId, list) ->
            val samples = list.mapNotNull { sampleOf(it.weightG, it.date, it.zone, weather) }
            withWeather += samples.size
            val name = list.first().name
            if (samples.size >= MIN_HARVESTS) {
                insights += insightFor(speciesId, name, samples)
            } else {
                pending += PendingSpecies(name, samples.size, list.size)
            }
        }
        return CorrelationReport(
            insights = insights.sortedByDescending { it.harvestCount },
            pending = pending.sortedByDescending { it.weighed },
            weighedHarvests = entries.size,
            harvestsWithWeather = withWeather,
        )
    }

    /** Conclusions lisibles pour une espèce (≥ MIN_HARVESTS échantillons). */
    private fun insightFor(speciesId: String, name: String, samples: List<Sample>): SpeciesInsight {
        // Meilleures récoltes = le tiers le plus lourd (au moins 3) ; « autres » = le reste.
        val sorted = samples.sortedByDescending { it.weightG }
        val bestCount = maxOf(3, samples.size / 3)
        val best = sorted.take(bestCount)
        val others = sorted.drop(bestCount)
        val lower = name.lowercase(Locale.FRANCE)
        val sentences = mutableListOf<String>()

        // a) Délai après un épisode de pluie.
        val lagged = best.filter { it.lagDays != null }
        if (lagged.size >= (best.size * 0.6).roundToInt().coerceAtLeast(3)) {
            val lag = median(lagged.map { it.lagDays!!.toDouble() }).roundToInt()
            val mm = (floor(quantile(lagged.map { it.lagRainMm!! }, 0.25) / 5.0) * 5).toInt().coerceAtLeast(10)
            val spread = quantile(lagged.map { it.lagDays!!.toDouble() }, 0.75) - quantile(lagged.map { it.lagDays!!.toDouble() }, 0.25)
            sentences += "Tes meilleures récoltes de $lower arrivent environ $lag jours après plus de $mm mm de pluie " +
                "en une semaine" + (if (spread <= 4) " (fenêtre assez régulière)." else " (fenêtre variable, de ${fmt0(quantile(lagged.map { it.lagDays!!.toDouble() }, 0.25))} à ${fmt0(quantile(lagged.map { it.lagDays!!.toDouble() }, 0.75))} jours).")
        } else {
            sentences += "Tes meilleures récoltes de $lower ne suivent pas un épisode de pluie net dans le mois d'avant."
        }

        // b) Cumul de pluie le plus parlant (7, 10, 15 ou 30 jours) : plus grand écart meilleures / autres.
        val rainWindows = listOf(
            7 to { s: Sample -> s.rain7 }, 10 to { s: Sample -> s.rain10 },
            15 to { s: Sample -> s.rain15 }, 30 to { s: Sample -> s.rain30 },
        )
        val (days, pick) = rainWindows.maxBy { (_, f) -> effect(best.map(f), others.map(f)) }
        val bLow = quantile(best.map(pick), 0.25)
        val bHigh = quantile(best.map(pick), 0.75)
        val rainText = "Elles se concentrent entre ${fmt0(bLow)} et ${fmt0(bHigh)} mm de pluie sur les $days jours d'avant"
        sentences += if (others.isNotEmpty()) "$rainText (contre ${fmt0(median(others.map(pick)))} mm pour tes autres récoltes)." else "$rainText."

        // c) Températures de la semaine d'avant (fenêtre interquartile des meilleures).
        sentences += "La semaine d'avant : minimales de ${fmt0(quantile(best.map { it.tMin7 }, 0.25))} à " +
            "${fmt0(quantile(best.map { it.tMin7 }, 0.75))} °C, maximales de ${fmt0(quantile(best.map { it.tMax7 }, 0.25))} à " +
            "${fmt0(quantile(best.map { it.tMax7 }, 0.75))} °C, écart jour/nuit d'environ ${fmt0(median(best.map { it.gap7 }))} °C."

        // d) Gel dans les 15 jours d'avant.
        val frozen = best.count { it.frostDays15 > 0 }
        sentences += when {
            frozen == 0 -> "Aucune n'a suivi de gel dans les deux semaines d'avant."
            frozen * 2 >= best.size -> "La plupart ont suivi au moins un jour de gel dans les deux semaines d'avant."
            else -> "$frozen sur ${best.size} ont suivi un jour de gel dans les deux semaines d'avant."
        }

        // e) Lune : on ne conclut que si une phase regroupe nettement les meilleures récoltes.
        sentences += moonSentence(best)

        val seasons = samples.map { StatsTime.seasonStart(it.date).year }.distinct().size
        val reliability = when {
            samples.size >= 30 && seasons >= 3 -> Reliability.BONNE
            samples.size >= 20 && seasons >= 2 -> Reliability.MOYENNE
            else -> Reliability.FAIBLE
        }
        return SpeciesInsight(speciesId, name, sentences, samples.size, seasons, reliability)
    }

    /** Phrase sur la lune : une phase « gagne » si elle regroupe au moins la moitié des meilleures récoltes. */
    private fun moonSentence(best: List<Sample>): String {
        // Quatre grandes phases de ~7,4 jours, centrées sur nouvelle lune, 1er quartier, pleine lune, dernier quartier.
        val labels = listOf("de la nouvelle lune", "du premier quartier", "de la pleine lune", "du dernier quartier")
        val synodic = 29.530588853
        val groups = best.groupingBy { s -> (floor((s.moonAge / synodic * 4) + 0.5).toInt()) % 4 }.eachCount()
        val top = groups.maxByOrNull { it.value } ?: return "Pas de lien visible avec la lune."
        return if (best.size >= 4 && top.value * 2 >= best.size) {
            "${top.value} sur ${best.size} sont tombées autour ${labels[top.key]} (à confirmer, la lune joue sans doute peu)."
        } else {
            "Pas de lien visible avec la lune."
        }
    }

    // ---------------------------------------------------------------------------------------
    // Petits outils statistiques
    // ---------------------------------------------------------------------------------------

    /** Quantile par interpolation linéaire (q entre 0 et 1). */
    private fun quantile(values: List<Double>, q: Double): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        val pos = q * (s.size - 1)
        val i = floor(pos).toInt()
        val frac = pos - i
        return if (i + 1 < s.size) s[i] + (s[i + 1] - s[i]) * frac else s[i]
    }

    private fun median(values: List<Double>): Double = quantile(values, 0.5)

    /** Écart des médianes ramené à l'écart-type global (taille d'effet simple, toujours positive). */
    private fun effect(a: List<Double>, b: List<Double>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val all = a + b
        val mean = all.average()
        val sd = sqrt(all.sumOf { (it - mean) * (it - mean) } / all.size)
        return if (sd < 1e-9) 0.0 else abs(median(a) - median(b)) / sd
    }

    /** Nombre arrondi sans décimale (« 42 »). */
    private fun fmt0(v: Double): String = v.roundToInt().toString()
}

/**
 * Raccourci public pour le module connect : identifiant de zone météo (~25 km) d'un point.
 * Voir [WeatherCorrelation.zoneIdFor].
 */
fun zoneIdFor(lat: Double, lon: Double): String = WeatherCorrelation.zoneIdFor(lat, lon)
