package fr.pushpull.champimap.feature.spot

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import fr.pushpull.champimap.core.db.Edibility
import fr.pushpull.champimap.core.db.Species
import fr.pushpull.champimap.core.ui.ChampiCard
import fr.pushpull.champimap.core.ui.ChampiOutlinedButton
import fr.pushpull.champimap.core.ui.VSpace
import fr.pushpull.champimap.feature.bonus.champiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/*
 * Écran de résultat de l'identification (après « Trouvé ! → Photo ») :
 *  - bandeau permanent et calme : pistes de l'IA, jamais une preuve de comestibilité ;
 *  - avertissements de SOSIES bien visibles quand une piste comestible a des sosies toxiques/mortels ;
 *  - « Ressemble à… » : les 5 propositions (photo de fiche, noms, pourcentage, lien « Voir la fiche ») ;
 *    toucher = choisir l'espèce. JAMAIS de verdict de comestibilité (ni « Comestible », ni « C'est un… ») ;
 *    seule une piste toxique ou mortelle est fortement marquée (sécurité, pas un verdict) ;
 *  - confiance de la 1re piste < SEUIL_CONFIANCE → « Identification incertaine » en tête, pistes sans chiffres ;
 *  - « Aucune ne correspond → liste complète » ;
 *  - note fixe + section dépliable « Espèces dangereuses que l'IA ne connaît pas encore » ;
 *  - rappel fixe tout en bas (hors défilement) : faire vérifier par un pharmacien ou une société mycologique.
 */

/**
 * Seuil de confiance de la 1re proposition (probabilité softmax, 0 à 1).
 * En dessous de 60 %, l'IA hésite trop : on affiche « Identification incertaine »
 * et on masque les pourcentages (pour ne pas donner une fausse impression de précision).
 */
private const val SEUIL_CONFIANCE = 0.60f

/** Rappel fixe affiché en bas de l'écran (texte validé). */
private const val RAPPEL_BAS =
    "Ne consomme jamais un champignon identifié seulement par l'appli : fais-le vérifier par un pharmacien ou une société mycologique."

/** Bandeau de prudence (texte validé). */
private const val BANDEAU_IA =
    "Pistes proposées par l'IA, jamais une preuve de comestibilité. Fais vérifier en cas de doute (pharmacien, société mycologique)."

/** Vrai si l'espèce est toxique ou mortelle. */
private fun Species.isDangerous(): Boolean = edibility == Edibility.MORTEL || edibility == Edibility.TOXIQUE

/** Pourcentage lisible (« 87 % », « < 1 % »). */
private fun formatPercent(p: Float): String = if (p < 0.01f) "< 1 %" else "${(p * 100).roundToInt()} %"

/**
 * Panneau de résultat.
 * @param proposals null pendant l'analyse, sinon les propositions (meilleure en premier).
 * @param all toutes les espèces (sosies, espèces dangereuses inconnues du modèle).
 */
@Composable
fun IaResultsPanel(
    photo: File,
    proposals: List<Pair<Species, Float>>?,
    all: List<Species>,
    onPick: (Species) -> Unit,
    onOpenSpecies: (String) -> Unit,
    onShowList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val byId = remember(all) { all.associateBy { it.id } }
    // Espèces dangereuses du catalogue absentes du modèle (calcul au chargement, hors fil principal).
    val unknownDangerous by produceState(initialValue = emptyList<Species>(), all) {
        value = withContext(Dispatchers.Default) { unknownDangerousSpecies(all, IaAssets.labelIds(context)) }
    }

    val list = proposals.orEmpty()
    // Identification incertaine : la meilleure piste est sous le seuil de confiance.
    val uncertain = list.isNotEmpty() && list.first().second < SEUIL_CONFIANCE

    // Partie qui défile au-dessus, rappel fixe tout en bas (toujours visible).
    Column(modifier) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PhotoTitle(photo, analysing = proposals == null)
            if (uncertain) UncertainBanner()
            CalmBanner(BANDEAU_IA)

            // Sosies dangereux des pistes comestibles : bien visibles, AVANT la liste.
            list.forEach { (sp, _) ->
                if (sp.edibility == Edibility.COMESTIBLE) {
                    val dangerousLookalikes = sp.lookalikes?.split(',')?.map { it.trim() }
                        ?.mapNotNull { byId[it] }?.filter { it.isDangerous() }.orEmpty()
                    if (dangerousLookalikes.isNotEmpty() || !sp.lookalikeWarning.isNullOrBlank()) {
                        LookalikeWarning(sp, dangerousLookalikes, onOpenSpecies)
                    }
                }
            }

            list.forEach { (sp, p) ->
                ProposalRow(sp, p, showPercent = !uncertain, onOpenFiche = { onOpenSpecies(sp.id) }) { onPick(sp) }
            }

            ChampiOutlinedButton("Aucune ne correspond → liste complète", onClick = onShowList)

            if (unknownDangerous.isNotEmpty()) UnknownDangerousSection(unknownDangerous, onOpenSpecies)
            VSpace(12.dp)
        }
        BottomReminder()
    }
}

