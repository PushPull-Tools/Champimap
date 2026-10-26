package fr.pushpull.champimap.feature.carnet

import fr.pushpull.champimap.core.db.CarnetHarvest
import fr.pushpull.champimap.core.util.AstroCalc
import fr.pushpull.champimap.feature.reglages.Departements
import fr.pushpull.champimap.feature.stats.zoneIdFor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/*
 * Conditions des succès : fonction PURE sur les données (CarnetData → ensemble des succès remplis).
 * Recalculée à chaque changement : un succès obtenu reste obtenu (il est enregistré en base avec sa date),
 * même si les données qui l'ont déclenché sont supprimées ensuite.
 * Une condition par identifiant de CarnetBadges ; les textes sont dans CarnetTextes.kt.
 */
object CarnetRules {

    // ------------------------------------------------------------------ Espèces repères (ids de species.json)

    private val CEPES = setOf("boletus-edulis", "boletus-aereus", "boletus-reticulatus", "boletus-pinophilus")
    private val BOLETS_COURANTS = CEPES + setOf(
        "imleria-badia", "xerocomellus-chrysenteron", "leccinum-scabrum", "leccinum-versipelle",
        "suillus-luteus", "neoboletus-erythropus",
    )
    private const val GIROLLE = "cantharellus-cibarius"
    private const val TROMPETTE = "craterellus-cornucopioides"
    private val CHANTERELLES = setOf(GIROLLE, TROMPETTE, "craterellus-tubaeformis", "craterellus-lutescens")
    private const val ORONGE = "amanita-caesarea"
    private const val PIED_BLEU = "lepista-nuda"
    private const val SAINT_GEORGES = "calocybe-gambosa"

    private fun isMorille(id: String) = id.startsWith("morchella-")
    private fun isPiedDeMouton(id: String) = id.startsWith("hydnum-")
    private fun isTruffe(id: String) = id.startsWith("tuber-")
    private fun isRussule(id: String) = id.startsWith("russula-")

    private const val HEURE_MS = 3_600_000L
    private const val HORS_SORTIE = ""

