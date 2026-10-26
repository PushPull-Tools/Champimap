package fr.pushpull.champimap.connect.download

import fr.pushpull.champimap.connect.jsonObj
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/*
 * Centres antipoison français (CAP) : liste intégrée, renvoyée telle quelle dans antipoison.json.
 * Aucune requête réseau : la liste évolue rarement, elle est mise à jour avec les versions de Connect.
 *
 * À VÉRIFIER AVANT PUBLICATION : numéros d'urgence et zones de compétence (répartition des départements
 * entre centres, en particulier Normandie, Centre-Val de Loire et outre-mer). Source de référence :
 * centres-antipoison.net. Numéros au format d'affichage avec espaces.
 */

/** Un centre antipoison (ville et coordonnées de la ville) et les départements dont il est le centre de référence. */
data class PoisonCenter(
    val id: String,
    val name: String,
    val city: String,
    val lat: Double,
    val lon: Double,
    val phone: String,
    val regions: List<String>,
    val departements: List<String>,
)

object PoisonCenters {
    /** Indique dans le fichier que la liste n'a pas encore été vérifiée (passer à true après contrôle). */
    const val VERIFIED = false

    val ALL: List<PoisonCenter> = listOf(
        PoisonCenter(
            "angers", "Centre antipoison d'Angers (Grand-Ouest)", "Angers", 47.4784, -0.5632, "02 41 48 21 21",
            listOf("Bretagne", "Pays de la Loire", "Centre-Val de Loire", "Normandie"),
            listOf(
                "22", "29", "35", "56", "44", "49", "53", "72", "85",
                "18", "28", "36", "37", "41", "45", "14", "27", "50", "61", "76",
            ),
        ),
        PoisonCenter(
            "bordeaux", "Centre antipoison de Bordeaux", "Bordeaux", 44.8378, -0.5792, "05 56 96 40 80",
            listOf("Nouvelle-Aquitaine"),
            listOf("16", "17", "19", "23", "24", "33", "40", "47", "64", "79", "86", "87"),
        ),
        PoisonCenter(
            "lille", "Centre antipoison de Lille", "Lille", 50.6292, 3.0573, "0 800 59 59 59",
            listOf("Hauts-de-France"),
            listOf("02", "59", "60", "62", "80"),
        ),
        PoisonCenter(
            "lyon", "Centre antipoison de Lyon", "Lyon", 45.7640, 4.8357, "04 72 11 69 11",
            listOf("Auvergne-Rhône-Alpes"),
            listOf("01", "03", "07", "15", "26", "38", "42", "43", "63", "69", "73", "74"),
        ),
        PoisonCenter(
            "marseille", "Centre antipoison de Marseille", "Marseille", 43.2965, 5.3698, "04 91 75 25 25",
            listOf("Provence-Alpes-Côte d'Azur", "Corse", "La Réunion", "Mayotte"),
            listOf("04", "05", "06", "13", "83", "84", "2A", "2B", "974", "976"),
        ),
        PoisonCenter(
            "nancy", "Centre antipoison de Nancy", "Nancy", 48.6921, 6.1844, "03 83 22 50 50",
            listOf("Grand Est", "Bourgogne-Franche-Comté"),
            listOf(
                "08", "10", "51", "52", "54", "55", "57", "67", "68", "88",
                "21", "25", "39", "58", "70", "71", "89", "90",
            ),
        ),
        PoisonCenter(
            "paris", "Centre antipoison de Paris", "Paris", 48.8566, 2.3522, "01 40 05 48 48",
            listOf("Île-de-France", "Guadeloupe", "Martinique", "Guyane"),
            listOf("75", "77", "78", "91", "92", "93", "94", "95", "971", "972", "973"),
        ),
        PoisonCenter(
            "toulouse", "Centre antipoison de Toulouse", "Toulouse", 43.6047, 1.4442, "05 61 77 74 47",
            listOf("Occitanie"),
            listOf("09", "11", "12", "30", "31", "32", "34", "46", "48", "65", "66", "81", "82"),
        ),
    )

    /** Contenu de antipoison.json : tableau d'objets (format figé, documenté dans SyncProtocol). */
    fun toJson(): JsonArray = JsonArray(ALL.map { c ->
        jsonObj(
            "id" to c.id, "name" to c.name, "city" to c.city, "lat" to c.lat, "lon" to c.lon,
            "phone" to c.phone, "verified" to VERIFIED,
            "regions" to JsonArray(c.regions.map { JsonPrimitive(it) }),
            "departements" to JsonArray(c.departements.map { JsonPrimitive(it) }),
        )
    })
}
