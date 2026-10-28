package fr.pushpull.champimap.feature.spot

import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.db.Species
import java.io.File

/*
 * Identification d'une espèce à partir d'une photo.
 * Si le modèle embarqué est livré dans les assets (assets/ia/, ajouté par la CI),
 * OnnxSpeciesIdentifier propose 5 espèces ; sinon NoModelIdentifier ne propose rien
 * et l'écran ouvre directement la liste.
 */

/** Propose des espèces pour une photo, avec un score de confiance entre 0 et 1 (meilleur en premier). */
interface SpeciesIdentifier {
    suspend fun identify(photo: File): List<Pair<Species, Float>>
}

/** Aucun modèle : aucune proposition. */
object NoModelIdentifier : SpeciesIdentifier {
    override suspend fun identify(photo: File): List<Pair<Species, Float>> = emptyList()
}

/** Identificateur utilisé par l'appli : le modèle ONNX s'il est présent dans l'APK, sinon aucun. */
val CurrentIdentifier: SpeciesIdentifier by lazy {
    if (OnnxSpeciesIdentifier.isAvailable(AppGraph.appContext)) OnnxSpeciesIdentifier else NoModelIdentifier
}
