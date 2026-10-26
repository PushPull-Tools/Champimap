package fr.pushpull.champimap.feature.bonus

import fr.pushpull.champimap.core.AppGraph
import kotlinx.coroutines.flow.first

/*
 * Messages de départ affichés sur l'accueil (carte verte).
 * Pioche « sac de billes » : chaque message sort une fois avant de remélanger,
 * et jamais deux fois de suite le même, même au moment du remélange.
 * L'état (sac restant + dernier message) est gardé dans les réglages (DataStore).
 */
object StartMessages {

    /** Les messages, dans l'ordre (les indices sont enregistrés dans les réglages). */
    val ALL: List<String> = listOf(
        "Je te souhaite une bonne cueillette !",
        "Les champignons n'attendent que toi !",
        "Est-ce que ta danse de la pluie a fonctionné ?",
        "Tu me gardes une place à ta table ?",
        "Apparemment Toad serait dans les parages !",
        "Paniers prêts, couteau affûté : c'est parti !",
        "Les cèpes se cachent, mais pas de toi.",
        "Ouvre l'œil, et le bon !",
        "Aujourd'hui, c'est toi le chasseur de chapeaux.",
        "Silence… on entend presque pousser les girolles.",
        "Pense à l'omelette du retour.",
        "Que la mousse soit avec toi.",
        "Regarde bien au pied des châtaigniers…",
        "Les trompettes sonnent le rassemblement !",
        "Mission du jour : remplir le panier, garder le secret.",
    )

    /** Pioche le message suivant et enregistre le nouvel état de la pioche. */
    suspend fun next(): String {
        val repo = AppGraph.settings
        val s = repo.flow.first()
        val last = s.lastStartMessage

        // Sac restant (on ignore les indices invalides si la liste a changé entre deux versions).
        var bag = s.startMessageBag.split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in ALL.indices }
            .toMutableList()

        // Sac vide : on remélange tous les messages, sans recommencer par le dernier affiché.
        if (bag.isEmpty()) bag = freshBag(last)

        val pick = bag.removeAt(0)
        repo.setStartMessageBag(bag.joinToString(","), pick)
        return ALL[pick]
    }

    /** Nouveau sac mélangé dont le premier élément n'est jamais [last]. */
    private fun freshBag(last: Int): MutableList<Int> {
        val bag = ALL.indices.shuffled().toMutableList()
        if (bag.size > 1 && bag[0] == last) {
            // On échange le premier avec un autre élément au hasard (hors position 0).
            val j = (1 until bag.size).random()
            bag[0] = bag[j].also { bag[j] = bag[0] }
        }
        return bag
    }
}
