package fr.pushpull.champimap.feature.carnet

import fr.pushpull.champimap.core.settings.MessageTone

/*
 * TOUS les textes du carnet (niveaux et succès), au même endroit, pour être relus et peaufinés.
 * Rangement : interface → familles → titres → passages de niveau → sources d'XP → succès par famille.
 * Chaque message existe en deux tons : Normal et Sarcastique (taquin, mais toujours bon esprit).
 * Règles : jamais le mot interdit des records (écrire « Nouveau record ») ; pas de « $ » dans les textes.
 * Le document de relecture docs/carnet-textes.md reprend ces mêmes textes sous forme de tableau.
 * Les identifiants (ex. "premier_spot") ne doivent PAS être modifiés : ils sont enregistrés dans la base.
 */

/** Un message dans les deux tons. */
data class Ton2(val normal: String, val sarcastique: String) {
    /** Le message dans le ton choisi dans les réglages. */
    fun pour(tone: MessageTone): String = if (tone == MessageTone.SARCASTIQUE) sarcastique else normal
}

/** Textes d'un succès : nom, condition d'obtention (affichée), message dans les deux tons. */
data class TexteSucces(val nom: String, val condition: String, val message: Ton2)

/** Un titre : niveau à partir duquel il s'applique, nom, message d'obtention. */
data class TexteTitre(val niveau: Int, val nom: String, val message: Ton2)

/** Nom et courte description d'une famille de succès. */
data class TexteFamille(val nom: String, val description: String)

/** Nom affiché d'une source d'XP et sa règle (affichée dans le carnet). */
data class TexteSource(val nom: String, val regle: String)

object CarnetTextes {

    // =========================================================================== Interface
    /** Titre de l'écran du carnet. */
    const val ECRAN_TITRE = "Mon carnet"
    /** Lien dans Paramètres › Profil. */
    const val LIEN_PARAMETRES_TITRE = "Mon carnet"
    /** Explication du lien. */
    const val LIEN_PARAMETRES_EXPLICATION = "Ton niveau, ton titre et tes succès."
    /** Réglage dans Paramètres › Affichage. */
    const val REGLAGE_TON_TITRE = "Ton des messages"
    /** Explication du réglage. */
    const val REGLAGE_TON_EXPLICATION = "Messages du carnet (succès, niveaux). Sarcastique : taquin, mais toujours bon esprit."
    /** Choix du réglage. */
    const val TON_NORMAL = "Normal"
    /** Choix du réglage. */
    const val TON_SARCASTIQUE = "Sarcastique"
    /** « Niveau 7 ». */
    const val NIVEAU = "Niveau"
    /** En-tête du carnet. */
    const val XP_TOTALE = "XP totale"
    /** « 120 / 400 XP vers le niveau 8 ». */
    const val VERS_NIVEAU = "vers le niveau"
    /** « Prochain titre : Œil de lynx (niveau 24) ». */
    const val PROCHAIN_TITRE = "Prochain titre"
    /** Après le niveau 50. */
    const val TOUS_TITRES = "Tous les titres sont à toi. Prochaine étoile au niveau"
    /** « 23 / 80 succès ». */
    const val MOT_SUCCES = "succès"
    /** « Secrets · 2 découvert(s) ». */
    const val SECRETS_DECOUVERTS = "découvert(s)"
    /** Bloc du carnet. */
    const val ORIGINE_XP = "D'où vient ton XP"
    /** Fenêtre d'un succès. */
    const val OBTENU_LE = "Obtenu le"
    /** Fenêtre d'un succès. */
    const val PAS_ENCORE = "Pas encore obtenu."
    /** Pastille sur un succès récent. */
    const val NOUVEAU = "Nouveau"
    /** Bloc de fin de sortie. */
    const val FIN_TITRE = "Carnet"
    /** Fin de sortie sans XP. */
    const val FIN_AUCUNE_XP = "Pas d'XP pour cette sortie… pour l'instant. Complète tes spots !"
    /** « Passage au niveau 8 ! ». */
    const val FIN_NIVEAU_ATTEINT = "Passage au niveau"
    /** « Nouveau titre : Panier percé ». */
    const val FIN_NOUVEAU_TITRE = "Nouveau titre"
    /** Carte animée de fin de sortie. */
    const val NOUVEAU_SUCCES = "Nouveau succès !"
    /** Bouton de la carte. */
    const val SUIVANT = "Suivant"
    /** Bouton du dernier succès annoncé. */
    const val TERMINER = "Super !"
    /** Bouton de la carte. */
    const val TOUT_VOIR = "Tout voir dans mon carnet"
    /** Fermer la fenêtre d'un succès. */
    const val OK = "OK"

