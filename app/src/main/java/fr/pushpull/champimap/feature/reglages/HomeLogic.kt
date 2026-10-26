package fr.pushpull.champimap.feature.reglages

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import androidx.core.content.ContextCompat
import fr.pushpull.champimap.core.util.AstroCalc
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Calculs de l'Accueil, sans interface : position approximative (hors ligne),
 * coucher du soleil, lune, période de chasse, début de saison, mémoire du bandeau chasse.
 * Rien de tout cela n'est écrit dans le journal (aucune coordonnée).
 */

object HomeLogic {

    private val FR = Locale.FRANCE
    private val SUNSET_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("H 'h' mm", FR)
    private val DAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", FR)

    /** Préférences locales de l'écran (non sauvegardées dans les réglages partagés). */
    private const val PREFS = "reglages_local"
    private const val KEY_HUNT_HIDDEN_AT = "chasseMasqueeA"
    private const val KEY_OPEN_CONNECT = "ouvrirConnect"

    /**
     * Position approximative : dernière position connue du téléphone si la permission est accordée,
     * sinon centre du premier département coché, sinon centre de la Haute-Vienne.
     */
    fun approxPosition(context: Context, departements: List<String>): Pair<Double, Double> {
        lastKnown(context)?.let { return it.latitude to it.longitude }
        val d = departements.firstNotNullOfOrNull { Departements.get(it) } ?: Departements.get("87")!!
        return d.lat to d.lon
    }

    /** Dernière position connue (tous fournisseurs actifs, la plus récente), ou null. */
    private fun lastKnown(context: Context): Location? {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return try {
            lm.getProviders(true).mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
                .maxByOrNull { it.time }
        } catch (e: SecurityException) {
            null
        }
    }

    /** Ligne du haut : « lundi 26 octobre ». */
    fun dayLabel(date: LocalDate): String = date.format(DAY_FMT)

    /** « 17 h 46 », ou null (impossible en France, mais prévu par AstroCalc). */
    fun sunsetLabel(date: LocalDate, lat: Double, lon: Double): String? =
        AstroCalc.sunset(date, lat, lon, ZoneId.systemDefault())?.format(SUNSET_FMT)

    /** Période de chasse : du 1er septembre au 28/29 février. */
    fun isHuntingPeriod(date: LocalDate): Boolean = date.monthValue >= 9 || date.monthValue <= 2

    /** Saison de chasse en cours, ex. « 2026-2027 ». */
    fun huntingSeason(date: LocalDate): String {
        val start = if (date.monthValue >= 9) date.year else date.year - 1
        return "$start-${start + 1}"
    }

    /** Recherche web des arrêtés (ouverte dans le navigateur, l'appli n'a pas Internet). */
    fun arretesUrl(date: LocalDate, departement: Departement): String {
        val q = "Période de chasse ${huntingSeason(date)} ${departement.nom} arrêté préfectoral"
        return "https://duckduckgo.com/?q=" + Uri.encode(q)
    }

    /**
     * Début de la « saison » champignons affichée sur l'Accueil : le 1er juillet le plus récent
     * (une saison couvre l'automne et le printemps suivant, sans couper en janvier).
     */
    fun seasonStartMillis(today: LocalDate = LocalDate.now()): Long {
        val year = if (today.monthValue >= 7) today.year else today.year - 1
        return LocalDate.of(year, 7, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    /** Moment où l'utilisateur a masqué le bandeau chasse (0 = jamais). */
    fun huntHiddenAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_HUNT_HIDDEN_AT, 0L)

    fun hideHunt(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_HUNT_HIDDEN_AT, System.currentTimeMillis()).apply()
    }

    /**
     * « Ouvrir les paramètres » à la fin du premier lancement : la navigation est réinitialisée
     * quand onboardingDone passe à true, donc l'Accueil ouvre l'écran Connect lui-même.
     */
    fun requestOpenConnect(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_OPEN_CONNECT, true).apply()
    }

    /** Lit et efface la demande d'ouverture de l'écran Connect. */
    fun consumeOpenConnect(context: Context): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val v = p.getBoolean(KEY_OPEN_CONNECT, false)
        if (v) p.edit().remove(KEY_OPEN_CONNECT).apply()
        return v
    }

    /** Bandeau masqué « jusqu'à la prochaine sortie » : il revient dès qu'une sortie commence après. */
    fun huntBannerVisible(hiddenAt: Long, lastSortieStart: Long?): Boolean =
        hiddenAt == 0L || (lastSortieStart ?: 0L) > hiddenAt

    /** Poids lisible : « 850 g » ou « 2,4 kg ». */
    fun weightLabel(g: Long): String =
        if (g < 1000) "$g g" else String.format(FR, "%.1f kg", g / 1000.0)
}
