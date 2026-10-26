package fr.pushpull.champimap.connect.download

import fr.pushpull.champimap.connect.SyncProtocol
import fr.pushpull.champimap.connect.data.Dept
import fr.pushpull.champimap.connect.jsonObj
import fr.pushpull.champimap.connect.net.Http
import fr.pushpull.champimap.connect.util.Phones
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale

/*
 * Hôpitaux avec service d'urgences d'un département, via l'API Overpass (OpenStreetMap).
 * Seule l'emprise rectangulaire du département est envoyée : aucune position de spot.
 * Sortie : hospitals_<code>.json, tableau d'objets (format figé, documenté dans SyncProtocol).
 */
object HospitalDownloader {
    private const val URL = "https://overpass-api.de/api/interpreter"

    /** Interroge Overpass et renvoie le tableau JSON prêt à écrire (hospitals_<code>.json). */
    fun download(dept: Dept): JsonArray {
        val b = dept.bbox
        // Overpass attend (sud, ouest, nord, est).
        val bbox = String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", b.minLat, b.minLon, b.maxLat, b.maxLon)
        val query = "[out:json][timeout:60];nwr[\"amenity\"=\"hospital\"][\"emergency\"=\"yes\"]($bbox);out center tags;"
        val body = "data=" + URLEncoder.encode(query, "UTF-8")
        val raw = Http.postForm(URL, body)
        return JsonArray(parse(raw, dept.code))
    }

    /** Transforme la réponse Overpass en liste d'hôpitaux (id, nom, lat, lon, téléphone, département). */
    private fun parse(raw: ByteArray, code: String): List<JsonObject> {
        val root = try {
            SyncProtocol.json.parseToJsonElement(String(raw, Charsets.UTF_8)).jsonObject
        } catch (e: Exception) {
            throw IOException("Réponse Overpass illisible")
        }
        val elements = root["elements"]?.jsonArray ?: return emptyList()
        val out = ArrayList<JsonObject>()
        for (el in elements) {
            val o = el.jsonObject
            val type = o["type"]?.jsonPrimitive?.contentOrNull ?: continue
            val id = o["id"]?.jsonPrimitive?.longOrNull ?: continue
            // Nœud : lat/lon directs ; chemin ou relation : « center » calculé par Overpass.
            val pos = if (type == "node") o else o["center"]?.jsonObject
            val lat = pos?.get("lat")?.jsonPrimitive?.doubleOrNull ?: continue
            val lon = pos?.get("lon")?.jsonPrimitive?.doubleOrNull ?: continue
            val tags = o["tags"]?.jsonObject
            fun tag(k: String): String? = tags?.get(k)?.jsonPrimitive?.contentOrNull
            val name = tag("name") ?: tag("official_name") ?: tag("short_name")
            val phone = Phones.format(tag("phone") ?: tag("contact:phone") ?: tag("emergency:phone"))
            out.add(
                jsonObj(
                    "id" to "$type/$id", "name" to name, "lat" to lat, "lon" to lon,
                    "phone" to phone, "departement" to code,
                ),
            )
        }
        return out
    }
}
