package fr.pushpull.champimap.core.db

/*
 * Accès aux données. Chaque DAO a un « propriétaire » (le module qui l'enrichit) :
 *  - PersonDao, SortieDao ............ module sortie
 *  - SpotDao, RecolteDao, PhotoDao,
 *    SpeciesDao ...................... module spot
 *  - StatsDao, WeatherDao ............ module stats
 *  - SyncDao ......................... module groupe (fusion entre téléphones)
 *  - BackupDao, ErrorLogDao .......... module réglages
 *  - IntoxDao, HospitalDao, BattueDao  module connect / sécurité
 * Un module ajoute ses requêtes dans SES DAO uniquement ; il lit les autres sans les modifier.
 */
