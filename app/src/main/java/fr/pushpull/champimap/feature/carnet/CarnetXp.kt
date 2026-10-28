package fr.pushpull.champimap.feature.carnet

import fr.pushpull.champimap.core.db.PhotoOwner
import fr.pushpull.champimap.core.db.Sortie
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * Calcul de l'XP : fonction PURE sur les données (CarnetData → XpResult).
 * L'XP n'est jamais incrémentée à la main : elle est recalculée à chaque fois, donc rien ne se
 * désynchronise après un import, une fusion de sortie à plusieurs ou une suppression.
 *
 * Barème (principe : récompenser la connaissance, la régularité et le bon suivi, pas le pillage) :
 *  - sortie terminée ..................... 20
 *  - nouveau spot ........................ 30
 *  - espèce jamais trouvée avant ......... 100
 *  - récolte pesée ....................... 10 par tranche de 100 g, 150 XP au plus par jour
 *  - « Spot vide » noté .................. 10 (une fois par spot et par jour)
 *  - photo d'espèce ...................... 5, 6 photos au plus par jour (30 XP)
 *  - sortie à plusieurs .................. +25
 *  - spot qui redonne une année suivante . 50 (une fois par spot et par année)
 *  - succès obtenu ....................... 25 / 75 / 200 / 500 selon sa difficulté (BadgeTier)
 *
 * L'XP des succès vient des succès ENREGISTRÉS en base (un seul enregistrement par identifiant) :
 * comptée exactement une fois, même après mille recalculs, et un succès obtenu garde son XP
 * même si les données qui l'ont déclenché sont supprimées ensuite.
 */
object CarnetXp {

    const val XP_SORTIE = 20
    const val XP_NOUVEAU_SPOT = 30
    const val XP_NOUVELLE_ESPECE = 100
    const val XP_PAR_100G = 10
    const val XP_PESEE_MAX_JOUR = 150
    const val XP_SPOT_VIDE = 10
    const val XP_PHOTO = 5
    const val PHOTOS_MAX_JOUR = 6
    const val XP_PLUSIEURS = 25
    const val XP_SPOT_REDONNE = 50

    /** Sortie technique des passages « hors sortie » (voir SpotRepository.HORS_SORTIE). */
    private const val HORS_SORTIE = ""

