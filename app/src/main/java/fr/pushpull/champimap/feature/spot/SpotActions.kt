package fr.pushpull.champimap.feature.spot

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.pushpull.champimap.core.ui.BUTTON_HEIGHT
import fr.pushpull.champimap.feature.bonus.champiText
import kotlinx.coroutines.launch

/*
 * Actions de la fiche spot :
 *  - « Trouvé ! » : bouton principal pleine largeur (style bouton normal, pas « sélectionné »),
 *    qui ouvre une feuille du bas avec deux grandes tuiles « Photo » / « Liste » ;
 *  - en dessous, une rangée de 3 boutons secondaires compacts (icône + libellé court, une ligne) :
 *    « Spot vide », « Au pied » (Je suis au pied), « Ajuster » (Ajuster la position).
 */

/** Bloc des 4 actions du spot. */
@Composable
fun SpotActions(
    onFound: () -> Unit,
    onEmpty: () -> Unit,
    onAuPied: () -> Unit,
    onAdjust: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onFound,
            modifier = Modifier.fillMaxWidth().heightIn(min = BUTTON_HEIGHT),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Icon(Icons.Filled.Spa, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text(champiText("Trouvé !"), style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallAction("Spot vide", "Spot vide : noter une visite sans récolte", Icons.Filled.Block, onEmpty, Modifier.weight(1f))
            SmallAction("Au pied", "Je suis au pied : nouvelle mesure précise", Icons.Filled.MyLocation, onAuPied, Modifier.weight(1f))
            SmallAction("Ajuster", "Ajuster la position sur la carte", Icons.Filled.OpenWith, onAdjust, Modifier.weight(1f))
        }
    }
}

/** Bouton secondaire compact : icône au-dessus d'un libellé court sur une seule ligne. */
@Composable
private fun SmallAction(text: String, description: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 60.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = description, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Text(
                champiText(text),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Feuille du bas « Trouvé ! » : deux grandes tuiles icône + texte.
 * La feuille se ferme (animation) avant de lancer l'action choisie.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoundSheet(onDismiss: () -> Unit, onPhoto: () -> Unit, onList: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    /** Ferme la feuille puis lance l'action. */
    fun choose(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            action()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Text(
                champiText("Qu'as-tu trouvé ?"),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SheetTile("Photo", "Prends-le en photo", Icons.Filled.PhotoCamera, { choose(onPhoto) }, Modifier.weight(1f))
                SheetTile("Liste", "Choisis l'espèce", Icons.AutoMirrored.Filled.List, { choose(onList) }, Modifier.weight(1f))
            }
        }
    }
}

/** Grande tuile de la feuille du bas. */
@Composable
private fun SheetTile(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 120.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(
            Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(36.dp))
            Text(champiText(title), style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(
                champiText(subtitle),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}
