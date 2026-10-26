package fr.pushpull.champimap.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.feature.groupe.GroupSync
import fr.pushpull.champimap.feature.sortie.SortieIntentHandler
import fr.pushpull.champimap.feature.sortie.SortieTracker
import kotlinx.coroutines.flow.collectLatest

/**
 * Liaisons entre modules, actives sur tout l'appli :
 *  - ouverture de la bonne sortie depuis la notification (même appli fermée) ;
 *  - reprise de la synchro de groupe si une sortie partagée est en cours ;
 *  - pastille « Avec Julie · synchro OK » alimentée par l'état du groupe.
 */
@Composable
fun ModuleGlue(nav: NavController) {
    val context = LocalContext.current

    // Notification « Sortie en cours » / « Marquer un spot » → bon écran.
    SortieIntentHandler(nav)

    // Sortie partagée en cours (ex. après redémarrage de l'appli) → on relance la synchro.
    LaunchedEffect(Unit) {
        AppGraph.db.sortieDao().current().collectLatest { sortie ->
            val groupId = sortie?.groupId
            if (groupId != null) GroupSync.start(context, groupId)
        }
    }

    // État du groupe → pastille de l'écran de sortie.
    LaunchedEffect(Unit) {
        GroupSync.state.collectLatest { st ->
            SortieTracker.groupSyncStatus.value = st.label().takeIf { it.isNotBlank() }
        }
    }
}