    /** Jour local d'un instant. */
    private fun day(time: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()

    /** Identifiant de sortie utilisable (null pour un passage hors sortie). */
    private fun sortieOf(id: String): String? = id.takeIf { it != HORS_SORTIE }

    /** Calcule tous les gains d'XP à partir des données. */
    fun compute(d: CarnetData, zone: ZoneId = ZoneId.systemDefault()): XpResult {
        val events = ArrayList<XpEvent>()
        val ended = d.sorties.filter { it.endedAt != null }

        // 1. Sorties terminées (+ bonus à plusieurs).
        for (s in ended) {
            val at = s.endedAt ?: s.startedAt
            events += XpEvent(XpSource.SORTIE, XP_SORTIE, s.id, at)
            if (s.groupId != null) events += XpEvent(XpSource.PLUSIEURS, XP_PLUSIEURS, s.id, at)
        }

        // 2. Nouveaux spots : rattachés à la sortie de leur premier passage (s'il y en a une).
        val firstVisitSortie = d.visits.sortedBy { it.time }.groupBy { it.spotId }
            .mapValues { (_, v) -> v.firstNotNullOfOrNull { sortieOf(it.sortieId) } }
        for (spot in d.spots) {
            events += XpEvent(XpSource.NOUVEAU_SPOT, XP_NOUVEAU_SPOT, firstVisitSortie[spot.id], spot.createdAt)
        }

        // 3. Espèces jamais trouvées avant (première récolte de chaque espèce).
        val seenSpecies = HashSet<String>()
        for (h in d.harvests) {
            if (seenSpecies.add(h.speciesId)) {
                events += XpEvent(XpSource.NOUVELLE_ESPECE, XP_NOUVELLE_ESPECE, sortieOf(h.sortieId), h.time)
            }
        }

        // 4. Récoltes pesées : 10 XP par 100 g cumulés dans la journée, plafond 150 XP/jour.
        //    Chaque pesée reçoit le supplément d'XP qu'elle apporte au cumul du jour.
        val gramsOfDay = HashMap<LocalDate, Int>()
        for (h in d.harvests) {
            val g = h.weightG ?: continue
            if (g <= 0) continue
            val dd = day(h.time, zone)
            val before = gramsOfDay[dd] ?: 0
            val after = before + g
            gramsOfDay[dd] = after
            val gain = weighXp(after) - weighXp(before)
            if (gain > 0) events += XpEvent(XpSource.PESEE, gain, sortieOf(h.sortieId), h.time)
        }

        // 5. « Spot vide » : une fois par spot et par jour.
        val emptyKeys = HashSet<String>()
        for (v in d.visits) {
            if (!v.empty) continue
            if (emptyKeys.add(v.spotId + "|" + day(v.time, zone))) {
                events += XpEvent(XpSource.SPOT_VIDE, XP_SPOT_VIDE, sortieOf(v.sortieId), v.time)
            }
        }

        // 6. Photos d'espèces : 5 XP, 6 photos au plus par jour.
        val sortieOfItem = d.harvests.associate { it.itemId to sortieOf(it.sortieId) }
        val photosOfDay = HashMap<LocalDate, Int>()
        for (p in d.speciesPhotos.filter { it.ownerType == PhotoOwner.RECOLTE_ITEM }.sortedBy { it.createdAt }) {
            val dd = day(p.createdAt, zone)
            val n = (photosOfDay[dd] ?: 0) + 1
            photosOfDay[dd] = n
            if (n <= PHOTOS_MAX_JOUR) events += XpEvent(XpSource.PHOTO, XP_PHOTO, sortieOfItem[p.ownerId], p.createdAt)
        }

        // 7. Spot qui redonne une année suivante : 50 XP par année productive après la première.
        for ((_, list) in d.harvests.groupBy { it.spotId }) {
            val firstOfYear = list.groupBy { day(it.time, zone).year }.toSortedMap().values.map { it.first() }
            firstOfYear.drop(1).forEach { h ->
                events += XpEvent(XpSource.SPOT_REDONNE, XP_SPOT_REDONNE, sortieOf(h.sortieId), h.time)
            }
        }

        return XpResult(events.sortedBy { it.time })
    }

    /** Délai après la fin d'une sortie pendant lequel un succès lui est encore rattaché (recalcul différé). */
    private const val RATTACHEMENT_SUCCES_MS = 2 * 3_600_000L

    /**
     * Gains d'XP des succès obtenus : un gain par succès connu, daté de son obtention, rattaché à la
     * sortie en cours à ce moment-là (ou terminée depuis moins de 2 h), sinon à aucune sortie.
     * Fonction pure : mêmes succès → mêmes gains (pas de double comptage possible).
     * @param unlocked succès obtenus : identifiant → date d'obtention.
     */
    fun badgeEvents(unlocked: Map<String, Long>, sorties: List<Sortie>): List<XpEvent> =
        unlocked.mapNotNull { (id, at) ->
            val def = CarnetBadges.BY_ID[id] ?: return@mapNotNull null
            val sortie = sorties
                .filter { s -> s.startedAt <= at && at <= (s.endedAt ?: Long.MAX_VALUE - RATTACHEMENT_SUCCES_MS) + RATTACHEMENT_SUCCES_MS }
                .maxByOrNull { it.startedAt }
            XpEvent(XpSource.SUCCES, def.xp, sortie?.id, at)
        }

    /** XP de pesée pour un cumul de grammes dans la journée (plafonnée). */
    private fun weighXp(grams: Int): Int = ((grams / 100) * XP_PAR_100G).coerceAtMost(XP_PESEE_MAX_JOUR)
}
