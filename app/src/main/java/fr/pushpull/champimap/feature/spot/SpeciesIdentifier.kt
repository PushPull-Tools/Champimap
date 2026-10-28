package fr.pushpull.champimap.feature.spot

import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.db.Species
import java.io.File

/*
 * Identification d'une espèce à partir d'une photo.
 * Si un modèle est disponible (livré par Champimap Connect dans filesDir/ia/, ou ancien modèle des assets),
 * OnnxSpeciesIdentifier propose 5 espèces ; sinon NoModelIdentifier ne propose rien
 * et l'écran ouvre directement la liste (avec un lien vers Champimap Connect).
 */

/** Propose des espèces pour une photo, avec un score de confiance entre 0 et 1 (meilleur en premier). */
interface SpeciesIdentifier {
    suspend fun identify(photo: File): List<Pair<Species, Float>>
}

/** Aucun modèle : aucune proposition. */
object NoModelIdentifier : SpeciesIdentifier {
    override suspend fun identify(photo: File): List<Pair<Species, Float>> = emptyList()
}

/**
 * Identificateur utilisé par l'appli : le modèle ONNX s'il est disponible, sinon aucun.
 * Réévalué à chaque lecture : un modèle reçu de Connect est utilisé sans redémarrer l'appli.
 */
val CurrentIdentifier: SpeciesIdentifier
    get() = if (OnnxSpeciesIdentifier.isAvailable(AppGraph.appContext)) OnnxSpeciesIdentifier else NoModelIdentifier
