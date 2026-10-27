package fr.pushpull.champimap.feature.reglages

import java.text.Normalizer
import kotlin.math.cos
import kotlin.math.roundToInt

/*
 * Table hors ligne des 101 départements français : code, nom, centre approximatif
 * (pour l'astronomie et la recherche « arrêtés locaux ») et superficie (pour estimer
 * la taille des cartes à télécharger par Champimap Connect).
 */

/** Un département : code INSEE, nom, centre approximatif (lat, lon), superficie en km². */
data class Departement(val code: String, val nom: String, val lat: Double, val lon: Double, val km2: Int) {
    /** Taille estimée des cartes hors ligne (Mo), zoom 15 (chemins et lieux-dits) : ~2,5 Mo pour 400 km², bornée. */
    val tailleMo: Int get() = (km2 / 160.0).roundToInt().coerceIn(4, 300)
    /** Libellé affiché, ex. « 87 · Haute-Vienne ». */
    val libelle: String get() = "$code · $nom"
}

object Departements {

    /** Liste complète (métropole, Corse, outre-mer), triée par code. */
    val TOUS: List<Departement> = listOf(
        Departement("01", "Ain", 46.10, 5.35, 5762),
        Departement("02", "Aisne", 49.56, 3.56, 7369),
        Departement("03", "Allier", 46.39, 3.19, 7340),
        Departement("04", "Alpes-de-Haute-Provence", 44.10, 6.24, 6925),
        Departement("05", "Hautes-Alpes", 44.66, 6.33, 5549),
        Departement("06", "Alpes-Maritimes", 43.94, 7.12, 4299),
        Departement("07", "Ardèche", 44.75, 4.42, 5529),
        Departement("08", "Ardennes", 49.62, 4.63, 5229),
        Departement("09", "Ariège", 42.92, 1.50, 4890),
        Departement("10", "Aube", 48.30, 4.16, 6004),
        Departement("11", "Aude", 43.10, 2.41, 6139),
        Departement("12", "Aveyron", 44.28, 2.68, 8735),
        Departement("13", "Bouches-du-Rhône", 43.54, 5.09, 5087),
        Departement("14", "Calvados", 49.10, -0.36, 5548),
        Departement("15", "Cantal", 45.05, 2.67, 5726),
        Departement("16", "Charente", 45.72, 0.20, 5956),
        Departement("17", "Charente-Maritime", 45.78, -0.67, 6864),
        Departement("18", "Cher", 47.06, 2.49, 7235),
        Departement("19", "Corrèze", 45.36, 1.88, 5857),
        Departement("2A", "Corse-du-Sud", 41.86, 8.98, 4014),
        Departement("2B", "Haute-Corse", 42.39, 9.21, 4666),
        Departement("21", "Côte-d'Or", 47.42, 4.77, 8763),
        Departement("22", "Côtes-d'Armor", 48.44, -2.86, 6878),
        Departement("23", "Creuse", 46.09, 2.02, 5565),
        Departement("24", "Dordogne", 45.10, 0.74, 9060),
        Departement("25", "Doubs", 47.17, 6.36, 5234),
        Departement("26", "Drôme", 44.68, 5.17, 6530),
        Departement("27", "Eure", 49.11, 1.00, 6040),
        Departement("28", "Eure-et-Loir", 48.39, 1.37, 5880),
        Departement("29", "Finistère", 48.26, -4.06, 6733),
        Departement("30", "Gard", 43.99, 4.18, 5853),
        Departement("31", "Haute-Garonne", 43.36, 1.17, 6309),
        Departement("32", "Gers", 43.69, 0.45, 6257),
        Departement("33", "Gironde", 44.83, -0.58, 9976),
        Departement("34", "Hérault", 43.58, 3.37, 6101),
        Departement("35", "Ille-et-Vilaine", 48.15, -1.64, 6775),
        Departement("36", "Indre", 46.78, 1.58, 6791),
        Departement("37", "Indre-et-Loire", 47.26, 0.69, 6127),
        Departement("38", "Isère", 45.26, 5.58, 7431),
        Departement("39", "Jura", 46.73, 5.70, 4999),
        Departement("40", "Landes", 43.96, -0.78, 9243),
        Departement("41", "Loir-et-Cher", 47.62, 1.43, 6343),
        Departement("42", "Loire", 45.73, 4.17, 4781),
        Departement("43", "Haute-Loire", 45.13, 3.81, 4977),
        Departement("44", "Loire-Atlantique", 47.35, -1.69, 6815),
        Departement("45", "Loiret", 47.91, 2.34, 6775),
        Departement("46", "Lot", 44.62, 1.60, 5217),
        Departement("47", "Lot-et-Garonne", 44.37, 0.46, 5361),
        Departement("48", "Lozère", 44.52, 3.50, 5167),
        Departement("49", "Maine-et-Loire", 47.39, -0.56, 7166),
        Departement("50", "Manche", 49.08, -1.33, 5938),
        Departement("51", "Marne", 48.95, 4.24, 8162),
        Departement("52", "Haute-Marne", 48.11, 5.23, 6211),
        Departement("53", "Mayenne", 48.15, -0.65, 5175),
        Departement("54", "Meurthe-et-Moselle", 48.79, 6.16, 5246),
        Departement("55", "Meuse", 48.99, 5.38, 6211),
        Departement("56", "Morbihan", 47.84, -2.81, 6823),
        Departement("57", "Moselle", 49.04, 6.66, 6216),
        Departement("58", "Nièvre", 47.11, 3.50, 6817),
        Departement("59", "Nord", 50.45, 3.22, 5743),
        Departement("60", "Oise", 49.41, 2.42, 5860),
        Departement("61", "Orne", 48.62, 0.13, 6103),
        Departement("62", "Pas-de-Calais", 50.49, 2.29, 6671),
        Departement("63", "Puy-de-Dôme", 45.72, 3.14, 7970),
        Departement("64", "Pyrénées-Atlantiques", 43.26, -0.76, 7645),
        Departement("65", "Hautes-Pyrénées", 43.05, 0.16, 4464),
        Departement("66", "Pyrénées-Orientales", 42.60, 2.52, 4116),
        Departement("67", "Bas-Rhin", 48.67, 7.55, 4755),
        Departement("68", "Haut-Rhin", 47.86, 7.27, 3525),
        Departement("69", "Rhône", 45.87, 4.64, 3249),
        Departement("70", "Haute-Saône", 47.64, 6.09, 5360),
        Departement("71", "Saône-et-Loire", 46.64, 4.54, 8575),
        Departement("72", "Sarthe", 47.99, 0.22, 6206),
        Departement("73", "Savoie", 45.48, 6.44, 6028),
        Departement("74", "Haute-Savoie", 46.03, 6.43, 4388),
        Departement("75", "Paris", 48.857, 2.342, 105),
        Departement("76", "Seine-Maritime", 49.66, 1.03, 6278),
        Departement("77", "Seine-et-Marne", 48.62, 2.93, 5915),
        Departement("78", "Yvelines", 48.82, 1.84, 2284),
        Departement("79", "Deux-Sèvres", 46.56, -0.32, 5999),
        Departement("80", "Somme", 49.96, 2.28, 6170),
        Departement("81", "Tarn", 43.79, 2.17, 5758),
        Departement("82", "Tarn-et-Garonne", 44.08, 1.27, 3718),
        Departement("83", "Var", 43.46, 6.22, 5973),
        Departement("84", "Vaucluse", 44.00, 5.18, 3567),
        Departement("85", "Vendée", 46.67, -1.30, 6720),
        Departement("86", "Vienne", 46.56, 0.46, 6990),
        Departement("87", "Haute-Vienne", 45.89, 1.24, 5520),
        Departement("88", "Vosges", 48.20, 6.38, 5874),
        Departement("89", "Yonne", 47.84, 3.56, 7427),
        Departement("90", "Territoire de Belfort", 47.63, 6.93, 609),
        Departement("91", "Essonne", 48.52, 2.24, 1804),
        Departement("92", "Hauts-de-Seine", 48.84, 2.24, 176),
        Departement("93", "Seine-Saint-Denis", 48.91, 2.48, 236),
        Departement("94", "Val-de-Marne", 48.78, 2.47, 245),
        Departement("95", "Val-d'Oise", 49.08, 2.13, 1246),
        Departement("971", "Guadeloupe", 16.20, -61.50, 1628),
        Departement("972", "Martinique", 14.65, -61.00, 1128),
        Departement("973", "Guyane", 3.90, -53.10, 83534),
        Departement("974", "La Réunion", -21.12, 55.53, 2504),
        Departement("976", "Mayotte", -12.82, 45.15, 374),
    )

