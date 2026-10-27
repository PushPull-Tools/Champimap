package fr.pushpull.champimap.feature.sortie

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * État en direct de la sortie en cours, partagé entre le service de suivi GPS
 * (SortieTrackingService, qui l'alimente) et les écrans (qui l'observent).
 * Vit en mémoire seulement : la trace elle-même est enregistrée dans la base.
 */

/** Une position GPS reçue (précision en mètres, vitesse en m/s si connue). */
data class GpsFix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val time: Long,
    val speedMs: Float? = null,
)

object SortieTracker {

    /** Identifiant de la sortie suivie par le service, null si aucun suivi. */
    internal val runningSortie = MutableStateFlow<String?>(null)
    val running: StateFlow<String?> = runningSortie.asStateFlow()

    /**
     * Dernière position À AFFICHER (point bleu, flèche, distances) : lissée et figée à l'arrêt
     * (voir PositionSmoother). La précision reste celle du GPS. Les calculs précis (trace,
     * mesure d'un spot) utilisent les positions brutes, pas celle-ci.
     */
    internal val lastFixFlow = MutableStateFlow<GpsFix?>(null)
    val lastFix: StateFlow<GpsFix?> = lastFixFlow.asStateFlow()

    /** Le téléphone est immobile d'après les capteurs (alimenté par le service de suivi). */
    internal val stillFlow = MutableStateFlow(false)
    val still: StateFlow<Boolean> = stillFlow.asStateFlow()

    /** Lissage de la position affichée, partagé par toutes les sources de positions. */
    private val smoother = PositionSmoother()
    /** Heure de la dernière position brute reçue (une même position arrive parfois par plusieurs écouteurs). */
    private var lastRawTime = 0L

    /** GPS mis en pause car on ne bouge plus (économie de batterie). */
    internal val gpsPausedFlow = MutableStateFlow(false)
    val gpsPaused: StateFlow<Boolean> = gpsPausedFlow.asStateFlow()

    /** Mode économie (batterie < 20 %) : un point toutes les 30 s. */
    internal val economyFlow = MutableStateFlow(false)
    val economy: StateFlow<Boolean> = economyFlow.asStateFlow()

    /** Niveau de batterie en %, null si inconnu. */
    internal val batteryFlow = MutableStateFlow<Int?>(null)
    val batteryPct: StateFlow<Int?> = batteryFlow.asStateFlow()

    /**
     * Point d'extension pour le module groupe : texte d'état de la synchro
     * (ex. « synchro OK », « synchro il y a 2 min »). Null = pas d'information.
     */
    val groupSyncStatus = MutableStateFlow<String?>(null)

    /** Demande au service de reprendre le GPS tout de suite (s'il était en pause). */
    internal val wakeRequests = MutableStateFlow(0L)
    fun wakeGps() { wakeRequests.value = System.currentTimeMillis() }

    /**
     * Reçoit une position BRUTE (service, écran « Je suis perdu », échantillonneur) et publie
     * la position lissée. Les positions plus anciennes ou déjà reçues sont ignorées.
     */
    @Synchronized
    fun publishFix(fix: GpsFix) {
        if (fix.time <= lastRawTime) return
        lastRawTime = fix.time
        lastFixFlow.value = smoother.update(fix, stillFlow.value)
    }

    /** Nouvelle sortie suivie : le lissage repart de zéro. */
    @Synchronized
    internal fun resetSmoothing() {
        smoother.reset()
    }
}
