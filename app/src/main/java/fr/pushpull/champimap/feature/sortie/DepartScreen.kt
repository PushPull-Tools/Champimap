package fr.pushpull.champimap.feature.sortie

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.ErrorLogger
import fr.pushpull.champimap.core.db.DepartType
import fr.pushpull.champimap.core.ui.ChampiButton
import fr.pushpull.champimap.core.ui.ChampiCard
import fr.pushpull.champimap.core.ui.ChampiOutlinedButton
import fr.pushpull.champimap.core.ui.ChampiScreen
import fr.pushpull.champimap.core.ui.CircularReveal
import fr.pushpull.champimap.core.ui.RevealMode
import fr.pushpull.champimap.core.ui.trackCenterInRoot
import fr.pushpull.champimap.feature.bonus.SortieTransitions
import fr.pushpull.champimap.feature.bonus.animationsEnabled
import fr.pushpull.champimap.core.ui.SectionTitle
import fr.pushpull.champimap.feature.bonus.champiText
import fr.pushpull.champimap.nav.Routes
import kotlinx.coroutines.launch

/*
 * Écran « Comment tu démarres ? » : garé (la voiture est enregistrée ici) ou à pied.
 * « C'est parti ! » crée la sortie, lance le suivi GPS et ouvre la sortie en cours.
 * « Sortie à plusieurs » fait pareil puis ouvre la création du groupe (QR code).
 * Transition : le fond du thème envahit l'écran en cercle depuis le bouton appuyé,
 * puis l'écran de sortie affiche la phrase de départ et s'ouvre en cercle (StartTransition.kt).
 */

/** Durée du cercle qui couvre l'écran Départ. */
private const val COVER_MS = 500

@Composable
fun DepartScreen(nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val permissions = rememberSortiePermissions()
    val current by remember { AppGraph.db.sortieDao().current() }.collectAsState(initial = null)
    var type by remember { mutableStateOf(DepartType.CAR) }
    var busy by remember { mutableStateOf(false) }
    // Écoute du GPS dès l'ouverture : la position de départ sera prête au moment d'appuyer.
    val fix by rememberLiveFix()
    // Transition : disque qui grandit depuis le bouton appuyé (0 = rien, 1 = écran couvert).
    val anim = animationsEnabled()
    val cover = remember { Animatable(0f) }
    var origin by remember { mutableStateOf<Offset?>(null) }
    var startOrigin by remember { mutableStateOf<Offset?>(null) }
    var groupOrigin by remember { mutableStateOf<Offset?>(null) }

    /** Crée la sortie puis navigue (vers la sortie, et éventuellement le groupe). */
    fun go(withGroup: Boolean) {
        if (busy) return
        busy = true
        permissions.request { _ ->
            scope.launch {
                try {
                    // Le cercle grandit pendant la création de la sortie (en parallèle).
                    origin = if (withGroup) groupOrigin else startOrigin
                    val grow = if (anim) launch { cover.animateTo(1f, tween(COVER_MS, easing = FastOutSlowInEasing)) } else null
                    val sortie = SortieActions.createSortie(type, fix ?: LocationUtils.lastKnown(context))
                    SortieActions.startTracking(context, sortie.id)
                    // Page de transition (phrase de départ + ouverture en cercle) à l'ouverture de la nouvelle sortie.
                    SortieTransitions.start.request(sortie.id)
                    grow?.join()
                    nav.navigate(Routes.sortie(sortie.id)) { popUpTo(Routes.DEPART) { inclusive = true } }
                    if (withGroup) nav.navigate(Routes.groupeCreer(sortie.id))
                } catch (e: Exception) {
                    ErrorLogger.log("Départ", "Création de la sortie impossible", e)
                    cover.snapTo(0f)
                } finally {
                    busy = false
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        ChampiScreen(title = "Comment tu démarres ?", onBack = { nav.popBackStack() }) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Une sortie est déjà en cours : on propose de la reprendre.
                current?.let { s ->
                    ChampiCard(Modifier.fillMaxWidth()) {
                        Text(champiText("Une sortie est déjà en cours."), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.size(8.dp))
                        ChampiButton("Reprendre la sortie", onClick = {
                            nav.navigate(Routes.sortie(s.id)) { popUpTo(Routes.DEPART) { inclusive = true } }
                        })
                    }
                }

                DepartOption(
                    icon = Icons.Filled.DirectionsCar,
                    title = "Je suis garé",
                    text = "On enregistre ta voiture ici, puis tes déplacements. Une flèche te ramènera à elle.",
                    selected = type == DepartType.CAR,
                    onClick = { type = DepartType.CAR },
                )
                DepartOption(
                    icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                    title = "Je suis à pied",
                    text = "On enregistre tes déplacements directement, depuis ton point de départ.",
                    selected = type == DepartType.FOOT,
                    onClick = { type = DepartType.FOOT },
                )

                // État du GPS au moment du départ.
                Text(
                    champiText(
                        when {
                            !LocationUtils.hasFineLocation(context) -> "La position sera demandée au départ."
                            fix == null -> "Recherche du GPS… tu peux partir, le départ sera enregistré dès le premier point."
                            else -> "GPS ±${fix!!.accuracyM.toInt()} m : prêt."
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )

                SectionTitle("À plusieurs")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ChampiOutlinedButton(
                        "Sortie à plusieurs",
                        onClick = { go(withGroup = true) },
                        modifier = Modifier.weight(1f).trackCenterInRoot { groupOrigin = it },
                        enabled = !busy,
                    )
                    ChampiOutlinedButton("Rejoindre", onClick = { nav.navigate(Routes.GROUPE_REJOINDRE) }, modifier = Modifier.weight(1f))
                }
            }
            Column(Modifier.padding(16.dp)) {
                ChampiButton(
                    "C'est parti !",
                    onClick = { go(withGroup = false) },
                    modifier = Modifier.trackCenterInRoot { startOrigin = it },
                    enabled = !busy,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
        // Disque de transition par-dessus l'écran (seulement pendant le départ).
        if (cover.value > 0f) {
            CircularReveal(progress = { cover.value }, mode = RevealMode.DISC, originInRoot = origin)
        }
    }
}

/** Carte de choix (garé / à pied), bordure épaisse quand elle est choisie. */
@Composable
private fun DepartOption(icon: ImageVector, title: String, text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (selected) 2.5.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(champiText(title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(champiText(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
