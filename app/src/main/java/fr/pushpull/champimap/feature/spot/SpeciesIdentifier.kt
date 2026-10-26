package fr.pushpull.champimap.feature.spot

import fr.pushpull.champimap.core.db.Species
import java.io.File

/*
 * Identification d'une espèce à partir d'une photo.
 * Le modèle d'IA (embarqué, hors ligne) viendra plus tard : en attendant,
 * NoModelIdentifier ne propose rien et l'écran ouvre directement la liste.
 */

/** Propose des espèces pour une photo, avec un score de confiance entre 0 et 1 (meilleur en premier). */
interface SpeciesIdentifier {
    suspend fun identify(photo: File): List<Pair<Species, Float>>
}

/** Implémentation provisoire : aucun modèle, aucune proposition. */
object NoModelIdentifier : SpeciesIdentifier {
    override suspend fun identify(photo: File): List<Pair<Species, Float>> = emptyList()
}

/** Identificateur utilisé par l'appli (à remplacer quand le modèle sera prêt). */
val CurrentIdentifier: SpeciesIdentifier = NoModelIdentifier
