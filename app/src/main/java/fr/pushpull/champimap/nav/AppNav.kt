package fr.pushpull.champimap.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import fr.pushpull.champimap.feature.bonus.AvatarScreen
import fr.pushpull.champimap.feature.bonus.HiddenRainsOverlay
import fr.pushpull.champimap.feature.bonus.champiText
import fr.pushpull.champimap.feature.bonus.emojiFor
import fr.pushpull.champimap.feature.carnet.CarnetScreen
import fr.pushpull.champimap.feature.connect.ConnectScreen
import fr.pushpull.champimap.feature.connect.IntoxListScreen
import fr.pushpull.champimap.feature.connect.IntoxScreen
import fr.pushpull.champimap.feature.groupe.GroupeCreerScreen
import fr.pushpull.champimap.feature.groupe.GroupeRejoindreScreen
import fr.pushpull.champimap.feature.groupe.ImportPartageScreen
import fr.pushpull.champimap.feature.groupe.PartageSpotScreen
import fr.pushpull.champimap.feature.reglages.CorbeilleScreen
import fr.pushpull.champimap.feature.reglages.HomeScreen
import fr.pushpull.champimap.feature.reglages.JournalScreen
import fr.pushpull.champimap.feature.reglages.OnboardingScreen
import fr.pushpull.champimap.feature.reglages.ParametresScreen
import fr.pushpull.champimap.feature.reglages.PreuvesScreen
import fr.pushpull.champimap.feature.reglages.SauvegardeScreen
import fr.pushpull.champimap.feature.sortie.CarteScreen
import fr.pushpull.champimap.feature.sortie.DepartScreen
import fr.pushpull.champimap.feature.sortie.FinSortieScreen
import fr.pushpull.champimap.feature.sortie.PerduScreen
import fr.pushpull.champimap.feature.sortie.SortieScreen
import fr.pushpull.champimap.feature.spot.CatalogueScreen
import fr.pushpull.champimap.feature.spot.EspecesPickerScreen
import fr.pushpull.champimap.feature.spot.FicheEspeceScreen
import fr.pushpull.champimap.feature.spot.SpotScreen
import fr.pushpull.champimap.feature.stats.CalendrierScreen
import fr.pushpull.champimap.feature.stats.StatsScreen
import fr.pushpull.champimap.feature.stats.TresorsScreen

/** Un onglet du menu du bas. */
private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab(Routes.HOME, "Accueil", Icons.Filled.Home),
    Tab(Routes.CARTE, "Carte", Icons.Filled.Map),
    Tab(Routes.STATS, "Stats", Icons.Filled.BarChart),
    Tab(Routes.CATALOGUE, "Espèces", Icons.Filled.Spa),
    Tab(Routes.PARAMETRES, "Plus", Icons.Filled.MoreHoriz),
)

/**
 * Navigation complète de l'appli.
 * @param startOnboarding true au tout premier lancement.
 * @param importPending true si l'appli a été ouverte avec un fichier de spot partagé.
 */
