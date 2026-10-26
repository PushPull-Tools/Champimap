package fr.pushpull.champimap.core.util

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Calculs astronomiques 100 % hors ligne (aucune table, aucune mise à jour) :
 *  - coucher du soleil (algorithme simplifié de la NOAA, précis à ~1 minute) ;
 *  - « nuit noire en forêt » = coucher − 25 minutes (le sous-bois s'assombrit plus tôt) ;
 *  - phase de la lune.
 */
object AstroCalc {

    /** Marge appliquée sous les arbres : la nuit tombe avant le coucher officiel. */
    const val FOREST_DARK_MARGIN_MIN = 25L

    /** Heure du coucher du soleil pour une date et une position, ou null (jour/nuit polaire). */
    fun sunset(date: LocalDate, lat: Double, lon: Double, zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime? {
        val minutesUtc = sunEventMinutesUtc(date, lat, lon, rising = false) ?: return null
        val utcMidnight = date.atStartOfDay(ZoneId.of("UTC"))
        return utcMidnight.plusSeconds((minutesUtc * 60).toLong()).withZoneSameInstant(zone)
    }

    /** Heure à partir de laquelle il fait trop sombre en forêt. */
    fun forestDark(date: LocalDate, lat: Double, lon: Double, zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime? =
        sunset(date, lat, lon, zone)?.minusMinutes(FOREST_DARK_MARGIN_MIN)

    /** Minutes après minuit UTC du lever (rising) ou du coucher du soleil. */
    private fun sunEventMinutesUtc(date: LocalDate, lat: Double, lon: Double, rising: Boolean): Double? {
        val dayOfYear = date.dayOfYear
        val gamma = 2 * Math.PI / 365.0 * (dayOfYear - 1)
        // Équation du temps (minutes) et déclinaison solaire (radians).
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
            0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma))
        val decl = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) - 0.006758 * cos(2 * gamma) +
            0.000907 * sin(2 * gamma) - 0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)
        val latRad = Math.toRadians(lat)
        // Angle horaire au coucher (zénith 90,833° : réfraction + rayon du disque solaire).
        val cosHa = cos(Math.toRadians(90.833)) / (cos(latRad) * cos(decl)) - tan(latRad) * tan(decl)
        if (cosHa < -1 || cosHa > 1) return null
        val ha = Math.toDegrees(acos(cosHa))
        return if (rising) 720 - 4 * (lon + ha) - eqTime else 720 - 4 * (lon - ha) - eqTime
    }

    /** Phases de la lune (8 étapes). */
    enum class MoonPhase(val label: String, val emoji: String) {
        NOUVELLE("nouvelle lune", "🌑"), PREMIER_CROISSANT("premier croissant", "🌒"),
        PREMIER_QUARTIER("premier quartier", "🌓"), GIBBEUSE_CROISSANTE("lune gibbeuse", "🌔"),
        PLEINE("pleine lune", "🌕"), GIBBEUSE_DECROISSANTE("lune gibbeuse", "🌖"),
        DERNIER_QUARTIER("dernier quartier", "🌗"), DERNIER_CROISSANT("dernier croissant", "🌘"),
    }

    /** Âge de la lune en jours (0 = nouvelle lune), d'après une nouvelle lune de référence. */
    fun moonAgeDays(date: LocalDate): Double {
        val synodic = 29.530588853
        val ref = LocalDate.of(2000, 1, 6).toEpochDay() + 0.7576 // nouvelle lune du 6 janv. 2000, 18 h 14 UTC
        val days = date.toEpochDay() + 0.5 - ref
        return ((days % synodic) + synodic) % synodic
    }

    fun moonPhase(date: LocalDate): MoonPhase {
        val idx = floor(moonAgeDays(date) / 29.530588853 * 8 + 0.5).toInt() % 8
        return MoonPhase.entries[idx]
    }

    /** Pourcentage de la lune éclairée (0..100). */
    fun moonIllumination(date: LocalDate): Int {
        val age = moonAgeDays(date) / 29.530588853 * 2 * Math.PI
        return ((1 - cos(age)) / 2 * 100).toInt()
    }

    @Suppress("unused")
    private fun clampAsin(x: Double) = asin(x.coerceIn(-1.0, 1.0))
}
