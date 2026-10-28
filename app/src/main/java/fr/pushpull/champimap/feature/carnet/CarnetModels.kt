package fr.pushpull.champimap.feature.carnet

import fr.pushpull.champimap.core.db.CarnetHarvest
import fr.pushpull.champimap.core.db.Photo
import fr.pushpull.champimap.core.db.Sortie
import fr.pushpull.champimap.core.db.Spot
import fr.pushpull.champimap.core.db.SpotVisit
import fr.pushpull.champimap.core.db.WeatherDay
import kotlinx.serialization.Serializable

/*
 * Modèles du carnet (niveaux et succès) : familles, liste FIXE des succès, sources d'XP,
 * instantané des données (entrée des calculs purs) et résultats.
 * Les textes affichés sont tous dans CarnetTextes.kt.
 */

/** Familles de succès, dans l'ordre d'affichage du carnet. */
enum class BadgeFamily { PREMIERES, COLLECTION, SAISONS, FIDELITE, EXPLORATEUR, METEO, PERSEVERANCE, PRECISION, SECRETS }

/**
 * Difficulté d'un succès et XP qu'il rapporte (une seule fois, à son obtention).
 * Repères du barème : sortie 20, nouveau spot 30, nouvelle espèce 100, coût d'un niveau ~415 au début.
 *  - FACILE ....... 25  (une première fois : à peine plus qu'une sortie)
 *  - MOYEN ........ 75  (quelques sorties ciblées ou un peu de chance)
 *  - DIFFICILE .... 200 (une saison bien remplie, ~ un demi-niveau)
 *  - LEGENDAIRE ... 500 (des années de cueillette, ~ un niveau entier)
 */
enum class BadgeTier(val xp: Int) { FACILE(25), MOYEN(75), DIFFICILE(200), LEGENDAIRE(500) }

/** Définition d'un succès. `secret` = invisible tant qu'il n'est pas obtenu. */
data class BadgeDef(
    val id: String,
    val family: BadgeFamily,
    val secret: Boolean = false,
    val tier: BadgeTier = BadgeTier.FACILE,
) {
    /** XP rapportée par ce succès. */
    val xp: Int get() = tier.xp
}

/**
 * Liste complète et FINIE des succès (80). L'ordre est celui de l'affichage.
 * Les identifiants sont enregistrés dans la base : ne jamais les renommer.
 * Les conditions sont dans CarnetRules.kt, les textes dans CarnetTextes.kt.
 */
object CarnetBadges {

    // Difficulté de chaque succès (tous les autres sont FACILE). Déclarée AVANT la liste qui l'utilise.
    private val MOYENS = setOf(
        "premiere_morille", "especes_10", "russules_5", "pied_bleu",
        "morilles_printemps", "pieds_mouton_hiver", "st_georges", "quatre_saisons", "cepe_ete",
        "trompettes_toussaint", "leve_tot", "pleine_lune",
        "spot_redonne", "spot_10_visites", "anniversaire_1",
        "km_100", "sortie_10km", "longue_sortie", "spots_10",
        "sous_la_pluie", "apres_gelee", "apres_deluge", "canicule", "grand_froid",
        "bredouille", "retour_apres_vide", "sorties_25", "trois_en_semaine",
        "au_pied_5", "fiche_complete", "pesees_10",
    )
    private val DIFFICILES = setOf(
        "especes_25", "especes_50", "tous_cepes", "bolets_courants", "chanterelles", "trio_limousin",
        "oronge", "truffe", "cumul_10kg", "douze_mois",
        "fidele_3", "fidele_5", "spots_productifs_10",
        "depts_5", "km_500", "spots_50", "sourcier",
        "vides_10_affilee", "vides_25", "sorties_50", "spots_3m_10", "photos_50",
    )
    private val LEGENDAIRES = setOf("especes_100", "fidele_10", "anniversaire_5", "km_1000", "sorties_100")

    /** Difficulté d'un succès d'après les listes ci-dessus. */
    private fun tierOf(id: String): BadgeTier = when (id) {
        in LEGENDAIRES -> BadgeTier.LEGENDAIRE
        in DIFFICILES -> BadgeTier.DIFFICILE
        in MOYENS -> BadgeTier.MOYEN
        else -> BadgeTier.FACILE
    }

