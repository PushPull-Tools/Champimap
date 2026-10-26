package fr.pushpull.champimap.nav

/**
 * Toutes les routes de navigation de l'appli.
 * Les fonctions `xxx(id)` construisent une route avec ses arguments.
 */
object Routes {
    // Premier lancement
    const val ONBOARDING = "onboarding"

    // Onglets du menu du bas
    const val HOME = "home"
    const val CARTE = "carte"
    const val STATS = "stats"
    const val CATALOGUE = "especes"
    const val PARAMETRES = "parametres"

    // Sorties
    const val DEPART = "depart"
    const val SORTIE = "sortie/{sortieId}"
    fun sortie(id: String) = "sortie/$id"
    const val FIN = "fin/{sortieId}"
    fun fin(id: String) = "fin/$id"
    const val PERDU = "perdu/{sortieId}"
    fun perdu(id: String) = "perdu/$id"

    // Spots, récoltes, espèces
    const val SPOT = "spot/{spotId}?sortieId={sortieId}"
    fun spot(id: String, sortieId: String? = null) = if (sortieId != null) "spot/$id?sortieId=$sortieId" else "spot/$id"
    const val ESPECES_PICKER = "especes/choisir/{recolteId}"
    fun especesPicker(recolteId: String) = "especes/choisir/$recolteId"
    const val FICHE_ESPECE = "espece/{speciesId}"
    fun ficheEspece(id: String) = "espece/$id"

    // Statistiques
    const val CALENDRIER = "calendrier"
    const val TRESORS = "tresors"

    // Sécurité
    const val INTOX = "intox?sortieId={sortieId}"
    fun intox(sortieId: String? = null) = if (sortieId != null) "intox?sortieId=$sortieId" else "intox"
    const val INTOX_LIST = "intox_list"

    // Réglages
    const val PREUVES = "preuves"
    const val JOURNAL = "journal"
    const val SAUVEGARDE = "sauvegarde"
    const val CORBEILLE = "corbeille"
    const val CONNECT = "connect"

    // Partage et groupe
    const val GROUPE_CREER = "groupe/creer/{sortieId}"
    fun groupeCreer(sortieId: String) = "groupe/creer/$sortieId"
    const val GROUPE_REJOINDRE = "groupe/rejoindre"
    const val PARTAGE_SPOT = "partage/spot/{spotId}"
    fun partageSpot(spotId: String) = "partage/spot/$spotId"
    const val IMPORT_PARTAGE = "import"

    // Easter egg
    const val AVATAR = "avatar"

    /** Routes qui affichent le menu du bas. */
    val TOP_LEVEL = setOf(HOME, CARTE, STATS, CATALOGUE, PARAMETRES)
}