    // =========================================================================== Familles
    val FAMILLES: Map<BadgeFamily, TexteFamille> = mapOf(
        BadgeFamily.PREMIERES to TexteFamille("Premières fois", "Les tout premiers pas du cueilleur."),
        BadgeFamily.COLLECTION to TexteFamille("Collection", "Les espèces trouvées au fil des sorties."),
        BadgeFamily.SAISONS to TexteFamille("Saisons", "La forêt à chaque moment de l'année."),
        BadgeFamily.FIDELITE to TexteFamille("Fidélité", "Les coins qui redonnent, année après année."),
        BadgeFamily.EXPLORATEUR to TexteFamille("Explorateur", "Des kilomètres, des départements, des spots."),
        BadgeFamily.METEO to TexteFamille("Météo", "Sortir par tous les temps (météo de Champimap Connect)."),
        BadgeFamily.PERSEVERANCE to TexteFamille("Persévérance", "La régularité, même quand le panier reste vide."),
        BadgeFamily.PRECISION to TexteFamille("Précision", "Des positions justes et des fiches bien tenues."),
        BadgeFamily.SECRETS to TexteFamille("Secrets", "Cachés jusqu'à leur découverte."),
    )

    // =========================================================================== Titres
    /** Titres par niveau (tous les 3–4 niveaux), triés. Le dernier (niveau 50) se garde ensuite, avec des étoiles. */
    val TITRES: List<TexteTitre> = listOf(
        TexteTitre(
            1, "Promeneur du dimanche",
            Ton2(
                "Bienvenue ! Chaque grand cueilleur a commencé par une balade du dimanche.",
                "Promeneur du dimanche. Même en semaine.",
            ),
        ),
        TexteTitre(
            4, "Ramasseur de mousse",
            Ton2(
                "Te voilà Ramasseur de mousse : tu regardes enfin où tu mets les pieds !",
                "Ramasseur de mousse. C'est vert, c'est doux, ce n'est pas un champignon, mais c'est un début.",
            ),
        ),
        TexteTitre(
            7, "Apprenti cueilleur",
            Ton2(
                "Apprenti cueilleur ! Ton panier commence à se remplir pour de bon.",
                "Apprenti cueilleur. « Apprenti », pour te rappeler de faire vérifier avant de manger.",
            ),
        ),
        TexteTitre(
            10, "Panier percé",
            Ton2(
                "Panier percé ! Tu en ramasses tellement qu'il en tombe en route.",
                "Panier percé. On ne sait pas si c'est pour l'abondance ou pour tout ce que tu sèmes en chemin.",
            ),
        ),
        TexteTitre(
            14, "Chercheur de chapeaux",
            Ton2(
                "Chercheur de chapeaux : ton œil repère les champignons de plus en plus loin.",
                "Chercheur de chapeaux. Tu scrutes le sol même sur le parking du supermarché.",
            ),
        ),
        TexteTitre(
            17, "Fin limier des sous-bois",
            Ton2(
                "Fin limier des sous-bois : rien ne t'échappe !",
                "Fin limier des sous-bois. Ton chien est un peu vexé.",
            ),
        ),
        TexteTitre(
            21, "Ami des châtaigniers",
            Ton2(
                "Ami des châtaigniers : les arbres te reconnaissent.",
                "Ami des châtaigniers. Ils ne te répondent pas, mais tu leur parles quand même.",
            ),
        ),
        TexteTitre(
            24, "Œil de lynx",
            Ton2(
                "Œil de lynx ! Même les trompettes ne se cachent plus de toi.",
                "Œil de lynx. Dommage que ça ne marche pas pour retrouver tes clés.",
            ),
        ),
        TexteTitre(
            28, "Gardien des coins secrets",
            Ton2(
                "Gardien des coins secrets : tes spots sont entre de bonnes mains.",
                "Gardien des coins secrets. Même sous la torture, tu donnerais une fausse adresse.",
            ),
        ),
        TexteTitre(
            31, "Maître des cèpes",
            Ton2(
                "Maître des cèpes ! Les rois de la forêt te saluent.",
                "Maître des cèpes. Tes voisins t'apportent des œufs en espérant une omelette.",
            ),
        ),
        TexteTitre(
            35, "Vieux briscard",
            Ton2(
                "Vieux briscard : l'expérience parle pour toi.",
                "Vieux briscard. Tu dis « de mon temps, on en remplissait des paniers entiers » : c'est officiel.",
            ),
        ),
        TexteTitre(
            38, "Sorcier des sous-bois",
            Ton2(
                "Sorcier des sous-bois : tu sens les champignons avant de les voir.",
                "Sorcier des sous-bois. Ta danse de la pluie est désormais certifiée.",
            ),
        ),
        TexteTitre(
            42, "Mémoire de la forêt",
            Ton2(
                "Mémoire de la forêt : chaque coin a son histoire dans ton carnet.",
                "Mémoire de la forêt. Tu te souviens d'un cèpe de 2019, mais pas de l'anniversaire de ta sœur.",
            ),
        ),
        TexteTitre(
            46, "Légende du Limousin",
            Ton2(
                "Légende du Limousin ! On parle de toi jusqu'au plateau de Millevaches.",
                "Légende du Limousin. Les anciens du village commencent à se méfier.",
            ),
        ),
        TexteTitre(
            50, "Esprit de la forêt",
            Ton2(
                "Esprit de la forêt. Tu fais partie des bois, maintenant. Quel parcours, bravo !",
                "Esprit de la forêt. Les arbres te demandent conseil. Tu pourrais t'arrêter là… mais on sait que tu ne le feras pas.",
            ),
        ),
    )

