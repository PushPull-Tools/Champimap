package fr.pushpull.champimap.connect.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/*
 * Thème Compose de Champimap Connect : simple, clair, aux couleurs de Champimap
 * (vert mousse #2F5D46, fond papier #F6F5F1, orange champignon pour l'accent).
 */
object ConnectColors {
    val Moss = Color(0xFF2F5D46)
    val MossLight = Color(0xFFDCE8E0)
    val Paper = Color(0xFFF6F5F1)
    val Card = Color(0xFFFFFFFF)
    val Ink = Color(0xFF1E2A23)
    val Muted = Color(0xFF66706A)
    val Orange = Color(0xFFD9822B)
    val Error = Color(0xFFB3261E)
    val ErrorLight = Color(0xFFF9DEDC)
}

private val Scheme = lightColorScheme(
    primary = ConnectColors.Moss,
    onPrimary = Color.White,
    primaryContainer = ConnectColors.MossLight,
    onPrimaryContainer = ConnectColors.Moss,
    secondary = ConnectColors.Orange,
    onSecondary = Color.White,
    background = ConnectColors.Paper,
    onBackground = ConnectColors.Ink,
    surface = ConnectColors.Card,
    onSurface = ConnectColors.Ink,
    surfaceVariant = ConnectColors.MossLight,
    onSurfaceVariant = ConnectColors.Muted,
    error = ConnectColors.Error,
    errorContainer = ConnectColors.ErrorLight,
)

/** Applique le thème Connect à tout l'écran. */
@Composable
fun ConnectTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
