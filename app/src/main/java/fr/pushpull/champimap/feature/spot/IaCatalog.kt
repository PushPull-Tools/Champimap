package fr.pushpull.champimap.feature.spot

import fr.pushpull.champimap.core.db.Edibility
import fr.pushpull.champimap.core.db.Species
import kotlinx.serialization.Serializable
import java.util.Locale

/*
 * Catalogue étendu par le modèle d'identification :
 *  - lecture des espèces de especes_ia.json du modèle (IaAssets) absentes de species.json
 *    (comestibilité « non renseignée », jamais proposées comme comestibles) ;
 *  - filtre par genre : lichens, rouilles, charbons, oïdiums, moisissures et parasites de plantes
 *    restent dans le modèle mais ne sont ni ajoutés au catalogue ni proposés à l'écran ;
 *  - espèces dangereuses du catalogue que le modèle ne connaît pas (avertissement sur l'écran de résultat).
 */

/** Une espèce de especes_ia.json. `catalogue` = déjà présente dans species.json (même id). */
@Serializable
internal data class IaSpeciesJson(
    val id: String,
    val nameLatin: String,
    val nameFr: String = "",
    val taxon: Long? = null,
    val catalogue: Boolean = false,
)

/**
 * Genres masqués (lichens, rouilles, charbons, oïdiums, moisissures, parasites de plantes ou d'insectes) :
 * rien à cueillir, ils encombreraient la liste et les propositions.
 */
private val GENRES_MASQUES: Set<String> = setOf(
    // Lichens
    "Acarospora", "Alyxoria", "Anaptychia", "Arthonia", "Bacidia", "Baeomyces", "Bagliettoa", "Blastenia",
    "Blennothallia", "Brodoa", "Bryoria", "Calogaya", "Caloplaca", "Candelaria", "Candelariella", "Cetraria",
    "Chaenotheca", "Chrysothrix", "Circinaria", "Cladonia", "Coniocarpon", "Cornicularia", "Dendrographa",
    "Dermatocarpon", "Dibaeis", "Dimelaena", "Diploicia", "Diploschistes", "Dirina", "Enchylium", "Enterographa",
    "Evernia", "Flavocetraria", "Flavoparmelia", "Flavoplaca", "Fuscidea", "Glaucomaria", "Graphis", "Gyalecta",
    "Gyalolechia", "Haematomma", "Hydropunctaria", "Hyperphyscia", "Hypocenomyce", "Hypogymnia", "Hypotrachyna",
    "Icmadophila", "Imshaugia", "Kuettlingeria", "Lasallia", "Lathagrium", "Lecanora", "Lecidea", "Lecidella",
    "Lepra", "Lepraria", "Leprocaulon", "Leproplaca", "Leptogium", "Letharia", "Lichina", "Lobaria", "Lobarina",
    "Lobothallia", "Melanelia", "Melanelixia", "Melanohalea", "Myriolecis", "Nephroma", "Normandina",
    "Ochrolechia", "Opegrapha", "Ophioparma", "Pannaria", "Parmelia", "Parmelina", "Parmeliopsis", "Parmotrema",
    "Peltigera", "Pertusaria", "Petractis", "Phaeographis", "Phaeophyscia", "Phlyctis", "Physcia", "Physciella",
    "Physconia", "Placocarpus", "Placynthium", "Platismatia", "Pleurosticta", "Polycauliona", "Protoblastenia",
    "Protoparmelia", "Protoparmeliopsis", "Pseudevernia", "Pseudoschismatomma", "Pseudosolorina", "Psilolechia",
    "Psora", "Punctelia", "Pyrenodesmia", "Ramalina", "Rhizocarpon", "Rhizoplaca", "Ricasolia", "Roccella",
    "Romjularia", "Rusavskia", "Sarcogyne", "Scytinium", "Solorina", "Sphaerophorus", "Spilopodia", "Sporastatia",
    "Squamarina", "Sticta", "Teloschistes", "Tephromela", "Thalloidima", "Thamnolia", "Thelotrema", "Toniniopsis",
    "Umbilicaria", "Usnea", "Variospora", "Verrucaria", "Vulpicida", "Xalocoa", "Xanthocarpia", "Xanthomendoza",
    "Xanthoparmelia", "Xanthoria",
    // Rouilles
    "Aecidium", "Chrysomyxa", "Coleosporium", "Cronartium", "Endophyllum", "Gymnosporangium", "Kuehneola",
    "Melampsora", "Melampsoridium", "Phragmidium", "Puccinia", "Pucciniastrum", "Trachyspora", "Triphragmium",
    "Uromyces",
    // Charbons
    "Entyloma", "Microbotryum", "Mycosarcoma", "Urocystis", "Ustilago",
    // Oïdiums (blancs des plantes)
    "Erysiphe", "Golovinomyces", "Neoerysiphe", "Phyllactinia", "Podosphaera", "Sawadaea",
    // Moisissures, taches foliaires, parasites de plantes, d'insectes ou de lichens
    "Akanthomyces", "Botrytis", "Claviceps", "Coccomyces", "Diplocarpon", "Engyodontium", "Entomophaga",
    "Entomophthora", "Epichloe", "Erythricium", "Exobasidium", "Hesperomyces", "Illosporiopsis", "Laetisaria",
    "Marchandiomyces", "Monilinia", "Mycogone", "Pseudomicrostroma", "Ramularia", "Rhytisma", "Septobasidium",
    "Spinellus", "Taphrina", "Trichoderma", "Venturia", "Wilsonomyces", "Xanthoriicola",
)

/** Vrai si l'espèce (nom latin) appartient à un genre masqué du catalogue et des propositions. */
fun isHiddenGenus(nameLatin: String): Boolean =
    nameLatin.trim().substringBefore(' ') in GENRES_MASQUES

/**
 * Conversion d'une espèce du modèle vers l'entité Room :
 * nom français avec majuscule initiale (repli sur le latin), comestibilité INCONNU, pas « courante ».
 */
internal fun IaSpeciesJson.toEntity(): Species {
    val fr = nameFr.trim().replaceFirstChar { it.titlecase(Locale.FRENCH) }
    return Species(
        id = id,
        nameFr = fr.ifEmpty { nameLatin },
        nameLatin = nameLatin,
        edibility = Edibility.INCONNU,
        common = false,
    )
}

/** Espèces du modèle à ajouter au catalogue : hors species.json et hors genres masqués. */
internal fun List<IaSpeciesJson>.toCatalogAdditions(): List<Species> =
    filter { !it.catalogue && it.id.isNotBlank() && it.nameLatin.isNotBlank() && !isHiddenGenus(it.nameLatin) }
        .map { it.toEntity() }

/**
 * Espèces TOXIQUES ou MORTELLES du catalogue que le modèle ne sait pas reconnaître
 * (aucune photo d'entraînement), mortelles d'abord puis par nom. Vide sans modèle.
 */
fun unknownDangerousSpecies(all: List<Species>, labelIds: Set<String>): List<Species> {
    if (labelIds.isEmpty()) return emptyList()
    return all.filter { (it.edibility == Edibility.MORTEL || it.edibility == Edibility.TOXIQUE) && it.id !in labelIds }
        .sortedWith(compareBy<Species>({ if (it.edibility == Edibility.MORTEL) 0 else 1 }, { it.nameFr }))
}