    // =========================================================================== Passages de niveau
    /** Messages de passage de niveau ; « {niveau} » est remplacé par le numéro. Choisis tour à tour selon le niveau. */
    val PASSAGE_NIVEAU: List<Ton2> = listOf(
        Ton2("Niveau {niveau} ! Continue comme ça.", "Niveau {niveau}. Tu montes, tu montes… les champignons, eux, restent par terre."),
        Ton2("Niveau {niveau} atteint : la forêt te va bien !", "Niveau {niveau}. Pas mal pour quelqu'un qui confondait un cèpe et une brioche."),
        Ton2("Et de {niveau} ! Ton carnet s'étoffe.", "Niveau {niveau}. On commence à te prendre au sérieux. Un peu."),
    )

    /** Message d'une nouvelle étoile (tous les 10 niveaux après le niveau 50). */
    val ETOILE = Ton2("Une nouvelle étoile ! Ton esprit de la forêt brille encore plus.", "Encore une étoile. Il va falloir un plus grand chapeau.")

    // =========================================================================== Sources d'XP
    val SOURCES: Map<XpSource, TexteSource> = mapOf(
        XpSource.SORTIE to TexteSource("Sortie terminée", "20 XP par sortie terminée."),
        XpSource.NOUVEAU_SPOT to TexteSource("Nouveau spot", "30 XP par spot noté."),
        XpSource.NOUVELLE_ESPECE to TexteSource("Espèce jamais trouvée", "100 XP la première fois que tu trouves une espèce."),
        XpSource.PESEE to TexteSource("Récolte pesée", "10 XP par tranche de 100 g pesés, 150 XP au plus par jour."),
        XpSource.SPOT_VIDE to TexteSource("Spot vide noté", "10 XP par spot vide noté (une fois par spot et par jour)."),
        XpSource.PHOTO to TexteSource("Photo d'espèce", "5 XP par espèce photographiée, 6 photos au plus par jour."),
        XpSource.PLUSIEURS to TexteSource("Sortie à plusieurs", "25 XP de plus pour une sortie à plusieurs."),
        XpSource.SPOT_REDONNE to TexteSource("Spot qui redonne", "50 XP quand un spot redonne une nouvelle année."),
    )

    // =========================================================================== Succès
    // ----------------------------------------------------------------- Premières fois
    private val PREMIERES: Map<String, TexteSucces> = mapOf(
        "premiere_sortie" to TexteSucces(
            "Premiers pas",
            "Terminer ta première sortie.",
            Ton2(
                "Ta première sortie est dans le carnet. Bienvenue dans la grande famille des cueilleurs !",
                "Première sortie terminée. Tu es rentré, c'est déjà un bon début.",
            ),
        ),
        "premier_champignon" to TexteSucces(
            "Premier champignon",
            "Noter ta première récolte.",
            Ton2(
                "Bravo ! Tu as trouvé ton premier champignon ! Le début d'une longue aventure.",
                "Un champignon. Un seul. Mais c'est le tien, et on est fiers de toi (un peu).",
            ),
        ),
        "premier_spot" to TexteSucces(
            "Premier coin",
            "Noter ton premier spot.",
            Ton2(
                "Ton premier spot, sûrement le plus important !",
                "Premier spot noté. Maintenant, tu vas le surveiller comme un vieux trésor.",
            ),
        ),
        "premiere_pesee" to TexteSucces(
            "La balance ne ment pas",
            "Peser une récolte pour la première fois.",
            Ton2(
                "Première pesée ! Maintenant, tu sais exactement ce que tu ramènes.",
                "Première pesée. Fini les « au moins un kilo » au repas de famille.",
            ),
        ),
        "premiere_photo" to TexteSucces(
            "Clic-clac",
            "Prendre ta première photo d'espèce.",
            Ton2(
                "Première photo d'espèce : un beau souvenir, et une aide précieuse pour bien identifier.",
                "Une photo de champignon. C’est les tiens ?",
            ),
        ),
        "premier_spot_vide" to TexteSucces(
            "Rien ici",
            "Noter ton premier « Spot vide ».",
            Ton2(
                "Noter un spot vide, c'est aussi ça, bien suivre ses coins. Bravo !",
                "Premier spot vide. Bienvenue dans la vraie vie du cueilleur.",
            ),
        ),
        "premiere_sortie_plusieurs" to TexteSucces(
            "En bonne compagnie",
            "Faire ta première sortie à plusieurs.",
            Ton2(
                "Première balade entre amis, un bon moment pour les perdre !",
                "Première sortie à plusieurs. Rappel : tes amis ne sont pas obligés de connaître TOUS tes coins.",
            ),
        ),
        "premier_cepe" to TexteSucces(
            "Le roi de la forêt",
            "Trouver ton premier cèpe.",
            Ton2(
                "Ton premier cèpe ! Le roi de la forêt t'a enfin fait l'honneur.",
                "Un cèpe. Tu vas en parler pendant des semaines, on le sent.",
            ),
        ),
        "premiere_girolle" to TexteSucces(
            "Toute dorée",
            "Trouver ta première girolle.",
            Ton2(
                "Première girolle ! Une petite pépite dorée dans ton panier.",
                "Une girolle. Une. Les autres se cachent, mais tu tiens une piste.",
            ),
        ),
        "premiere_morille" to TexteSucces(
            "Trésor du printemps",
            "Trouver ta première morille.",
            Ton2(
                "Ta première morille ! Le printemps te sourit.",
                "Une morille. Tu l'as regardée dix minutes avant d'y croire, avoue.",
            ),
        ),
        "premiere_trompette" to TexteSucces(
            "Ça sonne bien",
            "Trouver ta première trompette de la mort.",
            Ton2(
                "Première trompette de la mort ! Pas facile à voir : bel œil.",
                "En voilà une belle trompette !",
            ),
        ),
        "premier_retour" to TexteSucces(
            "Le retour",
            "Revenir sur un spot déjà noté, lors d'une autre sortie.",
            Ton2(
                "Tu es revenu sur un de tes coins : c'est comme ça qu'on apprend à les connaître.",
                "Retour sur un spot connu. C’est le début d’une routine ça !",
            ),
        ),
    )

