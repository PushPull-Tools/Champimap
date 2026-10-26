package fr.pushpull.champimap.feature.carnet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.pushpull.champimap.core.settings.MessageTone
import fr.pushpull.champimap.feature.bonus.champiText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Petits composants du carnet : barre d'XP, ligne compacte de l'accueil, picto de succès
 * (dessiné simplement en Compose, un par famille), tuile de succès et fenêtre de détail.
 * Tous les textes viennent de CarnetTextes.kt et passent par champiText (émojis, champignong).
 */

private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRANCE)

/** Date lisible d'un succès : « 12 octobre 2026 ». */
internal fun badgeDate(time: Long): String = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(DATE_FMT)

/** Nombre formaté à la française : « 12 450 ». */
internal fun fmtXp(v: Long): String = String.format(Locale.FRANCE, "%,d", v).replace(' ', ' ').replace(' ', ' ')

// ====================================================================== Barre d'XP

/** Barre d'avancement dans le niveau (arrondie, couleur girolle sur fond discret). */
@Composable
fun XpBar(progress: Float, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(scheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(height)
                .clip(RoundedCornerShape(height / 2))
                .background(scheme.secondary),
        )
    }
}

/**
 * Ligne compacte de l'accueil : « Niveau 7 · Apprenti cueilleur » + barre d'XP.
 * Appui → écran « Mon carnet ».
 */
@Composable
fun CarnetHomeLine(level: LevelInfo, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                champiText("${CarnetTextes.NIVEAU} ${level.level}"),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                " · " + champiText(level.titleWithStars),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(4.dp))
        XpBar(level.progress, Modifier.fillMaxWidth(0.7f), height = 6.dp)
    }
}

// ====================================================================== Pictos de succès

/** Couleur d'une famille de succès (picto obtenu). */
internal fun familyColor(f: BadgeFamily): Color = when (f) {
    BadgeFamily.PREMIERES -> Color(0xFF5E9C3A)
    BadgeFamily.COLLECTION -> Color(0xFF9A5B2E)
    BadgeFamily.SAISONS -> Color(0xFFD9822B)
    BadgeFamily.FIDELITE -> Color(0xFF2F5D46)
    BadgeFamily.EXPLORATEUR -> Color(0xFF3B6EA8)
    BadgeFamily.METEO -> Color(0xFF4A90C8)
    BadgeFamily.PERSEVERANCE -> Color(0xFF8A5BB8)
    BadgeFamily.PRECISION -> Color(0xFFC0392B)
    BadgeFamily.SECRETS -> Color(0xFFD4A017)
}

/**
 * Picto d'un succès : médaille ronde avec le dessin de sa famille.
 * Non obtenu : silhouette grise et « ? » par-dessus.
 */
