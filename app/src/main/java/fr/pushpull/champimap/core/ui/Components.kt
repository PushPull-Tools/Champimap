package fr.pushpull.champimap.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.pushpull.champimap.core.ui.theme.LocalChampiStyle
import fr.pushpull.champimap.feature.bonus.PsycheBackground
import fr.pushpull.champimap.feature.bonus.champiText
import fr.pushpull.champimap.feature.bonus.psycheCard

/*
 * Composants partagés de la charte : à utiliser dans tous les écrans pour que
 * les thèmes (clair, sombre, psyché) et les options (émojis, champignong)
 * s'appliquent partout automatiquement.
 */

/** Hauteur minimale des boutons (mode forêt, utilisable avec des gants). */
val BUTTON_HEIGHT: Dp = 56.dp

/**
 * Écran standard : fond du thème (animé en psyché), barre avec bouton Retour et titre.
 * `onBack = null` → pas de bouton Retour.
 */
@Composable
fun ChampiScreen(
    title: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    PsycheBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            if (title != null || onBack != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onBack != null) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.size(44.dp),
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                        Spacer(Modifier.width(12.dp))
                    }
                    if (title != null) {
                        Text(
                            champiText(title),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f),
                        )
                    } else Spacer(Modifier.weight(1f))
                    actions()
                }
            }
            Column(Modifier.fillMaxSize(), content = content)
        }
    }
}

/** Bouton principal (vert mousse, ou dégradé en psyché). */
@Composable
fun ChampiButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color? = null) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = BUTTON_HEIGHT),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color ?: MaterialTheme.colorScheme.primary),
    ) { Text(champiText(text), style = MaterialTheme.typography.labelLarge) }
}

/** Bouton secondaire (contour). */
@Composable
fun ChampiOutlinedButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
    ) { Text(champiText(text), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
}

/** Carte (bloc blanc arrondi). En psyché : dégradé animé + bordure arc-en-ciel tournante. */
@Composable
fun ChampiCard(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(16.dp), content: @Composable ColumnScope.() -> Unit) {
    val psyche = LocalChampiStyle.current.psyche
    if (psyche) {
        // Fond pastel : texte et icônes en couleur « sur surface » du thème psyché (sinon blancs).
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Column(modifier.psycheCard().padding(padding), content = content)
        }
    } else {
        Surface(modifier = modifier, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(padding), content = content)
        }
    }
}

/** Pastille (état, qualité…). */
@Composable
fun ChampiChip(text: String, background: Color = MaterialTheme.colorScheme.surfaceVariant, textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Box(
        Modifier.background(background, RoundedCornerShape(15.dp)).padding(horizontal = 11.dp, vertical = 6.dp),
    ) { Text(champiText(text), style = MaterialTheme.typography.labelMedium, color = textColor) }
}

/** Titre de section en petites capitales grises. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        champiText(text).uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, bottom = 6.dp, top = 8.dp),
    )
}

/** Boîte de confirmation (suppression de photo, etc.). */
@Composable
fun ConfirmDialog(
    title: String,
    text: String? = null,
    confirmLabel: String = "Supprimer",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(champiText(title), textAlign = TextAlign.Center) },
        text = text?.let { { Text(champiText(it)) } },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/** Espace vertical standard. */
@Composable
fun VSpace(h: Dp = 12.dp) = Spacer(Modifier.height(h))

/** Colonne avec espacement régulier. */
@Composable
fun Stack(modifier: Modifier = Modifier, gap: Dp = 12.dp, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap), content = content)