    // ----------------------------------------------------------------- Collection
    private val COLLECTION: Map<String, TexteSucces> = mapOf(
        "especes_5" to TexteSucces(
            "Petit herbier",
            "Trouver 5 espèces différentes.",
            Ton2(
                "5 espèces différentes ! Ta collection prend forme.",
                "5 espèces. Pas encore une encyclopédie, mais une jolie brochure.",
            ),
        ),
        "especes_10" to TexteSucces(
            "Curieux de nature",
            "Trouver 10 espèces différentes.",
            Ton2(
                "10 espèces dans ton carnet : tu deviens un vrai curieux de nature.",
                "10 espèces. Tu commences à corriger les autres, hein ?",
            ),
        ),
        "especes_25" to TexteSucces(
            "Mycologue en herbe",
            "Trouver 25 espèces différentes.",
            Ton2(
                "25 espèces ! Les sociétés mycologiques n'ont qu'à bien se tenir.",
                "25 espèces. Tes amis décrochent dès que tu parles de lames et de pores.",
            ),
        ),
        "especes_50" to TexteSucces(
            "Encyclopédie ambulante",
            "Trouver 50 espèces différentes.",
            Ton2(
                "50 espèces différentes : un savoir rare, bravo !",
                "50 espèces. À ce stade, tu parles latin aux champignons.",
            ),
        ),
        "especes_100" to TexteSucces(
            "Grand mycologue",
            "Trouver 100 espèces différentes.",
            Ton2(
                "100 espèces ! Un parcours exceptionnel : la forêt n'a presque plus de secrets pour toi.",
                "100 espèces. On ne sait plus si c'est une passion ou une obsession. Les deux, sûrement.",
            ),
        ),
        "tous_cepes" to TexteSucces(
            "Carré de rois",
            "Trouver les 4 cèpes : de Bordeaux, bronzé, d'été et des pins.",
            Ton2(
                "Les 4 cèpes sont dans ton carnet. Un vrai carré de rois !",
                "Les 4 cèpes. Tu peux maintenant être insupportable en toute légitimité.",
            ),
        ),
        "bolets_courants" to TexteSucces(
            "Bolétophile",
            "Trouver les 10 bolets courants : les 4 cèpes, bolet bai, bolet à chair jaune, bolet rude, bolet orangé des bouleaux, bolet jaune et bolet à pied rouge.",
            Ton2(
                "Tous les bolets courants trouvés ! Les pores n'ont plus de secrets pour toi.",
                "10 bolets différents. Tu retournes tous les chapeaux pour regarder dessous, comme un pro.",
            ),
        ),
        "chanterelles" to TexteSucces(
            "Famille chanterelle",
            "Trouver girolle, trompette de la mort, chanterelle en tube et chanterelle jaunissante.",
            Ton2(
                "Toute la famille chanterelle est réunie dans ton panier !",
                "Toute la famille chanterelle. Il ne manque plus que la photo de groupe.",
            ),
        ),
        "trio_limousin" to TexteSucces(
            "Le tiercé gagnant",
            "Trouver cèpe, girolle et trompette de la mort pendant la même sortie.",
            Ton2(
                "Cèpe, girolle et trompette dans la même sortie : la journée parfaite !",
                "Le tiercé dans l'ordre ! Garde ça pour toi, sinon tout le village sera dans tes bois demain.",
            ),
        ),
        "russules_5" to TexteSucces(
            "Arc-en-ciel",
            "Trouver 5 russules différentes.",
            Ton2(
                "5 russules différentes : un vrai arc-en-ciel sous les arbres.",
                "5 russules. Les couleurs, ça va ; les distinguer, c'est une autre histoire.",
            ),
        ),
        "oronge" to TexteSucces(
            "L'oronge",
            "Trouver une amanite des Césars.",
            Ton2(
                "Une amanite des Césars ! Un champignon d'empereur, rien que ça.",
                "L'oronge des Césars. Tu vas exiger qu'on t'appelle « Votre Majesté » ?",
            ),
        ),
        "pied_bleu" to TexteSucces(
            "Bleu de froid",
            "Trouver un pied bleu.",
            Ton2(
                "Un pied bleu ! La fin de saison a encore de beaux restes.",
                "Un pied bleu. Pas d'inquiétude : c'est le champignon qui est bleu, pas toi.",
            ),
        ),
        "truffe" to TexteSucces(
            "Trésor souterrain",
            "Trouver une truffe.",
            Ton2(
                "Une truffe ! Un trésor caché sous la terre, bravo.",
                "Une truffe. Tu as un cochon caché quelque part, ou c'est ton flair ?",
            ),
        ),
        "cumul_10kg" to TexteSucces(
            "Dix kilos de souvenirs",
            "Atteindre 10 kg pesés au total, toutes sorties confondues.",
            Ton2(
                "10 kg pesés depuis le début : de quoi régaler beaucoup de monde !",
                "10 kg au compteur. Ton congélateur a demandé un congé.",
            ),
        ),
    )

