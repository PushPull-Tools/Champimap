package fr.pushpull.champimap.feature.sortie

/*
 * Module « sortie » — index des points d'entrée (signatures appelées par nav/AppNav.kt, inchangées) :
 *  - DepartScreen(nav)               → DepartScreen.kt     « Comment tu démarres ? »
 *  - SortieScreen(nav, sortieId)     → SortieScreen.kt     sortie en cours (carte, flèche, Marquer un spot…)
 *  - FinSortieScreen(nav, sortieId)  → FinSortieScreen.kt  récapitulatif de fin de sortie
 *  - PerduScreen(nav, sortieId)      → PerduScreen.kt      « Je suis perdu » (SafetyTheme)
 *  - CarteScreen(nav)                → CarteScreen.kt      carte globale (onglet Carte)
 *  - SortieTrackingService           → SortieTrackingService.kt (service de premier plan, déclaré au manifeste)
 *
 * Outils réutilisables par les autres modules :
 *  - PrecisePositionSampler / PrecisePositionDialog : mesure de position précise (moyenne 30–60 s) ;
 *  - ChampiMapLayers : épingles, trace, position et départ sur une ChampiMapView ;
 *  - Geo : distances, caps, mise en forme ; SortieTracker : état en direct de la sortie.
 */
