package fr.pushpull.champimap.feature.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import fr.pushpull.champimap.core.db.HarvestRow
import fr.pushpull.champimap.core.db.Sortie
import fr.pushpull.champimap.core.ui.ChampiCard
import fr.pushpull.champimap.core.ui.ChampiScreen
import fr.pushpull.champimap.core.ui.SectionTitle
import fr.pushpull.champimap.feature.bonus.champiText
import fr.pushpull.champimap.nav.Routes
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Calendrier des sorties : grille mensuelle (lundi en premier), jours de sortie marqués,
 * jours où un spot a bien donné les années précédentes signalés par un petit point orange.
 * Appui sur un jour → détail : sorties du jour (durée, poids) et spots qui ont donné
 * à cette date les années précédentes (± 7 jours).
 */

/** Fenêtre (± jours) pour « ce spot a bien donné à cette date ». */
private const val PAST_WINDOW_DAYS = 7

@Composable
fun CalendrierContent(nav: NavController) {
    val data = rememberStatsData()
    val today = remember { LocalDate.now() }
    // Mois affiché et jour choisi, mémorisés en jours depuis l'époque (types simples sauvegardables).
    var monthEpoch by rememberSaveable { mutableLongStateOf(today.withDayOfMonth(1).toEpochDay()) }
    var selectedEpoch by rememberSaveable { mutableLongStateOf(today.toEpochDay()) }
    val month = LocalDate.ofEpochDay(monthEpoch)
    val selected = LocalDate.ofEpochDay(selectedEpoch)

    // Index par jour : sorties et grammes récoltés.
    val sortiesByDay = remember(data.sorties) { data.sorties.groupBy { StatsTime.dateOf(it.startedAt) } }
    val gramsByDay = remember(data.harvests) {
        data.harvests.groupBy { StatsTime.dateOf(it.time) }.mapValues { (_, l) -> l.sumOf { (it.weightG ?: 0).toLong() } }
    }
    // Jours du mois affiché où au moins un spot avait donné les années précédentes (point orange).
    val pastGoodDays = remember(data.harvests, monthEpoch) { pastGoodDaysOf(data.harvests, month) }

    ChampiScreen(title = "Calendrier", onBack = { nav.popBackStack() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            ChampiCard(Modifier.fillMaxWidth()) {
                MonthHeader(
                    month = month,
                    onPrev = { monthEpoch = month.minusMonths(1).toEpochDay() },
                    onNext = { monthEpoch = month.plusMonths(1).toEpochDay() },
                )
                Spacer(Modifier.height(8.dp))
                MonthGrid(
                    month = month,
                    today = today,
                    selected = selected,
                    hasSortie = { sortiesByDay.containsKey(it) },
                    hasPastGood = { it in pastGoodDays },
                    onSelect = { selectedEpoch = it.toEpochDay() },
                )
                Spacer(Modifier.height(8.dp))
                Legend()
            }

            DayDetail(
                nav = nav,
                day = selected,
                sorties = sortiesByDay[selected].orEmpty(),
                harvests = data.harvests,
                grams = gramsByDay[selected] ?: 0L,
            )
        }
    }
}

/** En-tête « ‹ Octobre 2026 › ». */
@Composable
private fun MonthHeader(month: LocalDate, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrev, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Mois précédent")
        }
        Text(
            StatsFormat.capitalize(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.FRANCE))),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onNext, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Mois suivant")
        }
    }
}

/** Grille du mois : 7 colonnes (L → D), cases vides avant le 1er. */
@Composable
private fun MonthGrid(
    month: LocalDate,
    today: LocalDate,
    selected: LocalDate,
    hasSortie: (LocalDate) -> Boolean,
    hasPastGood: (LocalDate) -> Boolean,
    onSelect: (LocalDate) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val leading = month.dayOfWeek.value - 1 // lundi = 0 case vide
    val days = month.lengthOfMonth()
    val cells = leading + days
    val rows = (cells + 6) / 7

    Row(Modifier.fillMaxWidth()) {
        StatsTime.WEEKDAY_LETTERS.forEach {
            Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        }
    }
    Spacer(Modifier.height(4.dp))
    for (r in 0 until rows) {
        Row(Modifier.fillMaxWidth()) {
            for (c in 0 until 7) {
                val index = r * 7 + c - leading
                Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                    if (index in 0 until days) {
                        val d = month.plusDays(index.toLong())
                        DayCell(d, d == today, d == selected, hasSortie(d), hasPastGood(d), onSelect)
                    }
                }
            }
        }
    }
}