    // ----------------------------------------------------------------- Saisons
    private val SAISONS: Map<String, TexteSucces> = mapOf(
        "morilles_printemps" to TexteSucces(
            "Printemps gourmand",
            "Trouver des morilles au printemps (mars à mai).",
            Ton2(
                "Des morilles au printemps : la saison commence en beauté !",
                "Des morilles en avril. Tu as donc passé tes week-ends à fixer des feuilles mortes. Ça a payé.",
            ),
        ),
        "pieds_mouton_hiver" to TexteSucces(
            "Mouton d'hiver",
            "Trouver des pieds-de-mouton en hiver (décembre à février).",
            Ton2(
                "Des pieds-de-mouton en plein hiver : la forêt donne toute l'année !",
                "Des pieds-de-mouton en hiver. Les autres sont au chaud, toi tu as les doigts gelés et le sourire.",
            ),
        ),
        "st_georges" to TexteSucces(
            "Fidèle à la Saint-Georges",
            "Trouver un tricholome de la Saint-Georges en avril ou en mai.",
            Ton2(
                "Le mousseron de la Saint-Georges, pile à l'heure ! Bravo.",
                "Un Saint-Georges au printemps. Même les champignons ont un agenda mieux tenu que le tien.",
            ),
        ),
        "douze_mois" to TexteSucces(
            "Toute l'année",
            "Faire au moins une sortie chaque mois de l'année (toutes années confondues).",
            Ton2(
                "Une sortie pour chacun des 12 mois : tu connais la forêt en toutes saisons !",
                "Douze mois, douze sorties au minimum. La forêt pense à te facturer un loyer.",
            ),
        ),
        "quatre_saisons" to TexteSucces(
            "Quatre saisons",
            "Récolter au moins une fois à chaque saison (toutes années confondues).",
            Ton2(
                "Une récolte à chaque saison : ton carnet fait le tour de l'année.",
                "Printemps, été, automne, hiver : tu ne lâches rien, même pas le calendrier.",
            ),
        ),
        "sortie_hiver" to TexteSucces(
            "Givré",
            "Récolter en décembre, janvier ou février.",
            Ton2(
                "Une récolte en hiver : la persévérance a du bon !",
                "Des champignons en hiver. Tu es sûr que ce ne sont pas des feuilles gelées ?",
            ),
        ),
        "cepe_ete" to TexteSucces(
            "Cèpe précoce",
            "Trouver un cèpe en juin ou en juillet.",
            Ton2(
                "Un cèpe dès l'été : tu as un temps d'avance !",
                "Un cèpe en juillet. Les autres en sont encore aux tongs.",
            ),
        ),
        "trompettes_toussaint" to TexteSucces(
            "Rendez-vous de la Toussaint",
            "Trouver des trompettes des morts autour de la Toussaint (25 octobre au 5 novembre).",
            Ton2(
                "Des trompettes à la Toussaint : la tradition est respectée !",
                "Des trompettes des morts à la Toussaint. Le sens du timing, un peu macabre mais efficace.",
            ),
        ),
        "leve_tot" to TexteSucces(
            "Lève-tôt",
            "Commencer une sortie avant 7 h du matin.",
            Ton2(
                "Une sortie avant 7 h : la forêt rien que pour toi !",
                "Une sortie avant 7 h. Les champignons dormaient encore, toi non.",
            ),
        ),
        "pleine_lune" to TexteSucces(
            "Sous la pleine lune",
            "Sortir un jour de pleine lune.",
            Ton2(
                "Une sortie un jour de pleine lune. Il paraît que ça porte chance…",
                "Une sortie à la pleine lune. On ne juge pas les croyances, on note juste.",
            ),
        ),
    )

