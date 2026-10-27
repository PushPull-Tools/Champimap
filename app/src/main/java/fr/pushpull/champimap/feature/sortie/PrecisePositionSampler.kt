package fr.pushpull.champimap.feature.sortie

import android.content.Context
import android.location.LocationListener
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max
import kotlin.math.sqrt

/*
 * Mesure de position PRÉCISE (réutilisable par le module spot : « Je suis au pied », etc.).
 *
 * Principe : on reste immobile 30 à 60 s ; chaque position GPS reçue (1 par seconde) est pondérée
 * par l'inverse du carré de sa précision (une position à ±4 m compte 9 fois plus qu'une à ±12 m).
 * Les valeurs aberrantes (à plus de 2 écarts-types, sauts du GPS) sont rejetées, puis la moyenne est recalculée.
 * La précision estimée baisse au fil du temps : c'est la jauge « ±12 m → ±3 m ».
 * La validation est toujours MANUELLE (l'appelant décide quand garder le résultat).
 *
 * Utilisation directe :
 *   val sampler = PrecisePositionSampler(context)
 *   sampler.start(); sampler.state.collect { … }; sampler.stop()
 * Utilisation en Compose (recommandée) : `PrecisePositionDialog(onValidate = { pos -> … }, onDismiss = { … })`
 * (voir PrecisePositionDialog.kt), ou `rememberPrecisePositionSampler()`.
 */

/** Résultat d'une mesure précise. */
data class PrecisePosition(
    val lat: Double,
    val lon: Double,
    /** Précision estimée de la moyenne, en mètres. */
    val accuracyM: Float,
    /** Nombre de positions retenues dans la moyenne. */
    val samples: Int,
    val durationMs: Long,
)

/** État en direct d'une mesure. */
data class SamplerState(
    val running: Boolean = false,
    val elapsedMs: Long = 0,
    /** Positions retenues / rejetées. */
    val kept: Int = 0,
    val rejected: Int = 0,
    /** Précision de la première position reçue (début de la jauge). */
    val firstAccuracyM: Float? = null,
    /** Précision estimée de la moyenne actuelle (fin de la jauge). */
    val currentAccuracyM: Float? = null,
    /** Meilleure position calculée jusqu'ici (null tant qu'aucune position exploitable). */
    val result: PrecisePosition? = null,
    /** L'utilisateur semble se déplacer : la mesure sera faussée. */
    val moving: Boolean = false,
) {
    /** Durée minimale atteinte : la mesure est conseillée. */
    val ready: Boolean get() = result != null && elapsedMs >= PrecisePositionSampler.MIN_DURATION_MS

    /** Durée maximale atteinte : la mesure est arrêtée (le résultat reste disponible). */
    val finished: Boolean get() = elapsedMs >= PrecisePositionSampler.MAX_DURATION_MS

    /** Avancement 0..1 sur la durée maximale. */
    val progress: Float get() = (elapsedMs.toFloat() / PrecisePositionSampler.MAX_DURATION_MS).coerceIn(0f, 1f)
}

class PrecisePositionSampler(context: Context) {

