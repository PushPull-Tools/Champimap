package fr.pushpull.champimap.feature.carnet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.pushpull.champimap.feature.bonus.champiText

/*
 * Bouton « i » du carnet et sa fenêtre « Ce qui rapporte de l'XP ».
 * Les montants sont lus DIRECTEMENT dans CarnetXp (constantes du barème) : la fenêtre reste juste
 * même si le barème change. Les noms des sources viennent de CarnetTextes.SOURCES.
 */

/** Une ligne de la fenêtre : source, gain affiché, précision (plafonds, conditions). */
private data class XpInfoLigne(val source: XpSource, val gain: String, val detail: String)

/** Barème complet, dans l'ordre d'affichage, construit à partir des constantes de CarnetXp. */
private fun xpInfoLignes(): List<XpInfoLigne> = listOf(
    XpInfoLigne(XpSource.SORTIE, "+${CarnetXp.XP_SORTIE} XP", "par sortie terminée"),
    XpInfoLigne(XpSource.PLUSIEURS, "+${CarnetXp.XP_PLUSIEURS} XP", "en plus, pour une sortie à plusieurs"),
    XpInfoLigne(XpSource.NOUVEAU_SPOT, "+${CarnetXp.XP_NOUVEAU_SPOT} XP", "par spot noté"),
    XpInfoLigne(XpSource.NOUVELLE_ESPECE, "+${CarnetXp.XP_NOUVELLE_ESPECE} XP", "la première fois que tu trouves une espèce"),
    XpInfoLigne(
        XpSource.PESEE,
        "+${CarnetXp.XP_PAR_100G} XP",
        "par tranche de 100 g pesés, ${CarnetXp.XP_PESEE_MAX_JOUR} XP au plus par jour",
    ),
    XpInfoLigne(XpSource.SPOT_VIDE, "+${CarnetXp.XP_SPOT_VIDE} XP", "par spot vide noté, une fois par spot et par jour"),
    XpInfoLigne(
        XpSource.PHOTO,
        "+${CarnetXp.XP_PHOTO} XP",
        "par photo d'espèce, ${CarnetXp.PHOTOS_MAX_JOUR} photos au plus par jour " +
            "(${CarnetXp.XP_PHOTO * CarnetXp.PHOTOS_MAX_JOUR} XP)",
    ),
    XpInfoLigne(
        XpSource.SPOT_REDONNE,
        "+${CarnetXp.XP_SPOT_REDONNE} XP",
        "quand un spot redonne une nouvelle année (une fois par spot et par année)",
    ),
)

/** Bouton « i » de la barre du carnet. */
@Composable
fun CarnetXpInfoButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Outlined.Info, contentDescription = CarnetTextes.INFO_XP_TITRE)
    }
}

/** Fenêtre « Ce qui rapporte de l'XP ». */
@Composable
fun CarnetXpInfoDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(champiText(CarnetTextes.INFO_XP_TITRE), textAlign = TextAlign.Center) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                xpInfoLignes().forEach { ligne -> XpInfoRow(ligne) }
                Text(
                    champiText(CarnetTextes.INFO_XP_SUCCES),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(CarnetTextes.OK) } },
    )
}

/** Une ligne : nom de la source et précision à gauche, gain à droite. */
@Composable
private fun XpInfoRow(ligne: XpInfoLigne) {
    val nom = CarnetTextes.SOURCES[ligne.source]?.nom ?: ligne.source.name
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(champiText(nom), style = MaterialTheme.typography.bodyMedium)
            Text(
                champiText(ligne.detail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(ligne.gain, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
}