    // ----------------------------------------------------------------- Fidélité
    private val FIDELITE: Map<String, TexteSucces> = mapOf(
        "fidele_3" to TexteSucces(
            "Fidèle au poste",
            "Récolter sur le même spot 3 années différentes.",
            Ton2(
                "Ce spot t'a fait plaisir 3 années : un vrai coin fidèle !",
                "3 ans sur le même spot. Il commence à te reconnaître.",
            ),
        ),
        "fidele_5" to TexteSucces(
            "Vieux complice",
            "Récolter sur le même spot 5 années différentes.",
            Ton2(
                "5 années sur le même spot : une belle histoire entre vous deux.",
                "5 ans au même endroit. C'est plus stable que bien des couples.",
            ),
        ),
        "fidele_10" to TexteSucces(
            "Héritage",
            "Récolter sur le même spot 10 années différentes.",
            Ton2(
                "10 années de récoltes au même coin : un spot à transmettre… ou pas !",
                "10 ans au même spot. Tu pourrais y mettre une boîte aux lettres.",
            ),
        ),
        "spot_redonne" to TexteSucces(
            "Il est revenu !",
            "Récolter sur un spot qui avait déjà donné une année précédente.",
            Ton2(
                "Ton spot a redonné une année suivante : c'est un bon coin, bravo de l'avoir noté !",
                "Le spot a redonné. Tu peux arrêter d'y penser la nuit, il est fidèle.",
            ),
        ),
        "spot_10_visites" to TexteSucces(
            "Habitué",
            "Passer sur le même spot 10 jours différents.",
            Ton2(
                "10 passages sur le même spot : tu le connais par cœur.",
                "10 passages au même endroit. Les écureuils parlent de toi comme du voisin.",
            ),
        ),
        "spots_productifs_10" to TexteSucces(
            "Réseau de coins",
            "Avoir 10 spots qui ont donné au moins une fois.",
            Ton2(
                "10 spots productifs : ta carte devient précieuse !",
                "10 bons coins. Garde ton téléphone bien verrouillé, maintenant.",
            ),
        ),
        "anniversaire_1" to TexteSucces(
            "Bougie n° 1",
            "Un an depuis ta première sortie.",
            Ton2(
                "Un an que tu notes tes sorties : joyeux anniversaire de cueilleur !",
                "Un an de carnet. Tu n'as pas abandonné au bout de trois semaines : c'est déjà exceptionnel.",
            ),
        ),
        "anniversaire_5" to TexteSucces(
            "Cinq ans de forêt",
            "Cinq ans depuis ta première sortie.",
            Ton2(
                "5 ans de sorties notées : un carnet qui vaut de l'or !",
                "5 ans. À ce stade, ce n'est plus un loisir, c'est un mode de vie.",
            ),
        ),
    )

    // ----------------------------------------------------------------- Explorateur
    private val EXPLORATEUR: Map<String, TexteSucces> = mapOf(
        "depts_2" to TexteSucces(
            "Hors des sentiers",
            "Avoir des spots dans 2 départements.",
            Ton2(
                "Des spots dans 2 départements : tu élargis ton horizon !",
                "2 départements. Tu as passé la frontière : il faudra déclarer les cèpes ?",
            ),
        ),
        "depts_5" to TexteSucces(
            "Globe-cueilleur",
            "Avoir des spots dans 5 départements.",
            Ton2(
                "5 départements explorés : un vrai voyageur des bois !",
                "5 départements. Tes champignons ont plus voyagé que certains de tes amis.",
            ),
        ),
        "km_100" to TexteSucces(
            "Cent bornes",
            "Parcourir 100 km en sortie, au total.",
            Ton2(
                "100 km parcourus en sortie : tes bottes sont bien rodées !",
                "100 km à pied. Ça use, ça use… mais ça remplit le panier.",
            ),
        ),
        "km_500" to TexteSucces(
            "Semelles de vent",
            "Parcourir 500 km en sortie, au total.",
            Ton2(
                "500 km en forêt : une sacrée distance, bravo !",
                "500 km. Tes bottes réclament une retraite anticipée.",
            ),
        ),
        "km_1000" to TexteSucces(
            "Marcheur infatigable",
            "Parcourir 1 000 km en sortie, au total.",
            Ton2(
                "1 000 km de sorties ! L'équivalent d'une traversée de la France dans les jambes.",
                "1 000 km. Tu aurais pu aller voir la mer, mais non : tu as préféré les sous-bois.",
            ),
        ),
        "sortie_10km" to TexteSucces(
            "Grande boucle",
            "Parcourir 10 km en une seule sortie.",
            Ton2(
                "10 km en une sortie : une belle balade !",
                "10 km d'un coup. Tu cherchais des champignons ou ta voiture ?",
            ),
        ),
        "longue_sortie" to TexteSucces(
            "Journée en forêt",
            "Faire une sortie de plus de 4 heures.",
            Ton2(
                "Plus de 4 heures en forêt : une vraie journée de cueilleur !",
                "4 heures dehors. Quelqu'un a pensé à prévenir ta famille ?",
            ),
        ),
        "spots_10" to TexteSucces(
            "Cartographe",
            "Noter 10 spots.",
            Ton2(
                "10 spots notés : ta carte prend forme !",
                "10 spots. Ta carte ressemble de plus en plus à une carte au trésor.",
            ),
        ),
        "spots_50" to TexteSucces(
            "Grand cartographe",
            "Noter 50 spots.",
            Ton2(
                "50 spots notés : un travail de fourmi, bravo !",
                "50 spots. Il reste des arbres sans épingle dans tes bois ?",
            ),
        ),
    )