@Composable
fun BadgePicto(family: BadgeFamily, unlocked: Boolean, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    val scheme = MaterialTheme.colorScheme
    val main = if (unlocked) familyColor(family) else scheme.onSurfaceVariant.copy(alpha = 0.35f)
    val back = if (unlocked) familyColor(family).copy(alpha = 0.16f) else scheme.surfaceVariant
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().semantics { contentDescription = if (unlocked) "Succès obtenu" else "Succès à découvrir" }) {
            val s = this.size.minDimension / 100f
            drawCircle(back, radius = 48f * s, center = center)
            drawCircle(main, radius = 46f * s, center = center, style = Stroke(width = 3f * s))
            drawFamily(family, main, s)
        }
        if (!unlocked) {
            Text(
                "?",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/** Dessine le motif d'une famille dans un repère 100 × 100 (facteur d'échelle [s]). */
private fun DrawScope.drawFamily(f: BadgeFamily, c: Color, s: Float) {
    fun o(x: Float, y: Float) = Offset(x * s, y * s)
    when (f) {
        // Pousse : tige et deux feuilles.
        BadgeFamily.PREMIERES -> {
            drawLine(c, o(50f, 75f), o(50f, 45f), 5f * s, StrokeCap.Round)
            drawOval(c, topLeft = o(26f, 34f), size = Size(24f * s, 13f * s))
            drawOval(c, topLeft = o(50f, 28f), size = Size(24f * s, 13f * s))
        }
        // Champignon : chapeau en dôme et pied.
        BadgeFamily.COLLECTION -> {
            drawRoundRect(c, topLeft = o(43f, 48f), size = Size(14f * s, 26f * s), cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f * s))
            drawArc(c, startAngle = 180f, sweepAngle = 180f, useCenter = true, topLeft = o(24f, 28f), size = Size(52f * s, 44f * s))
        }
        // Feuille : losange arrondi et nervure.
        BadgeFamily.SAISONS -> {
            val p = Path().apply {
                moveTo(28f * s, 72f * s)
                cubicTo(28f * s, 40f * s, 50f * s, 26f * s, 74f * s, 26f * s)
                cubicTo(74f * s, 52f * s, 58f * s, 72f * s, 28f * s, 72f * s)
                close()
            }
            drawPath(p, c)
            drawLine(Color.White.copy(alpha = 0.7f), o(30f, 70f), o(66f, 34f), 2.5f * s, StrokeCap.Round)
        }
        // Arbre : sapin et tronc.
        BadgeFamily.FIDELITE -> {
            drawRect(c, topLeft = o(46f, 64f), size = Size(8f * s, 12f * s))
            val p = Path().apply {
                moveTo(50f * s, 22f * s); lineTo(72f * s, 52f * s); lineTo(62f * s, 52f * s)
                lineTo(76f * s, 68f * s); lineTo(24f * s, 68f * s); lineTo(38f * s, 52f * s)
                lineTo(28f * s, 52f * s); close()
            }
            drawPath(p, c)
        }
        // Boussole : aiguille en losange.
        BadgeFamily.EXPLORATEUR -> {
            drawCircle(c, radius = 26f * s, center = o(50f, 50f), style = Stroke(width = 3f * s))
            val north = Path().apply { moveTo(50f * s, 28f * s); lineTo(57f * s, 50f * s); lineTo(43f * s, 50f * s); close() }
            val south = Path().apply { moveTo(50f * s, 72f * s); lineTo(57f * s, 50f * s); lineTo(43f * s, 50f * s); close() }
            drawPath(north, c)
            drawPath(south, c.copy(alpha = 0.45f))
        }
        // Goutte de pluie.
        BadgeFamily.METEO -> {
            val p = Path().apply {
                moveTo(50f * s, 24f * s)
                cubicTo(62f * s, 42f * s, 70f * s, 52f * s, 70f * s, 60f * s)
                cubicTo(70f * s, 72f * s, 61f * s, 78f * s, 50f * s, 78f * s)
                cubicTo(39f * s, 78f * s, 30f * s, 72f * s, 30f * s, 60f * s)
                cubicTo(30f * s, 52f * s, 38f * s, 42f * s, 50f * s, 24f * s)
                close()
            }
            drawPath(p, c)
        }
        // Drapeau planté au sommet.
        BadgeFamily.PERSEVERANCE -> {
            drawLine(c, o(36f, 76f), o(36f, 24f), 4f * s, StrokeCap.Round)
            val p = Path().apply { moveTo(38f * s, 26f * s); lineTo(70f * s, 35f * s); lineTo(38f * s, 46f * s); close() }
            drawPath(p, c)
            drawLine(c, o(24f, 76f), o(76f, 76f), 4f * s, StrokeCap.Round)
        }
        // Cible : cercles concentriques.
        BadgeFamily.PRECISION -> {
            drawCircle(c, radius = 26f * s, center = o(50f, 50f), style = Stroke(width = 4f * s))
            drawCircle(c, radius = 15f * s, center = o(50f, 50f), style = Stroke(width = 4f * s))
            drawCircle(c, radius = 5f * s, center = o(50f, 50f))
        }
        // Étoile à cinq branches.
        BadgeFamily.SECRETS -> {
            val p = Path()
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) 28f else 12f
                val a = Math.toRadians((-90 + i * 36).toDouble())
                val x = 50f + r * kotlin.math.cos(a).toFloat()
                val y = 52f + r * kotlin.math.sin(a).toFloat()
                if (i == 0) p.moveTo(x * s, y * s) else p.lineTo(x * s, y * s)
            }
            p.close()
            drawPath(p, c)
        }
    }
}

// ====================================================================== Tuile et détail d'un succès

/** Tuile de la grille : picto, nom (ou « ? »), date d'obtention ; pastille « Nouveau ». */
@Composable
fun BadgeTile(def: BadgeDef, unlockedAt: Long?, isNew: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val unlocked = unlockedAt != null
    val textes = CarnetTextes.succes(def.id)
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            BadgePicto(def.family, unlocked)
            if (isNew) {
                Box(
                    Modifier.align(Alignment.TopEnd).size(12.dp).background(MaterialTheme.colorScheme.secondary, CircleShape),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            champiText(if (unlocked) textes.nom else "?"),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            color = if (unlocked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (unlockedAt != null) {
            Text(
                badgeDate(unlockedAt),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Fenêtre de détail d'un succès. Obtenu : nom, date, condition, message dans le ton choisi.
 * Non obtenu : condition seulement (le nom reste une surprise).
 */
@Composable
fun BadgeDialog(def: BadgeDef, unlockedAt: Long?, tone: MessageTone, onDismiss: () -> Unit) {
    val t = CarnetTextes.succes(def.id)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { BadgePicto(def.family, unlockedAt != null, size = 64.dp) },
        title = { Text(champiText(if (unlockedAt != null) t.nom else "?"), textAlign = TextAlign.Center) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (unlockedAt != null) {
                    Text(champiText(t.message.pour(tone)), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        champiText(t.condition),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${CarnetTextes.OBTENU_LE} ${badgeDate(unlockedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(champiText(t.condition), textAlign = TextAlign.Center)
                    Text(champiText(CarnetTextes.PAS_ENCORE), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(CarnetTextes.OK) } },
    )
}

/** Espace horizontal (petit utilitaire local). */
@Composable
internal fun HGap(w: Dp = 8.dp) = Spacer(Modifier.width(w))