    private fun fam(f: BadgeFamily, vararg ids: String) =
        ids.map { BadgeDef(it, f, secret = f == BadgeFamily.SECRETS, tier = tierOf(it)) }

    val ALL: List<BadgeDef> =
        fam(
            BadgeFamily.PREMIERES,
            "premiere_sortie", "premier_champignon", "premier_spot", "premiere_pesee", "premiere_photo",
            "premier_spot_vide", "premiere_sortie_plusieurs", "premier_cepe", "premiere_girolle",
            "premiere_morille", "premiere_trompette", "premier_retour",
        ) + fam(
            BadgeFamily.COLLECTION,
            "especes_5", "especes_10", "especes_25", "especes_50", "especes_100", "tous_cepes",
            "bolets_courants", "chanterelles", "trio_limousin", "russules_5", "oronge", "pied_bleu",
            "truffe", "cumul_10kg",
        ) + fam(
            BadgeFamily.SAISONS,
            "morilles_printemps", "pieds_mouton_hiver", "st_georges", "douze_mois", "quatre_saisons",
            "sortie_hiver", "cepe_ete", "trompettes_toussaint", "leve_tot", "pleine_lune",
        ) + fam(
            BadgeFamily.FIDELITE,
            "fidele_3", "fidele_5", "fidele_10", "spot_redonne", "spot_10_visites", "spots_productifs_10",
            "anniversaire_1", "anniversaire_5",
        ) + fam(
            BadgeFamily.EXPLORATEUR,
            "depts_2", "depts_5", "km_100", "km_500", "km_1000", "sortie_10km", "longue_sortie",
            "spots_10", "spots_50",
        ) + fam(
            BadgeFamily.METEO,
            "sous_la_pluie", "apres_gelee", "apres_deluge", "sourcier", "canicule", "grand_froid",
        ) + fam(
            BadgeFamily.PERSEVERANCE,
            "vides_10_affilee", "vides_25", "bredouille", "retour_apres_vide", "sorties_10", "sorties_25",
            "sorties_50", "sorties_100", "trois_en_semaine",
        ) + fam(
            BadgeFamily.PRECISION,
            "spot_3m", "spots_3m_10", "au_pied_5", "fiche_complete", "pesees_10", "photos_50",
        ) + fam(
            BadgeFamily.SECRETS,
            "secret_easter", "secret_party", "secret_champignong", "pluie_feuilles", "pluie_eau",
            "pluie_champignons",
        )

    val BY_ID: Map<String, BadgeDef> = ALL.associateBy { it.id }

    /** Nombre total de succès (affiché « 23 / 80 succès »). */
    val TOTAL: Int get() = ALL.size
}

/** Sources d'XP (détail du récap de fin de sortie et du carnet). */
enum class XpSource { SORTIE, NOUVEAU_SPOT, NOUVELLE_ESPECE, PESEE, SPOT_VIDE, PHOTO, PLUSIEURS, SPOT_REDONNE, SUCCES }

/**
 * Instantané de TOUTES les données utiles au carnet, déjà filtrées sur « moi »
 * (en sortie à plusieurs, les saisies des autres sont dans la base mais ne comptent pas).
 * C'est l'entrée unique des calculs purs (CarnetXp, CarnetRules) : mêmes données → même résultat.
 */
data class CarnetData(
    /** Instant du calcul (anniversaires). */
    val now: Long,
    /** Mes sorties hors corbeille, triées par début. */
    val sorties: List<Sortie>,
    /** Mes spots hors corbeille. */
    val spots: List<Spot>,
    /** Mes passages sur des spots (vides ou non). */
    val visits: List<SpotVisit>,
    /** Mes espèces récoltées, triées par date. */
    val harvests: List<CarnetHarvest>,
    /** Mes photos d'espèces (rattachées à une de mes espèces récoltées). */
    val speciesPhotos: List<Photo>,
    /** Identifiants de mes spots ayant au moins une photo du spot. */
    val spotsWithPhoto: Set<String>,
    /** Meilleure précision (m) mesurée pour chaque spot. */
    val bestAccuracy: Map<String, Float>,
    /** Nombre de mesures « Je suis au pied ». */
    val auPiedCount: Int,
    /** Distance parcourue (m) par sortie (ma trace). */
    val distanceM: Map<String, Double>,
    /** Météo connue, par clé « zone|AAAA-MM-JJ » (vide si Champimap Connect n'a rien importé). */
    val weather: Map<String, WeatherDay>,
    // Réglages (succès secrets)
    val easterUnlocked: Boolean,
    val psycheUnlocked: Boolean,
    val champignong: Boolean,
    val rainsSeen: Set<String>,
)