    private val parCode: Map<String, Departement> = TOUS.associateBy { it.code }

    fun get(code: String): Departement? = parCode[code]

    /** Minuscules sans accents ni tirets : « Côtes-d'Armor » → « cotes d armor ». */
    fun normaliser(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[-'’]"), " ")
            .trim()

    /** Recherche tolérante sur le code ou le nom (accents et tirets ignorés). */
    fun rechercher(q: String): List<Departement> {
        val n = normaliser(q)
        if (n.isEmpty()) return TOUS
        return TOUS.filter { it.code.lowercase().startsWith(n) || normaliser(it.nom).contains(n) }
    }

    /** Taille totale estimée (Mo) pour une sélection de codes. */
    fun tailleTotaleMo(codes: Collection<String>): Int = codes.sumOf { get(it)?.tailleMo ?: 0 }

    /**
     * Département dont le centre est le plus proche d'une position (hors ligne, approximatif :
     * suffisant pour une recherche d'arrêtés préfectoraux, pas pour une limite exacte).
     */
    fun plusProche(lat: Double, lon: Double): Departement {
        val k = cos(Math.toRadians(lat))
        return TOUS.minBy { d ->
            val dLat = d.lat - lat
            val dLon = (d.lon - lon) * k
            dLat * dLat + dLon * dLon
        }
    }
}
