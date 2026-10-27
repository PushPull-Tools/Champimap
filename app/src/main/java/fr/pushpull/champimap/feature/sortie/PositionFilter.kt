package fr.pushpull.champimap.feature.sortie

import fr.pushpull.champimap.core.db.TrackPoint
import kotlin.math.max
import kotlin.math.sqrt

/*
 * Filtrage des positions GPS (sans Google Play Services, calculs purs, sans Android).
 *
 * Problème corrigé : immobile, le GPS « danse » de quelques mètres à chaque point (même à ±4 m),
 * ce qui dessinait des zigzags dans la trace, gonflait la distance parcourue et faisait sauter
 * le point bleu et la distance « vers le départ ».
 *
 * - TrackFilter      : décide quels points entrent dans la trace (et la distance parcourue).
 * - PositionSmoother : lisse la position AFFICHÉE (point bleu, flèche, distances), et la fige à l'arrêt.
 * - PositionMath     : moyenne pondérée 1/précision² avec rejet des aberrants (> 2 écarts-types).
 */

// -------------------------------------------------------------------------------------------------
// Paramètres (réglages fins ici uniquement)
// -------------------------------------------------------------------------------------------------

/** Trace : un point moins précis que ça (en mètres) n'entre jamais dans la trace. */
const val TRACK_MAX_ACCURACY_M = 25f

/** Trace : déplacement minimal (mètres) depuis le dernier point retenu, quelle que soit la précision. */
const val TRACK_MIN_STEP_M = 8.0

/** Trace : le déplacement doit aussi dépasser ce multiple de la précision combinée des deux points. */
const val TRACK_MIN_STEP_ACCURACY_FACTOR = 1.5

/** Trace : vitesse maximale crédible à pied (m/s, ≈ 11 km/h). Au-delà, saut à confirmer. */
const val TRACK_MAX_SPEED_MS = 3.0

/** Affichage : bruit de modèle du filtre de Kalman en marche (m²/s) — plus grand = suit plus vite, tremble plus. */
const val SMOOTH_Q_WALK = 4.0

/** Affichage : bruit de modèle quand le GPS (effet Doppler) annonce une vitesse quasi nulle (m²/s). */
const val SMOOTH_Q_SLOW = 0.1

/** Affichage : sous cette vitesse GPS (m/s), on considère qu'on avance à peine (réglage SMOOTH_Q_SLOW). */
const val SMOOTH_SLOW_SPEED_MS = 0.5

/** Affichage : un point à plus de N écarts-types de l'estimation est suspect (rejeté sauf s'il se confirme). */
const val SMOOTH_GATE_SIGMAS = 3.0

/** Affichage : en dessous de cet écart (mètres), un point n'est jamais considéré comme suspect. */
const val SMOOTH_GATE_MIN_M = 15.0

/** Affichage : nombre de points suspects consécutifs qui forcent le recalage sur la mesure. */
const val SMOOTH_GATE_CONFIRM = 2

/** Immobilité : au-delà de cette vitesse GPS (m/s), on bouge vraiment, même si l'accéléromètre dit le contraire. */
const val STILL_OVERRIDE_SPEED_MS = 1.0

/** Immobilité : durée (ms) des derniers points gardés pour calculer la position figée. */
const val FREEZE_WINDOW_MS = 10_000L

/** Immobilité : nombre maximal de points dans la moyenne de la position figée. */
const val FREEZE_MAX_SAMPLES = 120

/** Immobilité : la position figée n'est republiée que si la moyenne bouge de plus de ça (mètres) : rare et franc, pas de tremblement. */
const val FREEZE_REPUBLISH_M = 3.0

/** Moyenne robuste : rejet des points à plus de N écarts-types du centre. */
const val OUTLIER_SIGMAS = 2.0

/** Moyenne robuste : rayon minimal de rejet (mètres), pour ne pas écarter un nuage très serré. */
const val OUTLIER_MIN_RADIUS_M = 2.0

/** Moyenne robuste : écart-type radial ≈ 1,2 × distance médiane (loi de Rayleigh, insensible aux aberrants). */
private const val SIGMA_FROM_MEDIAN = 1.2

// -------------------------------------------------------------------------------------------------
// Moyenne robuste
// -------------------------------------------------------------------------------------------------

