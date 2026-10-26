package fr.pushpull.champimap.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import fr.pushpull.champimap.core.settings.AppSettings
import fr.pushpull.champimap.core.settings.ThemeMode

/*
 * Charte graphique Champimap (validée sur la maquette).
 * Clair : fond #F6F5F1, surface blanche, vert mousse #2F5D46, orange girolle #E08A2E, encre #1A1D1A.
 * Sombre : fond #121613, surface #1D231F, mousse #7FB89A, girolle #F0A24B.
 * Psyché : thème « Mushroom party » (fond animé géré par feature/fun).
 */
object ChampiColors {
    val Fond = Color(0xFFF6F5F1)
    val Surface = Color(0xFFFFFFFF)
    val Mousse = Color(0xFF2F5D46)
    val MousseClair = Color(0xFFE3EDE6)
    val MousseTexte = Color(0xFF22432F)
    val Girolle = Color(0xFFE08A2E)
    val GirolleClair = Color(0xFFFCEBD6)
    val GirolleTexte = Color(0xFF7A3E06)
    val Encre = Color(0xFF1A1D1A)
    val Gris = Color(0xFF5C635D)
    val Bordure = Color(0xFFDAD8D0)
    val Separateur = Color(0xFFECEAE3)
    val Urgence = Color(0xFFB3261E)

    val FondSombre = Color(0xFF121613)
    val SurfaceSombre = Color(0xFF1D231F)
    val MousseSombre = Color(0xFF7FB89A)
    val GirolleSombre = Color(0xFFF0A24B)
    val TexteSombre = Color(0xFFE8ECE9)
    val GrisSombre = Color(0xFFA9B3AC)

    // Psyché (Mushroom party)
    val PsyViolet = Color(0xFF7B2FF7)
    val PsyRose = Color(0xFFF107A3)
    val PsyOrange = Color(0xFFFF8A00)
    val PsyTurquoise = Color(0xFF2BD9A9)
    val PsyJaune = Color(0xFFF9F871)
    val PsyNuit = Color(0xFF1A0B2E)
}

private val LightScheme = lightColorScheme(
    primary = ChampiColors.Mousse, onPrimary = Color.White,
    primaryContainer = ChampiColors.MousseClair, onPrimaryContainer = ChampiColors.MousseTexte,
    secondary = ChampiColors.Girolle, onSecondary = ChampiColors.Encre,
    secondaryContainer = ChampiColors.GirolleClair, onSecondaryContainer = ChampiColors.GirolleTexte,
    background = ChampiColors.Fond, onBackground = ChampiColors.Encre,
    surface = ChampiColors.Surface, onSurface = ChampiColors.Encre,
    surfaceVariant = ChampiColors.Separateur, onSurfaceVariant = ChampiColors.Gris,
    outline = ChampiColors.Bordure, error = ChampiColors.Urgence,
)

private val DarkScheme = darkColorScheme(
    primary = ChampiColors.MousseSombre, onPrimary = ChampiColors.FondSombre,
    primaryContainer = Color(0xFF24322A), onPrimaryContainer = Color(0xFFCFE2D6),
    secondary = ChampiColors.GirolleSombre, onSecondary = ChampiColors.Encre,
    secondaryContainer = Color(0xFF4A2F12), onSecondaryContainer = Color(0xFFFCEBD6),
    background = ChampiColors.FondSombre, onBackground = ChampiColors.TexteSombre,
    surface = ChampiColors.SurfaceSombre, onSurface = ChampiColors.TexteSombre,
    surfaceVariant = Color(0xFF2A322D), onSurfaceVariant = ChampiColors.GrisSombre,
    outline = Color(0xFF3A4740), error = Color(0xFFF2B8B5),
)

private val PsycheScheme = darkColorScheme(
    primary = ChampiColors.PsyJaune, onPrimary = ChampiColors.PsyNuit,
    primaryContainer = ChampiColors.PsyTurquoise, onPrimaryContainer = ChampiColors.PsyNuit,
    secondary = ChampiColors.PsyRose, onSecondary = Color.White,
    background = ChampiColors.PsyViolet, onBackground = Color.White,
    surface = Color(0xFFFFB3E6), onSurface = ChampiColors.PsyNuit,
    surfaceVariant = Color(0xFFC9A7FF), onSurfaceVariant = Color(0xFF5B2A86),
    outline = ChampiColors.PsyNuit, error = Color(0xFFB3261E),
)

/*
 * Typographie : Manrope (titres, chiffres) et Inter (texte).
 * Polices système en attendant les fichiers .ttf intégrés dans res/font (hors ligne).
 */
val TitleFont: FontFamily = FontFamily.SansSerif
val BodyFont: FontFamily = FontFamily.SansSerif

private val ChampiTypography = Typography(
    headlineLarge = TextStyle(fontFamily = TitleFont, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp),
    headlineMedium = TextStyle(fontFamily = TitleFont, fontWeight = FontWeight.Bold, fontSize = 26.sp),
    headlineSmall = TextStyle(fontFamily = TitleFont, fontWeight = FontWeight.Bold, fontSize = 22.sp),
    titleLarge = TextStyle(fontFamily = TitleFont, fontWeight = FontWeight.Bold, fontSize = 18.sp),
    titleMedium = TextStyle(fontFamily = TitleFont, fontWeight = FontWeight.Bold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = BodyFont, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = BodyFont, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = BodyFont, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    labelMedium = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Medium, fontSize = 13.sp),
)

/**
 * Style d'affichage courant, accessible partout via `LocalChampiStyle.current`.
 * Les écrans de sécurité (intoxication, « Je suis perdu », alertes) doivent
 * envelopper leur contenu dans `SafetyTheme { }` pour rester en thème normal.
 */
data class ChampiStyle(
    val psyche: Boolean = false,
    val dark: Boolean = false,
    val emojis: Boolean = false,
    val champignong: Boolean = false,
    val easterUnlocked: Boolean = false,
)

val LocalChampiStyle = staticCompositionLocalOf { ChampiStyle() }

/** Thème racine : choisit clair / sombre / psyché selon les réglages. */
@Composable
fun ChampimapTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val mode = if (settings.themeMode == ThemeMode.PSYCHE && !settings.psycheUnlocked) ThemeMode.AUTO else settings.themeMode
    val psyche = mode == ThemeMode.PSYCHE
    val dark = when (mode) {
        ThemeMode.AUTO -> systemDark
        ThemeMode.SOMBRE -> true
        ThemeMode.CLAIR -> false
        ThemeMode.PSYCHE -> true
    }
    val scheme = when {
        psyche -> PsycheScheme
        dark -> DarkScheme
        else -> LightScheme
    }
    // En mode party, les émojis sont toujours actifs (c'est le principe de la blague).
    val style = ChampiStyle(
        psyche = psyche,
        dark = dark,
        emojis = settings.emojis || psyche,
        champignong = settings.champignong,
        easterUnlocked = settings.easterUnlocked,
    )
    CompositionLocalProvider(LocalChampiStyle provides style) {
        MaterialTheme(colorScheme = scheme, typography = ChampiTypography, content = content)
    }
}

/** Force le thème normal (clair/sombre, sans party ni émojis) pour les écrans de sécurité. */
@Composable
fun SafetyTheme(content: @Composable () -> Unit) {
    val current = LocalChampiStyle.current
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalChampiStyle provides current.copy(psyche = false, emojis = false, champignong = false)) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, typography = ChampiTypography, content = content)
    }
}
