package fr.pushpull.champimap.feature.bonus

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.ErrorLogger
import fr.pushpull.champimap.core.ui.ChampiButton
import fr.pushpull.champimap.core.ui.ChampiCard
import fr.pushpull.champimap.core.ui.ChampiScreen
import fr.pushpull.champimap.core.ui.SectionTitle
import kotlinx.coroutines.launch

/*
 * Écran de l'easter egg (route AVATAR, ouverte par le bouton invisible de l'accueil) :
 * « Oh, tu m'as trouvé ! », choix d'un des 6 champignons illustrés comme avatar.
 * « C'est lui ! » enregistre l'avatar, débloque les pictos (arbres et animaux) et revient.
 * Une fois l'easter egg débloqué, les arbres et les animaux s'ajoutent sous les champignons,
 * au même endroit et sélectionnables de la même façon (simple contenu en plus).
 */

@Composable
fun AvatarScreen(nav: NavController) {
    val settings by AppGraph.settings.flow.collectAsState(initial = null)
    val unlocked = settings?.easterUnlocked == true
    // Pictos proposés : les champignons, plus les arbres et les animaux une fois débloqués.
    val choices = if (unlocked) Pictos.MUSHROOMS + Pictos.TREES + Pictos.ANIMALS else Pictos.MUSHROOMS
    // Sélection en cours : l'avatar déjà choisi, sinon aucun.
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val current = selected ?: settings?.avatarKey?.takeIf { it in choices }

    ChampiScreen(title = "Oh, tu m'as trouvé !", onBack = { nav.popBackStack() }) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ChampiCard(Modifier.fillMaxWidth()) {
                Text(
                    champiText(
                        if (unlocked) "Choisis ton picto (champignon, arbre ou animal) : il t'accompagnera sur l'accueil."
                        else "Tu as trouvé le coin secret ! Choisis ton champignon : il t'accompagnera sur l'accueil.",
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            PictoGrid(Pictos.MUSHROOMS, current) { selected = it }
            // Contenu en plus une fois l'easter egg débloqué : mêmes tuiles, même sélection.
            if (unlocked) {
                SectionTitle("Arbres")
                PictoGrid(Pictos.TREES, current) { selected = it }
                SectionTitle("Animaux")
                PictoGrid(Pictos.ANIMALS, current) { selected = it }
            }

            ChampiButton(
                text = "C'est lui !",
                enabled = current != null,
                onClick = {
                    val key = current ?: return@ChampiButton
                    // Enregistré dans la portée de l'appli : survit à la fermeture de l'écran.
                    AppGraph.appScope.launch {
                        runCatching {
                            AppGraph.settings.setAvatarKey(key)
                            AppGraph.settings.setEasterUnlocked(true)
                        }.onFailure { ErrorLogger.log("Avatar", "Enregistrement de l'avatar impossible", it) }
                    }
                    nav.popBackStack()
                },
            )

            Spacer(Modifier.size(8.dp))
        }
    }
}

/** Grille de pictos, 3 par ligne ; la tuile choisie est entourée. */
@Composable
private fun PictoGrid(keys: List<String>, selected: String?, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        keys.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { key ->
                    PictoTile(key, key == selected, Modifier.weight(1f)) { onSelect(key) }
                }
                // Cases vides pour garder des colonnes alignées sur la dernière ligne.
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Tuile d'un picto (champignon, arbre ou animal) : grand picto + nom. */
@Composable
private fun PictoTile(key: String, isSelected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .background(if (isSelected) colors.primaryContainer else colors.surface, shape)
            .border(
                BorderStroke(if (isSelected) 3.dp else 1.dp, if (isSelected) colors.primary else colors.outline),
                shape,
            )
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Picto(key, Modifier.size(64.dp))
        Text(
            Pictos.label(key),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) colors.onPrimaryContainer else colors.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