/** Un gain d'XP : source, montant, sortie concernée (null = hors sortie) et date. */
data class XpEvent(val source: XpSource, val xp: Int, val sortieId: String?, val time: Long)

/** Résultat du calcul d'XP. */
data class XpResult(val events: List<XpEvent>) {
    val total: Long = events.sumOf { it.xp.toLong() }

    /** Ajoute des gains (ex. XP des succès) en gardant l'ordre chronologique. */
    operator fun plus(more: List<XpEvent>): XpResult = if (more.isEmpty()) this else XpResult((events + more).sortedBy { it.time })

    /** XP par source, toutes sorties confondues. */
    fun bySource(): Map<XpSource, Int> = events.groupBy { it.source }.mapValues { (_, l) -> l.sumOf { it.xp } }

    /** XP gagnée pendant une sortie, par source. */
    fun ofSortie(sortieId: String): Map<XpSource, Int> =
        events.filter { it.sortieId == sortieId }.groupBy { it.source }.mapValues { (_, l) -> l.sumOf { it.xp } }

    /** XP gagnée avant un instant, hors sortie donnée (niveau « avant » une sortie, même ancienne). */
    fun before(time: Long, excludeSortie: String): Long =
        events.filter { it.time < time && it.sortieId != excludeSortie }.sumOf { it.xp.toLong() }
}

/** Niveau calculé à partir de l'XP totale. */
data class LevelInfo(
    val level: Int,
    val totalXp: Long,
    /** XP déjà gagnée dans le niveau en cours. */
    val xpInLevel: Long,
    /** XP nécessaire pour passer au niveau suivant. */
    val xpForNext: Long,
    /** Titre courant (sans étoiles). */
    val title: String,
    /** Étoiles au-delà du niveau 50 (une tous les 10 niveaux). */
    val stars: Int,
) {
    /** Avancement dans le niveau, de 0 à 1. */
    val progress: Float get() = if (xpForNext <= 0) 0f else (xpInLevel.toFloat() / xpForNext).coerceIn(0f, 1f)

    /** Titre affiché, avec ses étoiles éventuelles : « Esprit de la forêt ★★ ». */
    val titleWithStars: String get() = if (stars > 0) "$title " + "★".repeat(stars) else title
}

/** État du carnet affiché par les écrans. */
data class CarnetState(
    val level: LevelInfo,
    val xp: XpResult,
    /** Succès obtenus : identifiant → date d'obtention. */
    val unlocked: Map<String, Long>,
    /** Succès pas encore annoncés (dans l'ordre d'obtention). */
    val toAnnounce: List<String>,
)

/**
 * Fiche de niveau échangeable (prévu pour plus tard : échange en sortie à plusieurs).
 * Contient UNIQUEMENT le prénom, le niveau, le titre et les succès : AUCUN spot, aucune position.
 * Sérialisable en JSON (kotlinx.serialization) pour passer par le lien Bluetooth du module groupe.
 */
@Serializable
data class CarnetSnapshot(
    /** Version du format, pour rester lisible par les versions futures. */
    val version: Int = 1,
    val firstName: String,
    val level: Int,
    val title: String,
    val stars: Int,
    val totalXp: Long,
    /** Succès obtenus (identifiants + date), secrets compris. */
    val badges: List<SnapshotBadge>,
)

/** Un succès dans la fiche échangeable. */
@Serializable
data class SnapshotBadge(val id: String, val unlockedAt: Long)
