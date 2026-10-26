package fr.pushpull.champimap.feature.sortie

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.pushpull.champimap.core.ui.BUTTON_HEIGHT
import fr.pushpull.champimap.feature.bonus.champiText
import kotlin.math.roundToInt

/*
 * Fenêtre de mesure précise, prête à l'emploi (sortie : « Marquer un spot » ;
 * module spot : « Je suis au pied »). Démarre la mesure à l'ouverture, l'arrête à la fermeture.
 */

/** Échantillonneur lié à l'écran : arrêté automatiquement quand l'écran disparaît. */
@Composable
fun rememberPrecisePositionSampler(): PrecisePositionSampler {
    val context = LocalContext.current
    val sampler = remember { PrecisePositionSampler(context.applicationContext) }
    DisposableEffect(sampler) { onDispose { sampler.stop() } }
    return sampler
}

/**
 * Mesure précise avec jauge « ±12 m → ±3 m » en direct et validation manuelle.
 * @param title titre de la fenêtre.
 * @param validateLabel texte du bouton de validation.
 * @param onValidate appelé avec la position moyenne retenue.
 * @param onDismiss fermeture sans rien garder.
 */
@Composable
fun PrecisePositionDialog(
    title: String = "Marquer un spot",
    validateLabel: String = "Valider la position",
    onValidate: (PrecisePosition) -> Unit,
    onDismiss: () -> Unit,
) {
    val sampler = rememberPrecisePositionSampler()
    var gpsUnavailable by remember { mutableStateOf(false) }
    DisposableEffect(sampler) {
        gpsUnavailable = !sampler.start()
        onDispose { sampler.stop() }
    }
    val st by sampler.state.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(champiText(title), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (gpsUnavailable) {
                    Text(champiText("Le GPS n'est pas disponible. Autorise la position précise dans les paramètres du téléphone."))
                } else {
                    SamplerBody(st)
                }
            }
        },
        confirmButton = {
            val result = st.result
            Button(
                onClick = { if (result != null) { sampler.stop(); onValidate(result) } },
                enabled = result != null,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.heightIn(min = BUTTON_HEIGHT),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (st.ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                ),
            ) { Text(champiText(if (st.ready || result == null) validateLabel else "Valider quand même")) }
        },
        dismissButton = {
            Row {
                if (!st.running && !gpsUnavailable) {
                    TextButton(onClick = { gpsUnavailable = !sampler.start() }) { Text(champiText("Recommencer")) }
                }
                TextButton(onClick = onDismiss) { Text(champiText("Annuler")) }
            }
        },
    )
}

/** Contenu de la mesure : consigne, jauge de précision, durée, avertissements. */
@Composable
private fun SamplerBody(st: SamplerState) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            champiText("Reste immobile au pied du spot, le téléphone à plat devant toi."),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        // Jauge : précision de départ → précision actuelle.
        val first = st.firstAccuracyM
        val now = st.currentAccuracyM
        Text(
            when {
                first == null -> champiText("Recherche du GPS…")
                now == null -> "±${first.roundToInt()} m"
                else -> "±${first.roundToInt()} m → ±${now.roundToInt()} m"
            },
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = accuracyColor(now),
        )
        LinearProgressIndicator(
            progress = { accuracyGauge(now) },
            modifier = Modifier.fillMaxWidth().height(10.dp),
            color = accuracyColor(now),
        )
        // Durée : 30 s conseillées, 60 s maximum.
        val seconds = (st.elapsedMs / 1000).toInt()
        LinearProgressIndicator(
            progress = { st.progress },
            modifier = Modifier.fillMaxWidth().height(4.dp),
        )
        Text(
            champiText(
                when {
                    st.finished -> "Mesure terminée · ${st.kept} positions"
                    st.ready -> "$seconds s · tu peux valider (jusqu'à 60 s pour affiner)"
                    else -> "$seconds s sur 30 s conseillées · ${st.kept} positions"
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (st.rejected > 0) {
            Text(
                champiText("${st.rejected} position(s) aberrante(s) écartée(s)"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (st.moving) {
            Text(
                champiText("Tu sembles bouger : reste immobile pour une mesure juste."),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Remplissage de la jauge : 20 m et plus = vide, 2 m = plein. */
private fun accuracyGauge(acc: Float?): Float =
    if (acc == null) 0f else ((20f - acc) / 18f).coerceIn(0f, 1f)

/** Couleur selon la précision : vert ≤ 5 m, orange ≤ 10 m, rouge au-delà. */
@Composable
private fun accuracyColor(acc: Float?): Color = when {
    acc == null -> MaterialTheme.colorScheme.onSurfaceVariant
    acc <= 5f -> Color(0xFF2E7D4F)
    acc <= 10f -> Color(0xFFE08A2E)
    else -> MaterialTheme.colorScheme.error
}