/** Une case jour : fond vert si sortie, contour si aujourd'hui, orange si choisie, point si « bien donné ». */
@Composable
private fun DayCell(d: LocalDate, isToday: Boolean, isSelected: Boolean, sortie: Boolean, pastGood: Boolean, onSelect: (LocalDate) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val bg = when {
        isSelected -> scheme.secondary
        sortie -> scheme.primary
        else -> Color.Transparent
    }
    val fg = when {
        isSelected -> scheme.onSecondary
        sortie -> scheme.onPrimary
        else -> scheme.onSurface
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(bg)
            .then(if (isToday && !isSelected) Modifier.border(2.dp, scheme.primary, CircleShape) else Modifier)
            .clickable { onSelect(d) },
        contentAlignment = Alignment.Center,
    ) {
        Text(d.dayOfMonth.toString(), color = fg, fontWeight = if (sortie || isToday) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp)
        if (pastGood) {
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).size(5.dp).clip(CircleShape)
                    .background(if (isSelected) scheme.onSecondary else scheme.secondary),
            )
        }
    }
}

/** Légende sous la grille. */
@Composable
private fun Legend() {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(scheme.primary))
        Text(champiText("Sortie"), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        Spacer(Modifier.size(8.dp))
        Box(Modifier.size(6.dp).clip(CircleShape).background(scheme.secondary))
        Text(champiText("Un spot a donné à cette date les années d'avant"), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    }
}

/** Détail du jour choisi : sorties et spots qui ont donné à cette date les années précédentes. */
@Composable
private fun DayDetail(nav: NavController, day: LocalDate, sorties: List<Sortie>, harvests: List<HarvestRow>, grams: Long) {
    val scheme = MaterialTheme.colorScheme
    val past = remember(harvests, day) { PastYears.goodSpotsAround(harvests, day, PAST_WINDOW_DAYS) }

    SectionTitle(StatsFormat.capitalize(day.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRANCE))))
    ChampiCard(Modifier.fillMaxWidth()) {
        if (sorties.isEmpty()) {
            Text(champiText("Pas de sortie ce jour-là."), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        } else {
            sorties.forEach { s ->
                val duration = s.endedAt?.let { end ->
                    val min = ((end - s.startedAt) / 60_000L).coerceAtLeast(0)
                    if (min >= 60) "${min / 60} h ${(min % 60).toString().padStart(2, '0')}" else "$min min"
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { nav.navigate(Routes.fin(s.id)) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            champiText(s.placeName?.takeIf { it.isNotBlank() } ?: "Sortie de ${StatsFormat.time(s.startedAt)}"),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            champiText(if (duration != null) "Départ ${StatsFormat.time(s.startedAt)} · $duration" else "Départ ${StatsFormat.time(s.startedAt)} · en cours"),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (grams > 0) {
                Spacer(Modifier.height(4.dp))
                KeyValueRow("Récolté ce jour-là", StatsFormat.kg(grams))
            }
        }
    }

    SectionTitle("Les années d'avant, à cette date")
    ChampiCard(Modifier.fillMaxWidth()) {
        if (past.isEmpty()) {
            Text(champiText("Aucun spot n'a donné à cette période les années précédentes."), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        } else {
            past.take(8).forEach { p ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { nav.navigate(Routes.spot(p.spotId)) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(champiText("Ce spot a bien donné à cette date : ${p.spotName}"), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            champiText("Le ${StatsFormat.date(p.lastDate)} · ${p.items} cueillette${if (p.items > 1) "s" else ""}"),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    if (p.grams > 0) Text(StatsFormat.kg(p.grams), style = MaterialTheme.typography.titleMedium, color = scheme.primary)
                }
            }
        }
    }
}

/**
 * Jours du mois affiché proches (± 3 j) d'une récolte pesée d'une saison précédente.
 * Fenêtre plus courte que le détail pour que les points restent parlants sur la grille.
 */
private fun pastGoodDaysOf(harvests: List<HarvestRow>, month: LocalDate): Set<LocalDate> {
    val pastDates = harvests.filter { (it.weightG ?: 0) > 0 }.map { StatsTime.dateOf(it.time) }.distinct()
    if (pastDates.isEmpty()) return emptySet()
    val result = HashSet<LocalDate>()
    for (i in 0 until month.lengthOfMonth()) {
        val d = month.plusDays(i.toLong())
        if (pastDates.any { p -> p.isBefore(d.minusDays(300)) && StatsTime.daysApartIgnoringYear(p, d) <= 3 }) result += d
    }
    return result
}