object PositionMath {

    /** Résultat d'une moyenne robuste : centre et points retenus. */
    data class RobustMean(val lat: Double, val lon: Double, val kept: List<GpsFix>)

    /**
     * Moyenne pondérée par 1/précision², en écartant les points à plus de 2 écarts-types :
     *  1. centre provisoire, écart-type estimé par la médiane (les aberrants ne le gonflent pas) ;
     *  2. premier tri, nouveau centre, écart-type pondéré recalculé sur les points sains ;
     *  3. second tri sur tous les points, centre final.
     * Moins de 3 points : simple moyenne pondérée (pas assez de points pour juger).
     */
    fun robustWeightedMean(list: List<GpsFix>): RobustMean? {
        val usable = list.filter { it.accuracyM > 0f }
        if (usable.isEmpty()) return null
        val c0 = weightedMean(usable)
        if (usable.size < 3) return RobustMean(c0.first, c0.second, usable)

        // 1) Écart-type robuste autour du centre provisoire.
        val d0 = usable.map { Geo.distanceM(c0.first, c0.second, it.lat, it.lon) }
        val sigma0 = SIGMA_FROM_MEDIAN * d0.sorted()[d0.size / 2]
        val limit0 = max(OUTLIER_MIN_RADIUS_M, OUTLIER_SIGMAS * sigma0)
        val kept1 = usable.filterIndexed { i, _ -> d0[i] <= limit0 }.ifEmpty { usable }

        // 2) Nouveau centre et écart-type pondéré (radial) sur les points sains.
        val c1 = weightedMean(kept1)
        var sw = 0.0
        var swd2 = 0.0
        for (f in kept1) {
            val w = weight(f)
            val d = Geo.distanceM(c1.first, c1.second, f.lat, f.lon)
            sw += w
            swd2 += w * d * d
        }
        val sigma1 = sqrt(swd2 / sw)

        // 3) Second tri sur TOUS les points (un point écarté à tort peut revenir), centre final.
        val limit1 = max(OUTLIER_MIN_RADIUS_M, OUTLIER_SIGMAS * sigma1)
        val kept2 = usable.filter { Geo.distanceM(c1.first, c1.second, it.lat, it.lon) <= limit1 }.ifEmpty { kept1 }
        val c2 = weightedMean(kept2)
        return RobustMean(c2.first, c2.second, kept2)
    }

    /** Poids d'un point : 1 / précision² (un point à ±4 m pèse 9 fois plus qu'un à ±12 m). */
    fun weight(f: GpsFix): Double = 1.0 / (f.accuracyM.toDouble() * f.accuracyM)

    /** Moyenne pondérée 1/précision² (latitude, longitude). */
    fun weightedMean(list: List<GpsFix>): Pair<Double, Double> {
        var sw = 0.0
        var sLat = 0.0
        var sLon = 0.0
        for (f in list) {
            val w = weight(f)
            sw += w
            sLat += w * f.lat
            sLon += w * f.lon
        }
        return (sLat / sw) to (sLon / sw)
    }
}

// -------------------------------------------------------------------------------------------------
// Filtre de la trace enregistrée
// -------------------------------------------------------------------------------------------------

/**
 * Décide si une position entre dans la trace :
 *  - précision > 25 m → rejetée ;
 *  - téléphone immobile → rejetée (le GPS continue de tourner) ;
 *  - déplacement < max(8 m, 1,5 × précision combinée) depuis le dernier point retenu → rejetée (bruit) ;
 *  - vitesse implicite > 3 m/s → saut suspect : gardé en attente, accepté seulement si le point
 *    suivant le confirme (plus proche du saut que de l'ancien point), sinon oublié.
 * La distance parcourue = somme des pas retenus.
 */
class TrackFilter {

    /** Pas retenu : points à enregistrer (le saut confirmé + le point courant) et distance ajoutée. */
    data class Step(val points: List<GpsFix>, val distanceM: Double)

    private var lastKept: GpsFix? = null
    private var pendingJump: GpsFix? = null

    /** Repart d'un dernier point connu (reprise d'une sortie déjà commencée). */
    fun reset(last: GpsFix?) {
        lastKept = last
        pendingJump = null
    }