    // ----------------------------------------------------------------- Météo
    private val METEO: Map<String, TexteSucces> = mapOf(
        "sous_la_pluie" to TexteSucces(
            "Danse de la pluie",
            "Sortir un jour de pluie (au moins 5 mm).",
            Ton2(
                "Une sortie sous la pluie : les champignons, eux, adorent ça !",
                "Une sortie sous la pluie. Les champignons aiment l'eau ; toi aussi, apparemment.",
            ),
        ),
        "apres_gelee" to TexteSucces(
            "Après la gelée",
            "Récolter le lendemain d'une gelée.",
            Ton2(
                "Une récolte après une nuit de gelée : bel exploit !",
                "Récolte après la gelée. Tes doigts s'en souviennent sûrement encore.",
            ),
        ),
        "apres_deluge" to TexteSucces(
            "Après le déluge",
            "Récolter après au moins 40 mm de pluie sur 7 jours.",
            Ton2(
                "Tu as su profiter des grosses pluies : bien vu !",
                "40 mm de pluie et toi, tu files en forêt. Tu as des branchies ?",
            ),
        ),
        "sourcier" to TexteSucces(
            "Sourcier",
            "Récolter malgré moins de 5 mm de pluie sur les 15 jours précédents.",
            Ton2(
                "Une récolte en pleine sécheresse : tu connais les bons coins humides !",
                "Des champignons en pleine sécheresse. On ne te demande pas où, promis.",
            ),
        ),
        "canicule" to TexteSucces(
            "Coup de chaud",
            "Sortir un jour à 30 °C ou plus.",
            Ton2(
                "Une sortie par plus de 30 °C : pense à bien boire !",
                "30 °C et tu vas en forêt. Les champignons, eux, sont restés à l'ombre.",
            ),
        ),
        "grand_froid" to TexteSucces(
            "Doigts gelés",
            "Sortir un jour où il ne fait pas plus de 5 °C.",
            Ton2(
                "Une sortie par grand froid : bravo pour le courage !",
                "5 °C au maximum. Le thermos de café était plus rempli que le panier ?",
            ),
        ),
    )

    // ----------------------------------------------------------------- Persévérance
    private val PERSEVERANCE: Map<String, TexteSucces> = mapOf(
        "vides_10_affilee" to TexteSucces(
            "La persévérance",
            "Noter 10 spots vides d'affilée.",
            Ton2(
                "La persévérance paie toujours… un jour.",
                "10 spots vides. Les champignons t'ont vu arriver.",
            ),
        ),
        "vides_25" to TexteSucces(
            "Collectionneur de vide",
            "Noter 25 spots vides au total.",
            Ton2(
                "25 spots vides notés : un suivi précieux de tes coins.",
                "25 spots vides. Tu tiens un inventaire très complet… du rien.",
            ),
        ),
        "bredouille" to TexteSucces(
            "Bredouille",
            "Terminer une sortie sans rien trouver (au moins un spot vide noté).",
            Ton2(
                "Rentrer bredouille fait partie du jeu. La prochaine sera la bonne !",
                "Zéro champignon. Mais tu as pris l'air : c'est ce qu'on dira.",
            ),
        ),
        "retour_apres_vide" to TexteSucces(
            "Patience récompensée",
            "Récolter sur un spot noté vide la fois précédente.",
            Ton2(
                "Ce spot était vide la dernière fois… et cette fois, il a donné. Bien joué d'y être retourné !",
                "Le spot vide a fini par donner. Comme quoi, l'entêtement, ça marche.",
            ),
        ),
        "sorties_10" to TexteSucces(
            "Habitué des bois",
            "Terminer 10 sorties.",
            Ton2(
                "10 sorties terminées : tu prends le rythme !",
                "10 sorties. La forêt commence à te garder une place sur le parking.",
            ),
        ),
        "sorties_25" to TexteSucces(
            "Pilier de la forêt",
            "Terminer 25 sorties.",
            Ton2(
                "25 sorties ! Une vraie régularité, bravo.",
                "25 sorties. Les sangliers te disent bonjour, maintenant.",
            ),
        ),
        "sorties_50" to TexteSucces(
            "Infatigable",
            "Terminer 50 sorties.",
            Ton2(
                "50 sorties dans ton carnet : quelle constance !",
                "50 sorties. On commence à se demander si tu as une maison.",
            ),
        ),
        "sorties_100" to TexteSucces(
            "Centurion des bois",
            "Terminer 100 sorties.",
            Ton2(
                "100 sorties ! Un cap magnifique.",
                "100 sorties. Tu as des actions dans une marque de bottes ?",
            ),
        ),
        "trois_en_semaine" to TexteSucces(
            "Semaine chargée",
            "Faire 3 sorties en 7 jours.",
            Ton2(
                "3 sorties en une semaine : la saison est lancée !",
                "3 sorties en une semaine. Ton agenda a dû inventer quelques « rendez-vous ».",
            ),
        ),
    )