    /** Renvoie les identifiants de tous les succès dont la condition est remplie. */
    fun evaluate(d: CarnetData, zone: ZoneId = ZoneId.systemDefault()): Set<String> {
        val ok = HashSet<String>()
        fun put(id: String, cond: Boolean) { if (cond) ok += id }

        // Données dérivées, calculées une fois.
        fun date(t: Long): LocalDate = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
        val ended = d.sorties.filter { it.endedAt != null }
        val h = d.harvests
        val species = h.map { it.speciesId }.toSet()
        val harvestMonths = h.map { date(it.time).monthValue }
        val emptyVisits = d.visits.filter { it.empty }
        val yearsBySpot: Map<String, Set<Int>> = h.groupBy { it.spotId }.mapValues { (_, l) -> l.map { date(it.time).year }.toSet() }
        val maxYears = yearsBySpot.values.maxOfOrNull { it.size } ?: 0
        val totalKm = d.distanceM.values.sum() / 1000.0

        // --- Premières fois
        put("premiere_sortie", ended.isNotEmpty())
        put("premier_champignon", h.isNotEmpty())
        put("premier_spot", d.spots.isNotEmpty())
        put("premiere_pesee", h.any { (it.weightG ?: 0) > 0 })
        put("premiere_photo", d.speciesPhotos.isNotEmpty())
        put("premier_spot_vide", emptyVisits.isNotEmpty())
        put("premiere_sortie_plusieurs", ended.any { it.groupId != null })
        put("premier_cepe", species.any { it in CEPES })
        put("premiere_girolle", GIROLLE in species)
        put("premiere_morille", species.any { isMorille(it) })
        put("premiere_trompette", TROMPETTE in species)
        put("premier_retour", spotSortieCounts(d).values.any { it >= 2 })

        // --- Collection
        put("especes_5", species.size >= 5)
        put("especes_10", species.size >= 10)
        put("especes_25", species.size >= 25)
        put("especes_50", species.size >= 50)
        put("especes_100", species.size >= 100)
        put("tous_cepes", species.containsAll(CEPES))
        put("bolets_courants", species.containsAll(BOLETS_COURANTS))
        put("chanterelles", species.containsAll(CHANTERELLES))
        put(
            "trio_limousin",
            h.filter { it.sortieId != HORS_SORTIE }.groupBy { it.sortieId }.values.any { l ->
                val s = l.map { it.speciesId }.toSet()
                s.any { it in CEPES } && GIROLLE in s && TROMPETTE in s
            },
        )
        put("russules_5", species.count { isRussule(it) } >= 5)
        put("oronge", ORONGE in species)
        put("pied_bleu", PIED_BLEU in species)
        put("truffe", species.any { isTruffe(it) })
        put("cumul_10kg", h.sumOf { (it.weightG ?: 0).toLong() } >= 10_000)

        // --- Saisons
        put("morilles_printemps", h.any { isMorille(it.speciesId) && date(it.time).monthValue in 3..5 })
        put("pieds_mouton_hiver", h.any { isPiedDeMouton(it.speciesId) && date(it.time).monthValue in WINTER })
        put("st_georges", h.any { it.speciesId == SAINT_GEORGES && date(it.time).monthValue in 4..5 })
        put("douze_mois", ended.map { date(it.startedAt).monthValue }.toSet().size == 12)
        put("quatre_saisons", harvestMonths.map { seasonOf(it) }.toSet().size == 4)
        put("sortie_hiver", harvestMonths.any { it in WINTER })
        put("cepe_ete", h.any { it.speciesId in CEPES && date(it.time).monthValue in 6..7 })
        put("trompettes_toussaint", h.any { it.speciesId == TROMPETTE && isToussaint(date(it.time)) })
        put("leve_tot", ended.any { Instant.ofEpochMilli(it.startedAt).atZone(zone).hour in 3..6 })
        put("pleine_lune", ended.any { AstroCalc.moonPhase(date(it.startedAt)) == AstroCalc.MoonPhase.PLEINE })

        // --- Fidélité
        put("fidele_3", maxYears >= 3)
        put("fidele_5", maxYears >= 5)
        put("fidele_10", maxYears >= 10)
        put("spot_redonne", maxYears >= 2)
        put("spot_10_visites", spotDays(d, zone).values.any { it >= 10 })
        put("spots_productifs_10", h.map { it.spotId }.toSet().size >= 10)
        val first = ended.minOfOrNull { it.startedAt }?.let { Instant.ofEpochMilli(it).atZone(zone) }
        val now: ZonedDateTime = Instant.ofEpochMilli(d.now).atZone(zone)
        put("anniversaire_1", first != null && !first.plusYears(1).isAfter(now))
        put("anniversaire_5", first != null && !first.plusYears(5).isAfter(now))

        // --- Explorateur (département = centre le plus proche, approximatif mais hors ligne)
        val depts = d.spots.map { Departements.plusProche(it.lat, it.lon).code }.toSet()
        put("depts_2", depts.size >= 2)
        put("depts_5", depts.size >= 5)
        put("km_100", totalKm >= 100)
        put("km_500", totalKm >= 500)
        put("km_1000", totalKm >= 1000)
        put("sortie_10km", d.distanceM.values.any { it >= 10_000 })
        put("longue_sortie", ended.any { (it.endedAt ?: it.startedAt) - it.startedAt >= 4 * HEURE_MS })
        put("spots_10", d.spots.size >= 10)
        put("spots_50", d.spots.size >= 50)

        // --- Météo (seulement si Champimap Connect a importé la météo)
        if (d.weather.isNotEmpty()) evaluateWeather(d, { t -> date(t) }, ok)

        // --- Persévérance
        put("vides_10_affilee", longestEmptyStreak(d) >= 10)
        put("vides_25", emptyVisits.size >= 25)
        put(
            "bredouille",
            ended.any { s -> emptyVisits.any { it.sortieId == s.id } && h.none { it.sortieId == s.id } },
        )
        put("retour_apres_vide", harvestAfterEmpty(d))
        put("sorties_10", ended.size >= 10)
        put("sorties_25", ended.size >= 25)
        put("sorties_50", ended.size >= 50)
        put("sorties_100", ended.size >= 100)
        val starts = ended.map { it.startedAt }.sorted()
        put("trois_en_semaine", starts.indices.any { i -> i + 2 < starts.size && starts[i + 2] - starts[i] <= 7 * 24 * HEURE_MS })

        // --- Précision
        val precise = d.spots.count { s ->
            val best = listOfNotNull(s.accuracyM, d.bestAccuracy[s.id]).minOrNull()
            best != null && best <= 3f
        }
        put("spot_3m", precise >= 1)
        put("spots_3m_10", precise >= 10)
        put("au_pied_5", d.auPiedCount >= 5)
        put(
            "fiche_complete",
            d.spots.any { s ->
                !s.essence.isNullOrBlank() && s.rating != null && !s.repere.isNullOrBlank() && s.id in d.spotsWithPhoto
            },
        )
        put("pesees_10", h.count { (it.weightG ?: 0) > 0 } >= 10)
        put("photos_50", d.speciesPhotos.size >= 50)

        // --- Secrets (réglages)
        put("secret_easter", d.easterUnlocked)
        put("secret_party", d.psycheUnlocked)
        put("secret_champignong", d.champignong)
        put("pluie_feuilles", "leaves" in d.rainsSeen)
        put("pluie_eau", "rain" in d.rainsSeen)
        put("pluie_champignons", "mushrooms" in d.rainsSeen)

        return ok
    }

    // ------------------------------------------------------------------ Météo

    /** Clé de la table météo : « zone|AAAA-MM-JJ ». */
    fun weatherKey(zoneId: String, date: LocalDate): String = "$zoneId|$date"

