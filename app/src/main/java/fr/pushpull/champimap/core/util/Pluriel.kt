package fr.pushpull.champimap.core.util

/*
 * Accord singulier / pluriel des textes affichés, selon la règle française :
 * 0 et 1 au singulier (« 0 spot », « 1 sortie »), à partir de 2 au pluriel (« 2 sorties »).
 *
 * Exemples :
 *   pluriel(3, "sortie")                  → « 3 sorties »
 *   pluriel(1, "espèce")                  → « 1 espèce »
 *   pluriel(2, "succès", "succès")        → « 2 succès »   (pluriel invariable)
 *   pluriel(2, "hôpital", "hôpitaux")     → « 2 hôpitaux » (pluriel irrégulier)
 *   motPluriel(n, "sortie")               → « sortie » ou « sorties » (le mot seul, sans le nombre)
 */

/** Vrai si [n] demande le pluriel en français (2 et plus, ou -2 et moins). */
fun estPluriel(n: Long): Boolean = n >= 2 || n <= -2

/** Le mot seul, accordé à [n] ; [plurielForme] par défaut = singulier + « s ». */
fun motPluriel(n: Long, singulier: String, plurielForme: String = singulier + "s"): String =
    if (estPluriel(n)) plurielForme else singulier

/** Variante Int de [motPluriel]. */
fun motPluriel(n: Int, singulier: String, plurielForme: String = singulier + "s"): String =
    motPluriel(n.toLong(), singulier, plurielForme)

/** « <n> <mot accordé> », ex. « 2 sorties », « 0 spot ». */
fun pluriel(n: Long, singulier: String, plurielForme: String = singulier + "s"): String =
    "$n ${motPluriel(n, singulier, plurielForme)}"

/** Variante Int de [pluriel]. */
fun pluriel(n: Int, singulier: String, plurielForme: String = singulier + "s"): String =
    pluriel(n.toLong(), singulier, plurielForme)
