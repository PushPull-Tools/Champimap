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

    /** Dernière position reçue par le service (ou par un écran qui écoute le GPS). */
    internal val lastFixFlow = MutableStateFlow<GpsFix?>(null)
    val lastFix: StateFlow<GpsFix?> = lastFixFlow.asStateFlow()

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

    /** Enregistre une position obtenue hors du service (écran « Je suis perdu », échantillonneur). */
    fun publishFix(fix: GpsFix) {
        val prev = lastFixFlow.value
        if (prev == null || fix.time >= prev.time) lastFixFlow.value = fix
    }
}