@Composable
fun AppNav(startOnboarding: Boolean, importPending: Boolean) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    // Écran de départ figé au premier affichage : MainActivity rappelle AppNav à CHAQUE changement
    // de réglage (fin du premier lancement, message de départ tiré, fichier d'import consommé…).
    // Sans `remember`, le départ changeait (ONBOARDING/IMPORT → HOME), le graphe était recréé et
    // toute la pile de navigation était perdue (ex. l'écran Connect ouvert par « Ouvrir les paramètres »).
    val start = remember {
        when {
            startOnboarding -> Routes.ONBOARDING
            importPending -> Routes.IMPORT_PARTAGE
            else -> Routes.HOME
        }
    }

    // Les pluies cachées (6, 12, 24 appuis dans le vide) recouvrent tous les écrans.
    HiddenRainsOverlay {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                NavHost(navController = nav, startDestination = start) {
                    composable(Routes.ONBOARDING) { OnboardingScreen(nav) }
                    composable(Routes.HOME) { HomeScreen(nav) }
                    composable(Routes.CARTE) { CarteScreen(nav) }
                    composable(Routes.STATS) { StatsScreen(nav) }
                    composable(Routes.CATALOGUE) { CatalogueScreen(nav) }
                    composable(Routes.PARAMETRES) { ParametresScreen(nav) }

                    composable(Routes.DEPART) { DepartScreen(nav) }
                    composable(Routes.SORTIE, listOf(navArgument("sortieId") { type = NavType.StringType })) {
                        SortieScreen(nav, it.arguments?.getString("sortieId").orEmpty())
                    }
                    composable(Routes.FIN, listOf(navArgument("sortieId") { type = NavType.StringType })) {
                        FinSortieScreen(nav, it.arguments?.getString("sortieId").orEmpty())
                    }
                    composable(Routes.PERDU, listOf(navArgument("sortieId") { type = NavType.StringType })) {
                        PerduScreen(nav, it.arguments?.getString("sortieId").orEmpty())
                    }

                    composable(
                        Routes.SPOT,
                        listOf(
                            navArgument("spotId") { type = NavType.StringType },
                            navArgument("sortieId") { type = NavType.StringType; nullable = true; defaultValue = null },
                        ),
                    ) { SpotScreen(nav, it.arguments?.getString("spotId").orEmpty(), it.arguments?.getString("sortieId")) }
                    composable(Routes.ESPECES_PICKER, listOf(navArgument("recolteId") { type = NavType.StringType })) {
                        EspecesPickerScreen(nav, it.arguments?.getString("recolteId").orEmpty())
                    }
                    composable(Routes.FICHE_ESPECE, listOf(navArgument("speciesId") { type = NavType.StringType })) {
                        FicheEspeceScreen(nav, it.arguments?.getString("speciesId").orEmpty())
                    }

                    composable(Routes.CALENDRIER) { CalendrierScreen(nav) }
                    composable(Routes.TRESORS) { TresorsScreen(nav) }

                    composable(
                        Routes.INTOX,
                        listOf(navArgument("sortieId") { type = NavType.StringType; nullable = true; defaultValue = null }),
                    ) { IntoxScreen(nav, it.arguments?.getString("sortieId")) }
                    composable(Routes.INTOX_LIST) { IntoxListScreen(nav) }

                    composable(Routes.PREUVES) { PreuvesScreen(nav) }
                    composable(Routes.JOURNAL) { JournalScreen(nav) }
                    composable(Routes.SAUVEGARDE) { SauvegardeScreen(nav) }
                    composable(Routes.CORBEILLE) { CorbeilleScreen(nav) }
                    composable(Routes.CONNECT) { ConnectScreen(nav) }

                    composable(Routes.GROUPE_CREER, listOf(navArgument("sortieId") { type = NavType.StringType })) {
                        GroupeCreerScreen(nav, it.arguments?.getString("sortieId").orEmpty())
                    }
                    composable(Routes.GROUPE_REJOINDRE) { GroupeRejoindreScreen(nav) }
                    composable(Routes.PARTAGE_SPOT, listOf(navArgument("spotId") { type = NavType.StringType })) {
                        PartageSpotScreen(nav, it.arguments?.getString("spotId").orEmpty())
                    }
                    composable(Routes.IMPORT_PARTAGE) { ImportPartageScreen(nav) }

                    composable(Routes.AVATAR) { AvatarScreen(nav) }
                    composable(Routes.CARNET) { CarnetScreen(nav) } // Carnet : niveaux et succès
                }
            }

            // Liaisons entre modules (après la création du graphe de navigation).
            ModuleGlue(nav)

            // Menu du bas, seulement sur les écrans principaux.
            if (currentRoute in Routes.TOP_LEVEL) {
                NavigationBar(Modifier.navigationBarsPadding(), containerColor = MaterialTheme.colorScheme.surface) {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    // Retour à l'Accueil (toujours en bas de la pile) : le départ du graphe peut être
                                    // le premier lancement ou l'import, déjà retirés de la pile.
                                    popUpTo(Routes.HOME) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(emojiFor(tab.label) + champiText(tab.label)) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer),
                        )
                    }
                }
            }
        }
    }
}
