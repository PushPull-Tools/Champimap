package fr.pushpull.champimap.feature.connect

import android.content.Context
import fr.pushpull.champimap.core.db.Hospital
import fr.pushpull.champimap.feature.reglages.Departements
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Centres antipoison (CAP) français et urgences les plus proches.
 * Liste mise à jour par Champimap Connect (filesDir/antipoison.json) ; sinon liste intégrée de secours.
 */

/** Un centre antipoison et les départements qu'il couvre. */
data class PoisonCenter(
    val name: String,
    /** Numéro brut (chiffres), affiché avec espaces par `formatPhone`. */
    val phone: String,
    val lat: Double,
    val lon: Double,
    val departements: Set<String> = emptySet(),
)

object PoisonCenters {

    /*
     * Liste intégrée de secours (numéros publics des CAP, 24 h/24).
     * À VÉRIFIER AVANT PUBLICATION : numéros et répartition des départements
     * (rattachements Normandie, Bourgogne-Franche-Comté, Champagne-Ardenne et outre-mer notamment).
     */
    val BUILT_IN: List<PoisonCenter> = listOf(
        PoisonCenter(
            "Centre antipoison d'Angers", "0241482121", 47.48, -0.56,
            setOf("44", "49", "53", "72", "85", "18", "28", "36", "37", "41", "45"),
        ),
        PoisonCenter(
            "Centre antipoison de Bordeaux", "0556964080", 44.83, -0.58,
            setOf("16", "17", "19", "23", "24", "33", "40", "47", "64", "79", "86", "87"),
        ),
        PoisonCenter(
            "Centre antipoison de Lille", "0800595959", 50.61, 3.03,
            setOf("02", "59", "60", "62", "80"),
        ),
        PoisonCenter(
            "Centre antipoison de Lyon", "0472116911", 45.74, 4.88,
            setOf("01", "03", "07", "15", "26", "38", "42", "43", "63", "69", "73", "74"),
        ),
        PoisonCenter(
            "Centre antipoison de Marseille", "0491752525", 43.29, 5.40,
            setOf("04", "05", "06", "13", "83", "84", "2A", "2B", "974", "976"),
        ),
        PoisonCenter(
            "Centre antipoison de Nancy", "0383225050", 48.69, 6.18,
            setOf("08", "10", "51", "52", "54", "55", "57", "88", "21", "25", "39", "58", "70", "71", "89", "90"),
        ),
        PoisonCenter(
            "Centre antipoison de Paris", "0140054848", 48.88, 2.35,
            setOf("75", "77", "78", "91", "92", "93", "94", "95", "14", "27", "50", "61", "76", "971", "972", "973"),
        ),
        PoisonCenter(
            "Centre antipoison de Rennes", "0299592222", 48.12, -1.69,
            setOf("22", "29", "35", "56"),
        ),
        PoisonCenter(
            "Centre antipoison de Strasbourg", "0388373737", 48.58, 7.74,
            setOf("67", "68"),
        ),
        PoisonCenter(
            "Centre antipoison de Toulouse", "0561777447", 43.61, 1.40,
            setOf("09", "11", "12", "30", "31", "32", "34", "46", "48", "65", "66", "81", "82"),
        ),
    )

    /**
     * Lit antipoison.json (liste à jour fournie par Connect).
     * Format toléré : tableau ou {"centers": [...]} d'objets {name, phone, lat, lon, departements: [...]}.
     */
    fun parse(text: String): List<PoisonCenter> = runCatching {
        ConnectProtocol.json.parseToJsonElement(text).objectList("centers", "centres", "items").mapNotNull { o ->
            val name = o.strOf("name", "nom") ?: return@mapNotNull null
            val phone = o.strOf("phone", "telephone", "tel")?.let { digits(it) }?.takeIf { it.length >= 10 }
                ?: return@mapNotNull null
            val depts = (o["departements"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }?.toSet() ?: emptySet()
            PoisonCenter(name, phone, o.dblOf("lat", "latitude") ?: 0.0, o.dblOf("lon", "lng", "longitude") ?: 0.0, depts)
        }
    }.getOrDefault(emptyList())

    /** Liste en vigueur : celle de Connect si lisible, sinon la liste intégrée. */
    fun load(context: Context): List<PoisonCenter> {
        val f = ConnectClient.antipoisonFile(context)
        val fromConnect = if (f.isFile) runCatching { parse(f.readText()) }.getOrDefault(emptyList()) else emptyList()
        return fromConnect.ifEmpty { BUILT_IN }
    }

    /** Centre compétent pour une position : par département, sinon le plus proche à vol d'oiseau. */
    fun forPosition(centers: List<PoisonCenter>, lat: Double, lon: Double): PoisonCenter? {
        val dept = runCatching { Departements.plusProche(lat, lon).code }.getOrNull()
        return centers.firstOrNull { dept != null && dept in it.departements }
            ?: centers.filter { it.lat != 0.0 || it.lon != 0.0 }.minByOrNull { distanceKm(lat, lon, it.lat, it.lon) }
            ?: centers.firstOrNull()
    }

    /** Urgences les plus proches, avec leur distance (km). */
    fun nearestHospitals(all: List<Hospital>, lat: Double, lon: Double, count: Int = 3): List<Pair<Hospital, Double>> =
        all.map { it to distanceKm(lat, lon, it.lat, it.lon) }.sortedBy { it.second }.take(count)
}

/** Garde uniquement les chiffres d'un numéro (« +33 » ramené à « 0 »). */
fun digits(phone: String): String {
    val d = phone.filter { it.isDigit() || it == '+' }
    return when {
        d.startsWith("+33") -> "0" + d.removePrefix("+33")
        d.startsWith("0033") -> "0" + d.removePrefix("0033")
        else -> d.filter { it.isDigit() }
    }
}

/** Numéro affiché avec espaces : « 05 56 96 40 80 », « 0 800 59 59 59 », « 15 ». */
fun formatPhone(phone: String): String {
    val d = digits(phone)
    return when {
        d.length == 10 && d.startsWith("08") -> "${d[0]} ${d.substring(1, 4)} " + d.substring(4).chunked(2).joinToString(" ")
        d.length == 10 -> d.chunked(2).joinToString(" ")
        else -> d
    }
}

/** Distance à vol d'oiseau (formule de haversine), en kilomètres. */
fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(a))
}
