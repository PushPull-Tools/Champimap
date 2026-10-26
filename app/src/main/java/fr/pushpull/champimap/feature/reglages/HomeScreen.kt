package fr.pushpull.champimap.feature.reglages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.ErrorLogger
import fr.pushpull.champimap.core.db.Battue
import fr.pushpull.champimap.core.db.Sortie
import fr.pushpull.champimap.core.settings.AppSettings
import fr.pushpull.champimap.core.ui.ChampiCard
import fr.pushpull.champimap.core.ui.ChampiScreen
import fr.pushpull.champimap.core.ui.SectionTitle
import fr.pushpull.champimap.core.ui.VSpace
import fr.pushpull.champimap.core.ui.theme.ChampiColors
import fr.pushpull.champimap.core.ui.theme.SafetyTheme
import fr.pushpull.champimap.core.util.AstroCalc
import fr.pushpull.champimap.feature.bonus.Picto
import fr.pushpull.champimap.feature.bonus.StartMessages
import fr.pushpull.champimap.feature.bonus.champiText
import fr.pushpull.champimap.feature.stats.SeasonAlertScheduler
import fr.pushpull.champimap.nav.Routes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Accueil : salutation (+ bouton invisible de l'easter egg), date / coucher du soleil / lune,
 * carte verte de départ, bandeaux chasse et battue, chiffres de la saison, dernières sorties.
 */

/** Écran d'accueil. Signature appelée par AppNav. */
@Composable
fun HomeScreen(nav: NavController) {
    val context = LocalContext.current
    val settings by AppGraph.settings.flow.collectAsState(initial = null)
    val s = settings ?: return
    val today = remember { LocalDate.now() }
    val since = remember { HomeLogic.seasonStartMillis(today) }

    // Données observées en direct.
    val dao = AppGraph.db.backupDao()
    val current by remember { AppGraph.db.sortieDao().current() }.collectAsState(initial = null)
    val sorties by remember { AppGraph.db.sortieDao().all() }.collectAsState(initial = emptyList())
    val weights by remember { dao.sortieWeights() }.collectAsState(initial = emptyList())
    val seasonG by remember { dao.seasonWeightG(since) }.collectAsState(initial = 0L)
    val seasonSorties by remember { dao.seasonSorties(since) }.collectAsState(initial = 0)
    val seasonSpots by remember { dao.seasonSpots(since) }.collectAsState(initial = 0)
    val lastStart by remember { dao.lastSortieStart() }.collectAsState(initial = null)
    var battues by remember { mutableStateOf(emptyList<Battue>()) }

    // Message de départ (pioche du module bonus), tiré une fois par affichage.
    var message by rememberSaveable { mutableStateOf("") }
    var huntHiddenAt by remember { mutableLongStateOf(HomeLogic.huntHiddenAt(context)) }

    LaunchedEffect(Unit) {
        // Demande « Ouvrir les paramètres » venue du premier lancement.
        if (HomeLogic.consumeOpenConnect(context)) nav.navigate(Routes.CONNECT)
        // Alertes de saison (WorkManager) : la planification est idempotente côté module stats.
        runCatching { SeasonAlertScheduler.schedule(context) }
            .onFailure { ErrorLogger.log("Accueil", "Planification des alertes de saison impossible", it) }
        if (message.isEmpty()) {
            message = runCatching { StartMessages.next() }.getOrDefault("Je te souhaite une bonne cueillette !")
        }
        battues = runCatching { AppGraph.db.battueDao().on(today.toString()) }.getOrDefault(emptyList())
    }

    // Position approximative → coucher du soleil et département (calcul local, jamais journalisé).
    val position = remember(s.departements) { HomeLogic.approxPosition(context, s.departements) }
    val sunset = remember(position) { HomeLogic.sunsetLabel(today, position.first, position.second) }
    val moon = remember { AstroCalc.moonPhase(today) }
    val departement = remember(position) { Departements.plusProche(position.first, position.second) }

    ChampiScreen {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            VSpace(8.dp)
            Greeting(s, onSecret = { nav.navigate(Routes.AVATAR) })
            InfoLine(today, sunset, moon)
            StartCard(
                message = message,
                current = current,
                onStart = { nav.navigate(Routes.DEPART) },
                onResume = { id -> nav.navigate(Routes.sortie(id)) },
            )
            if (battues.isNotEmpty()) BattueBanner(battues)
            if (HomeLogic.isHuntingPeriod(today) && HomeLogic.huntBannerVisible(huntHiddenAt, lastStart)) {
                HuntBanner(
                    onArretes = { openInBrowser(context, HomeLogic.arretesUrl(today, departement), "Accueil") },
                    onHide = {
                        HomeLogic.hideHunt(context)
                        huntHiddenAt = HomeLogic.huntHiddenAt(context)
                    },
                )
            }
            SeasonTiles(seasonG, seasonSorties, seasonSpots)
            LastSorties(
                sorties = sorties.filter { it.endedAt != null }.take(5),
                weights = weights.associate { it.sortieId to it.weightG },
                easter = s.easterUnlocked,
                onCalendar = { nav.navigate(Routes.CALENDRIER) },
                onOpen = { id -> nav.navigate(Routes.fin(id)) },
            )
            VSpace(16.dp)
        }
    }
}

