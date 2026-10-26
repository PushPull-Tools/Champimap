package fr.pushpull.champimap.connect.download

import fr.pushpull.champimap.connect.SyncProtocol
import fr.pushpull.champimap.connect.jsonObj
import fr.pushpull.champimap.connect.net.Http
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

/*
 * Météo journalière d'une zone (carré de 0,25°) via Open-Meteo, sans clé d'API.
 *  - dates de plus de 5 jours : API « archive » (réanalyse ERA5, publiée avec quelques jours de retard) ;
 *  - dates récentes : API « forecast » avec past_days (observations/modèles des derniers jours).
 * Seul le CENTRE du carré est envoyé, jamais une position de spot.
 * Valeurs : cumul de pluie (mm), température min et max (°C), journées en heure de Paris.
 * Sortie : weather.json, tableau plat (format figé, documenté dans SyncProtocol).
 */
object WeatherDownloader {
    private const val ARCHIVE_URL = "https://archive-api.open-meteo.com/v1/archive"
    private const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
    private const val DAILY = "precipitation_sum,temperature_2m_min,temperature_2m_max"
    private const val TZ_PARAM = "Europe%2FParis"

    /** Au-delà de ce nombre de jours, l'archive est fiable ; en deçà, on interroge « forecast ». */
    private const val ARCHIVE_DELAY_DAYS = 5L

    /** Plus ancienne date acceptée (début des données d'archive utiles). */
    private val OLDEST: LocalDate = LocalDate.of(1950, 1, 1)

    /** Fuseau des dates échangées avec Champimap. */
    val PARIS: ZoneId = ZoneId.of("Europe/Paris")

    /** Une journée de météo (valeurs absentes = null). */
    data class Day(val date: LocalDate, val precipitationMm: Double?, val tempMinC: Double?, val tempMaxC: Double?)

    /** Toutes les dates de [from, to], bornées à « aujourd'hui » et à OLDEST. */
    fun datesBetween(from: LocalDate, to: LocalDate, today: LocalDate): List<LocalDate> {
        val start = if (from.isBefore(OLDEST)) OLDEST else from
        val end = if (to.isAfter(today)) today else to
        val out = ArrayList<LocalDate>()
        var d = start
        while (!d.isAfter(end)) {
            out.add(d)
            d = d.plusDays(1)
        }
        return out
    }

    /**
     * Télécharge la météo des `dates` pour le point (lat, lon) : au plus une requête archive
     * et une requête forecast, puis ne garde que les dates demandées (triées).
     */
    fun downloadZone(lat: Double, lon: Double, dates: Set<LocalDate>, today: LocalDate): List<Day> {
        if (dates.isEmpty()) return emptyList()
        val cutoff = today.minusDays(ARCHIVE_DELAY_DAYS)
        val old = dates.filter { it.isBefore(cutoff) }
        val recent = dates.filter { !it.isBefore(cutoff) && !it.isAfter(today) }
        val ll = String.format(Locale.US, "latitude=%.4f&longitude=%.4f", lat, lon)
        val all = HashMap<LocalDate, Day>()

        if (old.isNotEmpty()) {
            val url = "$ARCHIVE_URL?$ll&start_date=${old.min()}&end_date=${old.max()}" +
                "&daily=$DAILY&timezone=$TZ_PARAM"
            all.putAll(parseDaily(Http.get(url)))
        }
        if (recent.isNotEmpty()) {
            // past_days compte les jours avant aujourd'hui ; forecast_days=1 = aujourd'hui seulement.
            val pastDays = ChronoUnit.DAYS.between(recent.min(), today).coerceIn(0, 92)
            val url = "$FORECAST_URL?$ll&past_days=$pastDays&forecast_days=1&daily=$DAILY&timezone=$TZ_PARAM"
            // Les données « forecast » priment pour les jours récents.
            all.putAll(parseDaily(Http.get(url)))
        }
        return dates.sorted().mapNotNull { all[it] }
    }

    /** Lit le bloc « daily » d'une réponse Open-Meteo. */
    private fun parseDaily(raw: ByteArray): Map<LocalDate, Day> {
        val root = try {
            SyncProtocol.json.parseToJsonElement(String(raw, Charsets.UTF_8)).jsonObject
        } catch (e: Exception) {
            throw IOException("Réponse météo illisible")
        }
        if (root["error"]?.jsonPrimitive?.booleanOrNull == true) {
            throw IOException(root["reason"]?.jsonPrimitive?.contentOrNull ?: "Erreur Open-Meteo")
        }
        val daily = root["daily"]?.jsonObject ?: throw IOException("Réponse météo incomplète")
        val time = daily["time"]?.jsonArray ?: throw IOException("Réponse météo incomplète")
        val rain = daily["precipitation_sum"]?.jsonArray
        val tMin = daily["temperature_2m_min"]?.jsonArray
        val tMax = daily["temperature_2m_max"]?.jsonArray
        val out = HashMap<LocalDate, Day>()
        for (i in time.indices) {
            val text = (time[i] as? JsonPrimitive)?.contentOrNull ?: continue
            val date = runCatching { LocalDate.parse(text) }.getOrNull() ?: continue
            out[date] = Day(date, rain.num(i), tMin.num(i), tMax.num(i))
        }
        return out
    }

    /** Valeur numérique à l'index i d'une colonne (null si absente ou nulle). */
    private fun JsonArray?.num(i: Int): Double? = (this?.getOrNull(i) as? JsonPrimitive)?.doubleOrNull

    /**
     * Lignes de weather.json pour une zone : { zoneId, date, rainMm, tMinC, tMaxC }.
     * Les jours incomplets (une valeur absente) sont omis : l'appli ne saurait pas les enregistrer.
     */
    fun rows(zoneId: String, days: List<Day>): List<JsonObject> = days.mapNotNull { d ->
        val rain = d.precipitationMm ?: return@mapNotNull null
        val lo = d.tempMinC ?: return@mapNotNull null
        val hi = d.tempMaxC ?: return@mapNotNull null
        jsonObj("zoneId" to zoneId, "date" to d.date.toString(), "rainMm" to rain, "tMinC" to lo, "tMaxC" to hi)
    }
}
