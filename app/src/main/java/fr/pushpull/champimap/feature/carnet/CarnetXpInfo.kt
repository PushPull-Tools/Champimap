package fr.pushpull.champimap.feature.carnet

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.pushpull.champimap.feature.bonus.champiText

/*
 * Bouton « i » du carnet et sa petite infobulle « Ce qui rapporte de l'XP ».
 * Infobulle compacte (menu déroulant ancré sous le bouton) : libellés courts à gauche,
 * montants alignés en colonne à droite. Les montants sont lus DIRECTEMENT dans CarnetXp et
 * BadgeTier : l'infobulle reste juste même si le barème change.
 */

/** Largeur de l'infobulle. */
private val LARGEUR = 232.dp

/** Largeur de la colonne des montants (alignés à droite). */
private val COLONNE_XP = 64.dp

/** Barème des actions : libellé court → gain. */
private fun lignesActions(): List<Pair<String, Int>> = listOf(
    "Sortie terminée" to CarnetXp.XP_SORTIE,
    "Sortie à plusieurs (en plus)" to CarnetXp.XP_PLUSIEURS,
    "Nouveau spot" to CarnetXp.XP_NOUVEAU_SPOT,
    "Nouvelle espèce" to CarnetXp.XP_NOUVELLE_ESPECE,
    "100 g pesés (max ${CarnetXp.XP_PESEE_MAX_JOUR}/j)" to CarnetXp.XP_PAR_100G,
    "Spot vide (1 par spot/j)" to CarnetXp.XP_SPOT_VIDE,
    "Photo d'espèce (max ${CarnetXp.PHOTOS_MAX_JOUR}/j)" to CarnetXp.XP_PHOTO,
    "Spot qui redonne (1 par an)" to CarnetXp.XP_SPOT_REDONNE,
)

/** Barème des succès : difficulté → gain. */
private fun lignesSucces(): List<Pair<String, Int>> =
    BadgeTier.entries.map { (CarnetTextes.DIFFICULTES[it] ?: it.name) to it.xp }

/** Bouton « i » de la barre du carnet, qui ouvre (et porte) l'infobulle du barème. */
@Composable
fun CarnetXpInfoButton() {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Outlined.Info, contentDescription = CarnetTextes.INFO_XP_TITRE)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.width(LARGEUR).padding(horizontal = 12.dp, vertical = 4.dp)) {
                Text(
                    champiText(CarnetTextes.INFO_XP_TITRE),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                lignesActions().forEach { (label, xp) -> XpInfoRow(label, xp) }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(
                    champiText(CarnetTextes.INFO_XP_SUCCES_TITRE),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                lignesSucces().forEach { (label, xp) -> XpInfoRow(label, xp) }
                Text(
                    champiText(CarnetTextes.INFO_XP_SUCCES),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** Une ligne serrée : libellé à gauche, « +N XP » aligné à droite dans sa colonne. */
@Composable
private fun XpInfoRow(label: String, xp: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(champiText(label), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(
            "+$xp XP",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.End,
            modifier = Modifier.width(COLONNE_XP),
        )
    }
}