/** « Bonjour <prénom> » + bouton invisible (easter egg) + avatar choisi. */
@Composable
private fun Greeting(s: AppSettings, onSecret: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            champiText("Bonjour ${s.firstName}"),
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(6.dp))
        // Bouton invisible : transparent, 36 dp, seul indice = description d'accessibilité.
        Box(
            Modifier
                .size(36.dp)
                .background(Color.Transparent)
                .semantics { contentDescription = "Choisir mon champignon" }
                .clickable(onClick = onSecret),
            contentAlignment = Alignment.Center,
        ) {
            if (s.easterUnlocked && s.avatarKey.isNotEmpty()) Picto(s.avatarKey, Modifier.size(32.dp))
        }
    }
}

/** Ligne : date · coucher du soleil · phase de lune. */
@Composable
private fun InfoLine(today: LocalDate, sunset: String?, moon: AstroCalc.MoonPhase) {
    val parts = buildList {
        add(HomeLogic.dayLabel(today).replaceFirstChar { it.titlecase(Locale.FRANCE) })
        if (sunset != null) add("coucher du soleil $sunset")
        add("${moon.emoji} ${moon.label}")
    }
    Text(
        champiText(parts.joinToString(" · ")),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Carte verte : message centré, Démarrer / Reprendre la sortie, sous-texte centré. */
@Composable
private fun StartCard(message: String, current: Sortie?, onStart: () -> Unit, onResume: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .background(scheme.primary, RoundedCornerShape(22.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            champiText(message),
            style = MaterialTheme.typography.titleLarge,
            color = scheme.onPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        VSpace(16.dp)
        if (current != null) {
            BigButton("Reprendre la sortie", Icons.Filled.PlayArrow) { onResume(current.id) }
        } else {
            BigButton("Démarrer une sortie", Icons.Filled.Explore, onStart)
            VSpace(10.dp)
            Text(
                champiText("Lance la sortie à côté de ta voiture : sa position est enregistrée au départ."),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Gros bouton orange à icône, sur la carte verte. */
@Composable
private fun BigButton(text: String, icon: ImageVector, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary, contentColor = ChampiColors.Encre),
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(champiText(text), style = MaterialTheme.typography.labelLarge)
    }
}

/** Bandeau chasse (sécurité → SafetyTheme), centré sur 4 lignes, croix pour masquer. */
@Composable
private fun HuntBanner(onArretes: () -> Unit, onHide: () -> Unit) {
    SafetyTheme {
        Box(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(18.dp))
                .padding(16.dp),
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                val c = MaterialTheme.colorScheme.onSecondaryContainer
                Text(champiText("Période de chasse"), style = MaterialTheme.typography.titleMedium, color = c)
                Text(champiText("Début septembre à fin février"), style = MaterialTheme.typography.bodyMedium, color = c)
                Text(champiText("Reste visible !"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = c)
                Text(
                    champiText("Consulte les arrêtés locaux"),
                    style = MaterialTheme.typography.bodyMedium,
                    textDecoration = TextDecoration.Underline,
                    color = c,
                    modifier = Modifier.clickable(onClick = onArretes).padding(vertical = 6.dp),
                )
            }
            IconButton(onClick = onHide, modifier = Modifier.align(Alignment.TopEnd).size(40.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Masquer jusqu'à la prochaine sortie")
            }
        }
    }
}

/** Bandeau « Battue prévue aujourd'hui » (jour saisi dans les paramètres). */
@Composable
private fun BattueBanner(battues: List<Battue>) {
    SafetyTheme {
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(18.dp))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val c = MaterialTheme.colorScheme.onErrorContainer
            Text("Battue prévue aujourd'hui", style = MaterialTheme.typography.titleMedium, color = c, textAlign = TextAlign.Center)
            Text("Évite les bois concernés, ou reste très visible.", style = MaterialTheme.typography.bodyMedium, color = c, textAlign = TextAlign.Center)
            battues.mapNotNull { it.note?.takeIf { n -> n.isNotBlank() } }.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = c, textAlign = TextAlign.Center)
            }
        }
    }
}

/** « Cette saison » : kg récoltés, sorties, spots. */
@Composable
private fun SeasonTiles(weightG: Long, sorties: Int, spots: Int) {
    Column {
        SectionTitle("Cette saison")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(HomeLogic.weightLabel(weightG), "récoltés", Modifier.weight(1f))
            Tile(sorties.toString(), if (sorties > 1) "sorties" else "sortie", Modifier.weight(1f))
            Tile(spots.toString(), if (spots > 1) "spots" else "spot", Modifier.weight(1f))
        }
    }
}