    /** Propose une position. Renvoie le pas retenu, ou null si la position n'entre pas dans la trace. */
    fun offer(fix: GpsFix, still: Boolean): Step? {
        if (fix.accuracyM > TRACK_MAX_ACCURACY_M) return null
        if (still) { pendingJump = null; return null }

        val prev = lastKept
        if (prev == null) {
            lastKept = fix
            return Step(listOf(fix), 0.0)
        }

        // Bruit : on n'a pas bougé de façon significative.
        val d = Geo.distanceM(prev.lat, prev.lon, fix.lat, fix.lon)
        if (d < minStepM(prev, fix)) { pendingJump = null; return null }

        // Saut physiquement impossible à pied : à confirmer par le point suivant.
        val toStore = mutableListOf<GpsFix>()
        val dtS = max(1.0, (fix.time - prev.time) / 1000.0)
        if (d / dtS > TRACK_MAX_SPEED_MS) {
            val p = pendingJump
            val confirmed = p != null && p.time < fix.time && Geo.distanceM(p.lat, p.lon, fix.lat, fix.lon) < d
            if (!confirmed) { pendingJump = fix; return null }
            // Le saut est enregistré aussi : relire la trace plus tard redonne le même résultat.
            toStore += p!!
        }

        pendingJump = null
        lastKept = fix
        toStore += fix
        return Step(toStore, d)
    }

    companion object {

        /** Déplacement minimal entre deux points : max(8 m, 1,5 × racine(précision1² + précision2²)). */
        fun minStepM(a: GpsFix, b: GpsFix): Double {
            val combined = sqrt(a.accuracyM.toDouble() * a.accuracyM + b.accuracyM.toDouble() * b.accuracyM)
            return max(TRACK_MIN_STEP_M, TRACK_MIN_STEP_ACCURACY_FACTOR * combined)
        }

        /**
         * Rejoue le filtre sur une trace enregistrée (nettoie aussi les anciennes traces bruitées).
         * Sortie à plusieurs : chaque marcheur est filtré séparément (ses points ne se mélangent pas).
         */
        fun filter(points: List<TrackPoint>): List<TrackPoint> =
            points.groupBy { it.authorId }.values.flatMap { filterOne(it) }

        /**
         * Longueur (mètres) d'une trace enregistrée, calculée uniquement sur les points filtrés.
         * Sortie à plusieurs : la plus longue des traces (on marche ensemble, pas de double compte).
         */
        fun lengthM(points: List<TrackPoint>): Double =
            points.groupBy { it.authorId }.values.maxOfOrNull { lengthOne(it) } ?: 0.0

        /** Filtre la trace d'un seul marcheur (un saut en attente n'est pas redessiné). */
        private fun filterOne(points: List<TrackPoint>): List<TrackPoint> {
            val f = TrackFilter()
            val out = mutableListOf<TrackPoint>()
            for (p in points) if (f.offer(p.toFix(), still = false) != null) out += p
            return out
        }

        /** Longueur de la trace d'un seul marcheur. */
        private fun lengthOne(points: List<TrackPoint>): Double {
            val f = TrackFilter()
            var total = 0.0
            for (p in points) f.offer(p.toFix(), still = false)?.let { total += it.distanceM }
            return total
        }

        private fun TrackPoint.toFix() = GpsFix(lat, lon, accuracyM, time)
    }
}

// -------------------------------------------------------------------------------------------------
// Lissage de la position affichée
// -------------------------------------------------------------------------------------------------

/**
 * Position affichée (point bleu, flèche et distance vers le départ) :
 *  - en mouvement : filtre de Kalman simple (même variance sur les deux axes), mesure pondérée
 *    par sa précision ; bruit de modèle faible si le GPS annonce une vitesse quasi nulle ;
 *    un point très éloigné de l'estimation est ignoré, sauf s'il se répète (vrai déplacement) ;
 *  - immobile (capteurs) : position FIGÉE sur la moyenne robuste des derniers points,
 *    affinée au fil des mesures mais republiée seulement si elle bouge de plus de 3 m.
 * La précision affichée reste celle du GPS (pastille « GPS ±4 m »).
 */
class PositionSmoother {

