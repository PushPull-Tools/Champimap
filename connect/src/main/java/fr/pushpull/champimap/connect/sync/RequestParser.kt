package fr.pushpull.champimap.connect.sync

import android.content.Intent
import fr.pushpull.champimap.connect.IaRequest
import fr.pushpull.champimap.connect.SyncProtocol
import fr.pushpull.champimap.connect.SyncRequest
import fr.pushpull.champimap.connect.WeatherZoneRequest
import fr.pushpull.champimap.connect.data.Departements
import java.time.LocalDate

/*
 * Lecture et validation de la demande envoyée par Champimap (extras de l'Intent SYNC).
 * Tout ce qui ne respecte pas strictement le protocole est ignoré et signalé :
 * en particulier, une zone météo plus précise qu'un carré de 0,25° est refusée.
 */

/** Demande validée + remarques sur les éléments refusés + zoom maximal des cartes. */
data class ParsedRequest(val request: SyncRequest, val rejected: List<String>, val mapMaxZoom: Int) {
    /** Vrai s'il n'y a rien à télécharger. */
    val isEmpty: Boolean
        get() = request.departements.isEmpty() && request.mapDepartements.isEmpty() &&
            request.zones.isEmpty() && !request.poisonCenters && request.ia == null
}

object RequestParser {
    /** Nombre maximal de jours par zone (garde-fou : 10 ans). */
    private const val MAX_DAYS_PER_ZONE = 3660L

    fun parse(intent: Intent): ParsedRequest {
        val rejected = ArrayList<String>()
        val depts = codes(intent.strings(SyncProtocol.EXTRA_DEPARTEMENTS), rejected)
        val maps = codes(intent.strings(SyncProtocol.EXTRA_MAP_DEPARTEMENTS), rejected)
        val zones = intent.strings(SyncProtocol.EXTRA_WEATHER_ZONES).mapNotNull { zone(it, rejected) }
        val poison = intent.getBooleanExtra(SyncProtocol.EXTRA_POISON_CENTERS, false)
        val zoom = intent.getIntExtra(SyncProtocol.EXTRA_MAP_MAX_ZOOM, SyncProtocol.MAP_MAX_ZOOM_DEFAULT).coerceIn(10, 15)
        val ia = iaRequest(intent)
        return ParsedRequest(SyncRequest(depts, maps, zones, poison, ia), rejected, zoom)
    }

    /** Demande « modèle d'identification » (null si non demandée). Versions invalides ignorées. */
    private fun iaRequest(intent: Intent): IaRequest? {
        if (!intent.getBooleanExtra(SyncProtocol.EXTRA_INCLUDE_IA, false)) return null
        fun version(name: String): String? =
            intent.getStringExtra(name)?.trim()?.takeIf { SyncProtocol.IA_VERSION_REGEX.matches(it) }
        return IaRequest(
            installed = version(SyncProtocol.EXTRA_IA_VERSION_INSTALLED),
            force = intent.getBooleanExtra(SyncProtocol.EXTRA_IA_FORCE, false),
            wanted = version(SyncProtocol.EXTRA_IA_VERSION_WANTED),
            choose = intent.getBooleanExtra(SyncProtocol.EXTRA_IA_CHOOSE, false),
        )
    }

    /** Liste de textes : tableau (String[]) ou ArrayList<String>. */
    private fun Intent.strings(name: String): List<String> =
        getStringArrayExtra(name)?.toList() ?: getStringArrayListExtra(name)?.toList() ?: emptyList()

    /** Codes de départements valides et connus, sans doublon (« 2a » accepté comme « 2A »). */
    private fun codes(raw: List<String>, rejected: MutableList<String>): List<String> =
        raw.map { it.trim().uppercase() }.distinct().filter { code ->
            val ok = SyncProtocol.DEPT_REGEX.matches(code) && Departements.byCode(code) != null
            if (!ok) rejected.add("Département inconnu ignoré : $code")
            ok
        }

    /** « zoneId|AAAA-MM-JJ|AAAA-MM-JJ » → zone validée (dates remises dans l'ordre), sinon null. */
    private fun zone(raw: String, rejected: MutableList<String>): WeatherZoneRequest? {
        val parts = raw.trim().split('|')
        if (parts.size != 3 || !SyncProtocol.ZONE_REGEX.matches(parts[0]) ||
            !SyncProtocol.DATE_REGEX.matches(parts[1]) || !SyncProtocol.DATE_REGEX.matches(parts[2])
        ) {
            rejected.add("Zone météo refusée (format)")
            return null
        }
        val a = runCatching { LocalDate.parse(parts[1]) }.getOrNull()
        val b = runCatching { LocalDate.parse(parts[2]) }.getOrNull()
        if (a == null || b == null) {
            rejected.add("Zone météo refusée (date)")
            return null
        }
        var from = if (a.isAfter(b)) b else a
        val to = if (a.isAfter(b)) a else b
        if (from.plusDays(MAX_DAYS_PER_ZONE).isBefore(to)) from = to.minusDays(MAX_DAYS_PER_ZONE)
        return WeatherZoneRequest(parts[0], from.toString(), to.toString())
    }
}
