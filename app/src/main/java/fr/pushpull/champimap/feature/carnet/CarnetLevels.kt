package fr.pushpull.champimap.feature.carnet

import kotlin.math.pow
import kotlin.math.roundToLong

/*
 * Courbe des niveaux (infinie) et titres.
 *
 * Coût pour passer du niveau L au niveau L+1 :
 *     cout(L) = arrondi( 415 + 0,008 × (L − 1)^3,4 )
 * → presque constant au début (~415–430 XP par niveau jusqu'au niveau 10), puis de plus en plus cher.
 *
 * XP totale nécessaire pour ATTEINDRE un niveau (somme des coûts) :
 *     niveau 5 ≈ 1 660   niveau 10 ≈ 3 760   niveau 20 ≈ 8 570   niveau 30 ≈ 16 600
 *     niveau 40 ≈ 33 400   niveau 50 ≈ 67 800   niveau 60 ≈ 133 000   niveau 70 ≈ 246 000
 *
 * Calibrage (barème de CarnetXp) :
 *  - cueilleur régulier, ~25 sorties/an : ~130 XP par sortie la 1re année (sortie 20, ~1 nouveau spot 30,
 *    ~600 g pesés 60, un spot vide 10, 2 photos 10) + ~12 espèces nouvelles (1 200) ≈ 4 400 XP
 *    → niveau 10–11 dans la 1re saison ; ~3 400 XP la 2e année (moins de nouveautés, spots qui redonnent)
 *    → ~7 800 XP, niveau 19–20 en 2 ans.
 *  - acharné, ~80 sorties/an, beaucoup d'espèces : ~150 XP par sortie (pesée plafonnée à 150/jour) + espèces
 *    + spots qui redonnent ≈ 13 000–15 000 XP/an → niveau 50 (67 800 XP) en ~5 ans au maximum.
 *  - au-delà du niveau 50, les niveaux continuent (51, 52…) avec le titre « Esprit de la forêt »
 *    et une étoile ★ tous les 10 niveaux (60, 70…).
 */
object CarnetLevels {

    private const val BASE = 415.0
    private const val FACTEUR = 0.008
    private const val PUISSANCE = 3.4

    /** Niveau du dernier titre ; au-delà, les étoiles. */
    const val NIVEAU_MAX_TITRE = 50

    /** Une étoile tous les N niveaux après le niveau 50. */
    const val ETOILE_TOUS_LES = 10

    /** XP pour passer du niveau [level] au suivant. */
    fun costOf(level: Int): Long = (BASE + FACTEUR * (level - 1).toDouble().pow(PUISSANCE)).roundToLong()

    /** XP totale nécessaire pour atteindre [level] (niveau 1 = 0 XP). */
    fun xpToReach(level: Int): Long {
        var total = 0L
        for (l in 1 until level) total += costOf(l)
        return total
    }

    /** Niveau correspondant à une XP totale (boucle simple : les coûts grandissent vite, peu de tours). */
    fun levelInfo(totalXp: Long): LevelInfo {
        var level = 1
        var remaining = totalXp.coerceAtLeast(0)
        while (remaining >= costOf(level)) {
            remaining -= costOf(level)
            level++
        }
        return LevelInfo(
            level = level,
            totalXp = totalXp,
            xpInLevel = remaining,
            xpForNext = costOf(level),
            title = titleOf(level).nom,
            stars = starsOf(level),
        )
    }

    /** Titre en vigueur à un niveau (le dernier titre dont le niveau est atteint). */
    fun titleOf(level: Int): TexteTitre = CarnetTextes.TITRES.last { it.niveau <= level.coerceAtLeast(1) }

    /** Prochain titre à obtenir, ou null s'ils sont tous obtenus. */
    fun nextTitle(level: Int): TexteTitre? = CarnetTextes.TITRES.firstOrNull { it.niveau > level }

    /** Étoiles au-delà du niveau 50 : ★ au niveau 60, ★★ au 70… */
    fun starsOf(level: Int): Int = if (level < NIVEAU_MAX_TITRE) 0 else (level - NIVEAU_MAX_TITRE) / ETOILE_TOUS_LES

    /** Prochain niveau qui donne une étoile (après le niveau 50). */
    fun nextStarLevel(level: Int): Int = NIVEAU_MAX_TITRE + (starsOf(level) + 1) * ETOILE_TOUS_LES
}