    private var lat = 0.0
    private var lon = 0.0
    /** Variance de l'estimation (m²). */
    private var variance = -1.0
    private var lastTime = 0L
    private var suspects = 0

    /** Derniers points corrects (fenêtre de quelques secondes) et points reçus depuis l'arrêt. */
    private val recent = ArrayDeque<GpsFix>()
    private var frozen = false
    private var frozenLat = 0.0
    private var frozenLon = 0.0

    /** Oublie tout (nouvelle sortie). */
    fun reset() {
        variance = -1.0
        suspects = 0
        recent.clear()
        frozen = false
    }

    /** Intègre une position brute et renvoie la position à afficher. */
    fun update(fix: GpsFix, stillSensor: Boolean): GpsFix {
        val still = stillSensor && (fix.speedMs ?: 0f) < STILL_OVERRIDE_SPEED_MS
        keepRecent(fix, still)

        if (still) return freeze(fix)
        if (frozen) {
            // Reprise du mouvement : le filtre repart de la position figée.
            frozen = false
            lat = frozenLat
            lon = frozenLon
        }
        kalman(fix)
        return fix.copy(lat = lat, lon = lon)
    }

    /** Garde les derniers points corrects (fenêtre glissante, ou tout depuis l'arrêt, plafonné). */
    private fun keepRecent(fix: GpsFix, still: Boolean) {
        if (fix.accuracyM <= TRACK_MAX_ACCURACY_M) recent.addLast(fix)
        if (!still) while (recent.isNotEmpty() && fix.time - recent.first().time > FREEZE_WINDOW_MS) recent.removeFirst()
        while (recent.size > FREEZE_MAX_SAMPLES) recent.removeFirst()
    }

    /** Immobile : moyenne robuste des derniers points, republiée seulement si elle bouge vraiment. */
    private fun freeze(fix: GpsFix): GpsFix {
        val mean = PositionMath.robustWeightedMean(recent.toList())
        if (!frozen) {
            frozen = true
            frozenLat = mean?.lat ?: if (variance >= 0) lat else fix.lat
            frozenLon = mean?.lon ?: if (variance >= 0) lon else fix.lon
        } else if (mean != null && Geo.distanceM(frozenLat, frozenLon, mean.lat, mean.lon) > FREEZE_REPUBLISH_M) {
            frozenLat = mean.lat
            frozenLon = mean.lon
        }
        // Le filtre suit la position figée (pas de saut à la reprise).
        lat = frozenLat
        lon = frozenLon
        val r = fix.accuracyM.toDouble() * fix.accuracyM
        variance = if (variance < 0) r else minOf(variance, r)
        lastTime = fix.time
        suspects = 0
        return fix.copy(lat = frozenLat, lon = frozenLon)
    }

    /** Filtre de Kalman 1D par axe (gain commun car la variance est la même sur les deux axes). */
    private fun kalman(fix: GpsFix) {
        val r = fix.accuracyM.toDouble() * fix.accuracyM
        if (variance < 0) {
            lat = fix.lat; lon = fix.lon; variance = r; lastTime = fix.time
            return
        }
        // Prédiction : l'incertitude grandit avec le temps écoulé.
        val dtS = ((fix.time - lastTime) / 1000.0).coerceAtLeast(0.0)
        val q = if (fix.speedMs != null && fix.speedMs < SMOOTH_SLOW_SPEED_MS) SMOOTH_Q_SLOW else SMOOTH_Q_WALK
        val predicted = variance + q * dtS
        lastTime = fix.time

        // Point très éloigné de l'estimation : ignoré, sauf s'il se répète (recalage).
        val d = Geo.distanceM(lat, lon, fix.lat, fix.lon)
        if (d > max(SMOOTH_GATE_MIN_M, SMOOTH_GATE_SIGMAS * sqrt(predicted + r))) {
            suspects++
            if (suspects < SMOOTH_GATE_CONFIRM) { variance = predicted; return }
            lat = fix.lat; lon = fix.lon; variance = r; suspects = 0
            return
        }
        suspects = 0

        // Correction : gain = part de confiance donnée à la nouvelle mesure.
        val k = predicted / (predicted + r)
        lat += k * (fix.lat - lat)
        lon += k * (fix.lon - lon)
        variance = (1 - k) * predicted
    }
}