/** « Identification incertaine » : explication courte + conseil pour reprendre une photo. */
@Composable
private fun UncertainBanner() {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.secondary),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(champiText("Identification incertaine"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text(
                champiText(
                    "Photo floue, mauvais angle, ou espèce peu connue de l'IA. " +
                        "Reprends une photo du dessus, du dessous et du pied. Les pistes ci-dessous sont données sans chiffres."
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Rappel fixe en bas de l'écran (hors zone de défilement). */
@Composable
private fun BottomReminder() {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(champiText(RAPPEL_BAS), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        }
    }
}

/** Vignette de la photo prise + titre (ou « Analyse de la photo… » avec un indicateur). */
@Composable
private fun PhotoTitle(photo: File, analysing: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(
            model = photo,
            contentDescription = "Photo prise",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(84.dp).clip(RoundedCornerShape(14.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                champiText(if (analysing) "Analyse de la photo…" else "Ressemble à…"),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                champiText(if (analysing) "Quelques secondes, tout se passe sur ton téléphone." else "Touche la bonne espèce pour l'ajouter à ta récolte."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (analysing) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
    }
}

/** Bandeau calme (fond neutre, icône info) : toujours affiché. */
@Composable
private fun CalmBanner(text: String) {
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(champiText(text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Avertissement de sosie (piste comestible avec sosies toxiques ou mortels) : une ligne dépliable. */
@Composable
private fun LookalikeWarning(sp: Species, dangerous: List<Species>, onOpen: (String) -> Unit) {
    SosiesRepliables("Attention aux sosies : ${sp.nameFr}", sp.lookalikeWarning, dangerous, onOpen)
}

/**
 * Une proposition : photo de fiche, noms, pourcentage (si confiance suffisante), lien « Voir la fiche ».
 * Aucune pastille de comestibilité (pas de verdict) ; seule une piste toxique / mortelle garde
 * sa pastille, sa bordure et son texte d'alerte (sécurité).
 */
@Composable
private fun ProposalRow(sp: Species, p: Float, showPercent: Boolean, onOpenFiche: () -> Unit, onClick: () -> Unit) {
    val danger = sp.isDangerous()
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (danger) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (danger) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
        border = if (danger) BorderStroke(2.dp, MaterialTheme.colorScheme.error) else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            SpeciesThumb(sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(champiText(sp.nameFr), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    sp.nameLatin,
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (danger) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            champiText(if (sp.edibility == Edibility.MORTEL) "MORTEL : ne jamais consommer" else "TOXIQUE : ne pas consommer"),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (danger) EdibilityChip(sp.edibility) // TOXIQUE / MORTEL seulement
                if (showPercent) Text(formatPercent(p), style = MaterialTheme.typography.labelLarge)
                // Lien discret vers la fiche complète (comestibilité, sosies, description).
                TextButton(onClick = onOpenFiche, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                    Text(
                        champiText("Voir la fiche"),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/** Première photo de fiche de l'espèce (repli : disque de couleur de l'espèce). */
@Composable
private fun SpeciesThumb(sp: Species) {
    val context = LocalContext.current
    val model by produceState<Any?>(initialValue = null, sp.id) {
        value = withContext(Dispatchers.IO) { loadFichePhotos(context, sp.id).firstOrNull()?.model }
    }
    Box(
        Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val m = model
        if (m != null) {
            AsyncImage(model = m, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp))
        } else {
            SpeciesDot(sp, 20)
        }
    }
}

/** Note fixe + liste dépliable des espèces dangereuses que le modèle ne reconnaît pas. */
@Composable
private fun UnknownDangerousSection(list: List<Species>, onOpen: (String) -> Unit) {
    val examples = list.take(2).joinToString(", ") { it.nameFr.lowercase() }
    ChampiCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                champiText(
                    "Certaines espèces dangereuses ne sont pas reconnues par l'IA, faute de photos (ex. $examples). " +
                        "Une piste rassurante ne les exclut pas."
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Collapsible("Espèces dangereuses que l'IA ne connaît pas encore (${list.size})") {
            list.forEach { o ->
                Surface(
                    onClick = { onOpen(o.id) },
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    Row(Modifier.padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        SpeciesDot(o, 12)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(champiText(o.nameFr), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                o.nameLatin,
                                style = MaterialTheme.typography.bodySmall,
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        EdibilityChip(o.edibility)
                    }
                }
            }
        }
    }
}