    /** Succès météo : sortie sous la pluie, après une gelée, après un déluge, en sécheresse, chaud, froid. */
    private fun evaluateWeather(d: CarnetData, date: (Long) -> LocalDate, ok: MutableSet<String>) {
        fun w(zoneId: String, day: LocalDate) = d.weather[weatherKey(zoneId, day)]

        // Sorties : zone du départ (voiture) ou, à défaut, du premier spot récolté.
        for (s in d.sorties.filter { it.endedAt != null }) {
            val lat = s.startLat ?: d.harvests.firstOrNull { it.sortieId == s.id }?.lat ?: continue
            val lon = s.startLon ?: d.harvests.firstOrNull { it.sortieId == s.id }?.lon ?: continue
            val day = w(zoneIdFor(lat, lon), date(s.startedAt)) ?: continue
            if (day.rainMm >= 5.0) ok += "sous_la_pluie"
            if (day.tMaxC >= 30.0) ok += "canicule"
            if (day.tMaxC <= 5.0) ok += "grand_froid"
        }

        // Récoltes : météo des jours précédents, dans la zone du spot.
        for (hv in d.harvests) {
            val zoneId = zoneIdFor(hv.lat, hv.lon)
            val day = date(hv.time)
            val veille = w(zoneId, day.minusDays(1))
            val jour = w(zoneId, day)
            if ((veille != null && veille.tMinC <= 0.0) || (jour != null && jour.tMinC <= 0.0)) ok += "apres_gelee"
            val week = (1..7).mapNotNull { w(zoneId, day.minusDays(it.toLong())) }
            if (week.sumOf { it.rainMm } >= 40.0) ok += "apres_deluge"
            val fortnight = (1..15).mapNotNull { w(zoneId, day.minusDays(it.toLong())) }
            if (fortnight.size == 15 && fortnight.sumOf { it.rainMm } < 5.0) ok += "sourcier"
        }
    }

    // ------------------------------------------------------------------ Aides

    private val WINTER = setOf(12, 1, 2)

    /** Saison météorologique d'un mois : 0 printemps, 1 été, 2 automne, 3 hiver. */
    private fun seasonOf(month: Int): Int = when (month) {
        in 3..5 -> 0
        in 6..8 -> 1
        in 9..11 -> 2
        else -> 3
    }

    /** Du 25 octobre au 5 novembre. */
    private fun isToussaint(d: LocalDate): Boolean =
        (d.monthValue == 10 && d.dayOfMonth >= 25) || (d.monthValue == 11 && d.dayOfMonth <= 5)

    /** Pour chaque spot : nombre de sorties différentes où il a été visité ou récolté. */
    private fun spotSortieCounts(d: CarnetData): Map<String, Int> {
        val pairs = d.visits.filter { it.sortieId != HORS_SORTIE }.map { it.spotId to it.sortieId } +
            d.harvests.filter { it.sortieId != HORS_SORTIE }.map { it.spotId to it.sortieId }
        return pairs.toSet().groupBy { it.first }.mapValues { it.value.size }
    }

    /** Pour chaque spot : nombre de jours différents de passage (visite ou récolte). */
    private fun spotDays(d: CarnetData, zone: ZoneId): Map<String, Int> {
        fun date(t: Long) = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
        val pairs = d.visits.map { it.spotId to date(it.time) } + d.harvests.map { it.spotId to date(it.time) }
        return pairs.toSet().groupBy { it.first }.mapValues { it.value.size }
    }

    /** Plus longue série de « Spot vide » consécutifs, interrompue par une récolte. */
    private fun longestEmptyStreak(d: CarnetData): Int {
        val events = d.visits.filter { it.empty }.map { it.time to true } + d.harvests.map { it.time to false }
        var best = 0
        var cur = 0
        for ((_, empty) in events.sortedBy { it.first }) {
            cur = if (empty) cur + 1 else 0
            if (cur > best) best = cur
        }
        return best
    }

    /** Une récolte sur un spot dont le passage précédent (autre sortie) était un « Spot vide ». */
    private fun harvestAfterEmpty(d: CarnetData): Boolean {
        val bySpot = HashMap<String, MutableList<Triple<Long, String, Boolean>>>()
        d.visits.filter { it.empty }.forEach { bySpot.getOrPut(it.spotId) { ArrayList() } += Triple(it.time, it.sortieId, true) }
        d.harvests.forEach { hv: CarnetHarvest -> bySpot.getOrPut(hv.spotId) { ArrayList() } += Triple(hv.time, hv.sortieId, false) }
        for (list in bySpot.values) {
            val sorted = list.sortedBy { it.first }
            for (i in 1 until sorted.size) {
                val prev = sorted[i - 1]
                val cur = sorted[i]
                if (prev.third && !cur.third && prev.second != cur.second) return true
            }
        }
        return false
    }
}
