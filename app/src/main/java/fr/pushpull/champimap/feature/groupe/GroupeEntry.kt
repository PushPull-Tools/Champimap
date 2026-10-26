package fr.pushpull.champimap.feature.groupe

import androidx.compose.runtime.Composable
import androidx.navigation.NavController

/*
 * Points d'entrée des écrans du module « groupe » (appelés par nav/AppNav.kt, signatures fixes).
 * Chaque écran est dans son propre fichier :
 *  - GroupeCreerUi.kt      : « Sortie à plusieurs » (création du réseau + QR code) ;
 *  - GroupeRejoindreUi.kt  : « Rejoindre » (scan du QR code + connexion) ;
 *  - PartageSpotUi.kt      : « Partager le spot » (QR code ou fichier .champi) ;
 *  - ImportPartageUi.kt    : « Spot reçu » (fichier .champi ou QR code scanné).
 * Moteur de synchro : GroupSync.kt ; fusion : GroupRepository.kt ; partage : SpotShare.kt.
 */

@Composable
fun GroupeCreerScreen(nav: NavController, sortieId: String) = GroupeCreerContent(nav, sortieId)

@Composable
fun GroupeRejoindreScreen(nav: NavController) = GroupeRejoindreContent(nav)

@Composable
fun PartageSpotScreen(nav: NavController, spotId: String) = PartageSpotContent(nav, spotId)

@Composable
fun ImportPartageScreen(nav: NavController) = ImportPartageContent(nav)
