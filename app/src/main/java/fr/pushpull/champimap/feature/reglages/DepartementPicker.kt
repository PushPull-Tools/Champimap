package fr.pushpull.champimap.feature.reglages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.pushpull.champimap.feature.bonus.champiText

/*
 * Choix des départements (premier lancement et paramètres) : recherche, cases à cocher,
 * départements cochés en tête de liste, taille de carte estimée par département et au total.
 */

/**
 * Liste à cocher des 101 départements.
 * @param selected codes actuellement cochés.
 * @param onChange nouvelle sélection (appelée à chaque case).
 */
@Composable
fun DepartementPicker(selected: Set<String>, onChange: (Set<String>) -> Unit, modifier: Modifier = Modifier) {
    var query by remember { mutableStateOf("") }
    // Les départements cochés restent en tête pour qu'on les retrouve sans chercher.
    val list = remember(query, selected) {
        Departements.rechercher(query).sortedByDescending { it.code in selected }
    }
    Column(modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            placeholder = { Text("Rechercher (nom ou numéro)") },
        )
        Text(
            champiText(
                if (selected.isEmpty()) "Aucun département coché"
                else "${selected.size} coché(s) · cartes ≈ ${Departements.tailleTotaleMo(selected)} Mo",
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = true)) {
            items(list, key = { it.code }) { d ->
                val checked = d.code in selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clickable { onChange(if (checked) selected - d.code else selected + d.code) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(checked = checked, onCheckedChange = { onChange(if (it) selected + d.code else selected - d.code) })
                    Text(
                        d.libelle,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (checked) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                    Text("≈ ${d.tailleMo} Mo", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
}