    // ----------------------------------------------------------------- Précision
    private val PRECISION: Map<String, TexteSucces> = mapOf(
        "spot_3m" to TexteSucces(
            "Au mètre près",
            "Noter un spot avec une précision de ±3 m ou mieux.",
            Ton2(
                "Un spot à ±3 m : tu le retrouveras les yeux fermés !",
                "±3 m. Même ton GPS est impressionné.",
            ),
        ),
        "spots_3m_10" to TexteSucces(
            "Géomètre",
            "Noter 10 spots à ±3 m ou mieux.",
            Ton2(
                "10 spots ultra précis : du travail de géomètre !",
                "10 spots au mètre près. Tu as raté ta vocation de cartographe officiel.",
            ),
        ),
        "au_pied_5" to TexteSucces(
            "Pied à terre",
            "Faire 5 mesures « Je suis au pied ».",
            Ton2(
                "5 mesures au pied du spot : tes positions sont de plus en plus justes.",
                "5 fois « Je suis au pied ». Rester immobile aussi longtemps, quelle endurance.",
            ),
        ),
        "fiche_complete" to TexteSucces(
            "Fiche parfaite",
            "Remplir une fiche de spot complète : essence, note, repère et photo du spot.",
            Ton2(
                "Une fiche de spot complète : un vrai travail de pro !",
                "Fiche complète. Même ton banquier n'a pas autant d'infos sur toi.",
            ),
        ),
        "pesees_10" to TexteSucces(
            "Bon peseur",
            "Peser 10 espèces récoltées.",
            Ton2(
                "10 pesées : tes stats sont de plus en plus fiables !",
                "10 pesées. Tu emmènes la balance de cuisine en forêt, ou bien ?",
            ),
        ),
        "photos_50" to TexteSucces(
            "Photographe des sous-bois",
            "Prendre 50 photos d'espèces.",
            Ton2(
                "50 photos d'espèces : un bel album de la forêt !",
                "50 photos de champignons. Ta galerie inquiète ta famille.",
            ),
        ),
    )

    // ----------------------------------------------------------------- Secrets
    private val SECRETS: Map<String, TexteSucces> = mapOf(
        "secret_easter" to TexteSucces(
            "Tu m'as trouvé !",
            "Découvrir le bouton caché à côté de ton prénom.",
            Ton2(
                "Tu as trouvé le bouton secret ! Bien vu.",
                "Tu appuies partout, hein ? Ça a fini par payer.",
            ),
        ),
        "secret_party" to TexteSucces(
            "Mushroom party",
            "Découvrir le mode party.",
            Ton2(
                "Le mode party est à toi ! Que la fête commence.",
                "Tu as trouvé le mode party. On ne posera pas de questions.",
            ),
        ),
        "secret_champignong" to TexteSucces(
            "Champignong",
            "Activer l'option « Tu dis le g toi ? ».",
            Ton2(
                "Champignong activé ! Chacun son accent, et c'est très bien comme ça.",
                "Champignong. On respecte. On ne comprend pas, mais on respecte.",
            ),
        ),
        "pluie_feuilles" to TexteSucces(
            "Coup de vent",
            "Déclencher la pluie de feuilles cachée.",
            Ton2(
                "Une pluie de feuilles rien que pour toi : l'automne en avance !",
                "6 appuis dans le vide. Tu t'ennuyais un peu, non ?",
            ),
        ),
        "pluie_eau" to TexteSucces(
            "Faiseur de pluie",
            "Déclencher la pluie cachée.",
            Ton2(
                "Tu as fait pleuvoir ! Ta danse de la pluie fonctionne enfin.",
                "12 appuis dans le vide pour faire pleuvoir. Les champignons te remercient, ton écran moins.",
            ),
        ),
        "pluie_champignons" to TexteSucces(
            "Il pleut des champignons",
            "Déclencher la pluie de champignons cachée.",
            Ton2(
                "Une pluie de champignons ! Le rêve de tout cueilleur.",
                "24 appuis dans le vide. Respect pour l'acharnement, sincèrement.",
            ),
        ),
    )

    /** Tous les succès, par identifiant. */
    val SUCCES: Map<String, TexteSucces> =
        PREMIERES + COLLECTION + SAISONS + FIDELITE + EXPLORATEUR + METEO + PERSEVERANCE + PRECISION + SECRETS

    /** Textes d'un succès (repli neutre si l'identifiant est inconnu). */
    fun succes(id: String): TexteSucces = SUCCES[id] ?: TexteSucces(id, "", Ton2(id, id))

    /** Message de passage au niveau [niveau], dans le ton choisi. */
    fun passageNiveau(niveau: Int, tone: MessageTone): String =
        PASSAGE_NIVEAU[Math.floorMod(niveau, PASSAGE_NIVEAU.size)].pour(tone).replace("{niveau}", niveau.toString())
}