    companion object {
        const val MIN_DURATION_MS = 30_000L
        const val MAX_DURATION_MS = 60_000L
        private const val GPS_INTERVAL_MS = 1000L
        /** Au-delà, une position est trop mauvaise pour entrer dans la moyenne. */
        private const val MAX_ACCEPTED_ACCURACY_M = 50f
        /** Durée pendant laquelle les erreurs du GPS restent liées entre elles (positions non indépendantes). */
        private const val CORRELATION_S = 5.0

        /**
         * Calcul pur (sans Android) : moyenne pondérée robuste d'une série de positions.
         * Renvoie le résultat et le nombre de positions rejetées, ou null si rien d'exploitable.
         */
        fun compute(fixes: List<GpsFix>): Pair<PrecisePosition, Int>? {
            val usable = fixes.filter { it.accuracyM in 0.5f..MAX_ACCEPTED_ACCURACY_M }
            if (usable.isEmpty()) return null

            // 1–2) Moyenne pondérée 1/précision², points à plus de 2 écarts-types écartés
            //      AVANT la moyenne finale (voir PositionMath.robustWeightedMean).
            val robust = PositionMath.robustWeightedMean(usable) ?: return null
            val kept = robust.kept
            val lat = robust.lat
            val lon = robust.lon

            // 3) Précision estimée : dispersion + précision moyenne, réduites par le nombre de mesures
            //    réellement indépendantes (≈ une toutes les 5 s), avec 1 m d'erreur résiduelle minimum.
            var sumW = 0.0
            var sumWd2 = 0.0
            for (f in kept) {
                val w = 1.0 / (f.accuracyM * f.accuracyM)
                val d = Geo.distanceM(lat, lon, f.lat, f.lon)
                sumW += w
                sumWd2 += w * d * d
            }
            val spread2 = sumWd2 / sumW
            val typicalAcc2 = kept.size / sumW
            val spanS = (kept.maxOf { it.time } - kept.minOf { it.time }) / 1000.0
            val nEff = max(1.0, spanS / CORRELATION_S)
            val est = max(1.5, sqrt(spread2 / nEff + typicalAcc2 / nEff + 1.0))

            val pos = PrecisePosition(
                lat = lat,
                lon = lon,
                accuracyM = est.toFloat(),
                samples = kept.size,
                durationMs = (spanS * 1000).toLong(),
            )
            return pos to (fixes.size - kept.size)
        }
    }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val fixes = mutableListOf<GpsFix>()
    private var listener: LocationListener? = null
    private var startedAt = 0L

    private val _state = MutableStateFlow(SamplerState())
    /** État en direct (jauge, résultat, avertissement de mouvement). */
    val state: StateFlow<SamplerState> = _state.asStateFlow()

    /** Horloge : met à jour la durée et arrête le GPS à 60 s. */
    private val tick = object : Runnable {
        override fun run() {
            val elapsed = System.currentTimeMillis() - startedAt
            _state.value = _state.value.copy(elapsedMs = elapsed)
            if (elapsed >= MAX_DURATION_MS) stopGps() else handler.postDelayed(this, 500)
        }
    }

    /**
     * Démarre (ou redémarre à zéro) une mesure. Renvoie false si le GPS n'est pas disponible
     * (permission refusée) : l'appelant affiche alors un message.
     */
    fun start(): Boolean {
        stop()
        fixes.clear()
        startedAt = System.currentTimeMillis()
        _state.value = SamplerState(running = true)
        SortieTracker.wakeGps()
        listener = LocationUtils.start(appContext, GPS_INTERVAL_MS) { onFix(it) }
        if (listener == null) {
            _state.value = SamplerState(running = false)
            return false
        }
        handler.post(tick)
        return true
    }

    /** Arrête la mesure (le dernier résultat reste lisible dans `state`). */
    fun stop() {
        stopGps()
    }

    private fun stopGps() {
        handler.removeCallbacks(tick)
        LocationUtils.stop(appContext, listener)
        listener = null
        _state.value = _state.value.copy(running = false)
    }

    /** Nouvelle position : on recalcule la moyenne et la jauge. */
    private fun onFix(fix: GpsFix) {
        SortieTracker.publishFix(fix)
        // On ignore les positions « en cache » antérieures au début de la mesure.
        if (fix.time < startedAt - 2000) return
        fixes += fix
        val computed = compute(fixes)
        val current = _state.value
        val result = computed?.first
        // Mouvement : vitesse franche, ou position actuelle loin de la moyenne.
        val moving = (fix.speedMs ?: 0f) > 1.0f ||
            (result != null && fixes.size >= 5 &&
                Geo.distanceM(result.lat, result.lon, fix.lat, fix.lon) > max(15.0, 2.0 * fix.accuracyM))
        _state.value = current.copy(
            kept = result?.samples ?: 0,
            rejected = computed?.second ?: fixes.size,
            firstAccuracyM = current.firstAccuracyM ?: fix.accuracyM,
            currentAccuracyM = result?.accuracyM,
            result = result,
            moving = moving,
        )
    }
}