@Composable
private fun Tile(value: String, label: String, modifier: Modifier) {
    ChampiCard(modifier) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        Text(champiText(label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val MONTH_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM", Locale.FRANCE)
private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM · H 'h' mm", Locale.FRANCE)

/** « Dernières sorties » + lien Calendrier ; pastille de date (ou picto si easter egg). */
@Composable
private fun LastSorties(
    sorties: List<Sortie>,
    weights: Map<String, Long>,
    easter: Boolean,
    onCalendar: () -> Unit,
    onOpen: (String) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Dernières sorties", Modifier.weight(1f))
            Text(
                champiText("Calendrier"),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onCalendar).padding(8.dp),
            )
        }
        if (sorties.isEmpty()) {
            ChampiCard(Modifier.fillMaxWidth()) {
                Text(
                    champiText("Pas encore de sortie. La première est toujours la meilleure !"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            sorties.forEach { so ->
                val date = Instant.ofEpochMilli(so.startedAt).atZone(ZoneId.systemDefault())
                ChampiCard(Modifier.fillMaxWidth().clickable { onOpen(so.id) }, padding = PaddingValues(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DateBadge(date.dayOfMonth, date.format(MONTH_FMT), if (easter) so.pictoKey else null)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                champiText(so.placeName?.takeIf { it.isNotBlank() } ?: "Sortie"),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                date.format(TIME_FMT),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val g = weights[so.id] ?: 0L
                        if (g > 0) Text(HomeLogic.weightLabel(g), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

/** Pastille de date (jour + mois abrégé), ou picto de la sortie si débloqué. */
@Composable
private fun DateBadge(day: Int, month: String, pictoKey: String?) {
    Box(
        Modifier.size(52.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (!pictoKey.isNullOrEmpty()) {
            Picto(pictoKey, Modifier.size(40.dp))
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(day.toString(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(month.trimEnd('.'), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}
