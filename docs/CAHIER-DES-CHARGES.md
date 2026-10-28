# Champimap — cahier des charges et règles de développement

Appli Android pour noter ses coins à champignons. **100 % hors ligne** : l'appli principale n'a
AUCUNE permission réseau. Seul le module séparé **Champimap Connect** (dossier `connect/`) va sur Internet,
et uniquement pour des zones larges (départements, carrés de ~25 km), jamais les positions des spots.

Maquette validée (référence visuelle) : écrans décrits ci-dessous. Langue de l'appli : français.

## Règles de code (OBLIGATOIRES)
- Kotlin, Jetpack Compose, Material 3. Paquet racine `fr.pushpull.champimap`.
- **Code rangé et commenté en français** : chaque fichier commence par un commentaire qui explique son rôle ;
  chaque fonction non triviale a un commentaire. Fichiers courts et lisibles (découper au-delà de ~400 lignes).
- **Aucune dépendance réseau** dans `app/` : pas d'OkHttp, Retrofit, Firebase, Google Play Services, ML Kit,
  analytics, crash reporting. Pas de `java.net.URL`/`HttpURLConnection` dans `app/`.
- Pas de nouvelle dépendance Gradle sans nécessité forte. Dépendances disponibles : voir `gradle/libs.versions.toml`
  (Compose, Material3 + icônes étendues, Navigation, Lifecycle, Room, DataStore, Biometric, WorkManager,
  coroutines, kotlinx.serialization, MapLibre `org.maplibre.android`, zxing core + zxing-android-embedded, Coil).
- **On ne peut PAS compiler localement** (dépôts Maven bloqués) : la compilation se fait sur GitHub Actions.
  Écrire du code qui compile du premier coup : imports complets et exacts, API existantes uniquement
  (Compose BOM 2024.12.01, Material3 1.3.x, Room 2.6.1, MapLibre Android 11.8.0, Kotlin 2.0.21), pas d'API
  expérimentale sans `@OptIn`. En cas de doute sur une API, choisir la plus simple et la plus stable.
- Accès aux données : `AppGraph.db` (Room), `AppGraph.settings` (DataStore), `AppGraph.appScope`.
  Identifiants : `newId()`. Erreurs : `ErrorLogger.log(ecran, message, exception)` — **jamais de coordonnées
  GPS ni de nom de spot dans le journal**.
- UI : utiliser les composants partagés de `core/ui/Components.kt` (`ChampiScreen`, `ChampiButton`,
  `ChampiOutlinedButton`, `ChampiCard`, `ChampiChip`, `SectionTitle`, `ConfirmDialog`) pour que les thèmes
  (clair, sombre, psyché) et les options (émojis, « champignong ») s'appliquent partout.
  Tout texte affiché passe par `champiText(...)` (déjà fait dans les composants partagés).
  Couleurs : `MaterialTheme.colorScheme` ou `ChampiColors`. Boutons ≥ 56 dp de haut (mode forêt, gants).
- Écrans de sécurité (intoxication, « Je suis perdu », alertes nuit/batterie/chasse) : envelopper dans
  `SafetyTheme { }` → jamais en thème party.
- **Enregistrement automatique** : chaque modification est enregistrée ~300 ms après la dernière frappe
  (pas de bouton Sauvegarder). Petit « ✓ Enregistré » discret. Suppression → bandeau « Supprimé — Annuler »
  pendant **10 s** + corbeille 30 jours (`deletedAt`). Suppression de **photo** : boîte de confirmation.
- Rien n'est obligatoire dans les formulaires (sauf prénom et départements au premier lancement).
- Ne jamais utiliser le mot « battu ». Écrire « Nouveau record ».
- Numéros de téléphone affichés avec des espaces : `05 56 96 40 80`.
- Ne PAS modifier les fichiers d'un autre module. Fichiers partagés modifiables uniquement par leur propriétaire
  (voir « Propriété des fichiers »). Si un module a besoin d'une requête de base de données, il l'ajoute dans
  SON DAO.

## Architecture (socle déjà en place)
- `core/db/` : entités (`Entities.kt`) et un fichier par DAO. Base `AppDatabase` (version 2 : `Migration(1,2)` explicite
  qui crée la table `vente` ; jamais de migration destructive).
- `core/settings/Settings.kt` : `AppSettings`, `SettingsRepository`, `ThemeMode` (AUTO, CLAIR, SOMBRE, PSYCHE).
- `core/ui/theme/Theme.kt` : couleurs `ChampiColors`, `ChampimapTheme`, `LocalChampiStyle`, `SafetyTheme`.
- `core/ui/Components.kt` : composants partagés.
- `core/util/AstroCalc.kt` : coucher du soleil, « nuit noire en forêt » (coucher − 25 min), phase de lune.
- `core/map/ChampiMap.kt` : `ChampiMapView(modifier, onMapReady)` — carte hors ligne partagée.
- `nav/Routes.kt` + `nav/AppNav.kt` : routes et graphe de navigation, menu du bas
  (Accueil, Carte, Stats, Espèces, Plus).
- `feature/<module>/…Entry.kt` : écrans provisoires dont **les signatures doivent être conservées
  exactement** (appelées par `AppNav.kt`). Chaque module remplace ses écrans provisoires.

## Tests : captures d'écran automatiques
- À chaque envoi (job CI `captures`, séparé de l'APK : un échec ne bloque jamais la publication), chaque écran
  est rendu en PNG 1080 × 2400 (420 dpi) en thème **clair**, **sombre** et **Psyché**, par
  **Robolectric** (rendu natif) + **Roborazzi** 1.32 : tests `app/src/test/java/fr/pushpull/champimap/captures/`
  (`gradle :app:testDebugUnitTest --tests 'fr.pushpull.champimap.captures.*'`).
- Rangement par parcours : `1-premier-lancement/` (Prudence → Bienvenue → Départements → Connect → Préparation
  des cartes → Accueil), `2-sortie/` (Accueil → Départ → transition → Sortie en cours → Marquer un spot → fiche
  Spot → « Trouvé ! » → choix d'espèce → propositions de l'IA → récolte → Je suis perdu → fin de sortie),
  `3-menu/` (onglets et tous les écrans ouverts depuis eux). Écran long = plusieurs captures (`-1`, `-2`…).
- Publication : branche orpheline **`captures`** (réécrite à chaque passage) → `captures/<sha>/` et
  `captures/derniere/`, avec `index.html` (planche : une section par parcours, 3 colonnes) et `rapport.txt`
  (écrans manquants, erreurs) ; aussi en artefact « Captures ».
- Données : base Room **en mémoire** remplie de données factices (`CaptureData` : spots imaginaires des Monts de
  Blond, saison de sorties, récoltes, ventes, météo) ; propositions de l'IA factices. Points d'injection réservés
  aux tests, sans effet dans l'appli : `AppGraph.initForTests` / `AppGraph.testMode` (la carte MapLibre, qui exige
  un GPU, est remplacée par une zone neutre) et le paramètre facultatif `nav` d'`AppNav`.
- Ajouter un écran : une étape `step("NN-nom") { go(route); capture("NN-nom", "Libellé") }` dans le bon parcours.

## Propriété des fichiers
| Module | Paquet | Propriétaire aussi de |
|---|---|---|
| sortie | `feature/sortie` | `core/map/ChampiMap.kt`, `SortieDao.kt`, `PersonDao.kt` |
| spot | `feature/spot` | `SpotDao.kt`, `RecolteDao.kt`, `PhotoDao.kt`, `SpeciesDao.kt`, `app/src/main/assets/species.json` (écrit par le module données) |
| stats | `feature/stats` | `StatsDao.kt`, `WeatherDao.kt` |
| reglages | `feature/reglages` | `BackupDao.kt`, `ErrorLogDao.kt` |
| groupe | `feature/groupe` | `SyncDao.kt` |
| connect | `feature/connect` + tout le dossier `connect/` | `IntoxDao.kt`, `HospitalDao.kt`, `BattueDao.kt` |
| bonus | `feature/bonus` | — |
Fichiers du socle (`core/ui`, `core/settings`, `core/db/Entities.kt`, `AppDatabase.kt`, `nav/`, `MainActivity.kt`,
manifeste, Gradle) : NE PAS modifier. Si un changement y est indispensable, le signaler dans le rapport final.

## Fonctionnalités (cahier des charges validé)

### Premier lancement (module reglages)
1. Bienvenue : logo (épingle verte au cœur orange), « Bienvenue dans Champimap », « Tes coins à champignons,
   tes récoltes, tes stats. Tout reste sur ton téléphone. » puis en gras sur sa ligne
   « Champimap n'a pas accès à Internet. », champ « Comment tu t'appelles ? » (prénom), Continuer.
   Le prénom crée la `Person` isMe=true.
2. Départements : liste de tous les départements à cocher (recherche), taille de carte estimée, Continuer.
3. Champimap Connect : texte validé (voir module connect), « Installer le module » / « Plus tard (sans carte ni
   météo) ». « Plus tard » ouvre une fenêtre : ce qui manque (pas de fond de carte : grille avec spots, trace et
   flèche ; pas de météo ; urgences moins à jour), ce qui marche, boutons « Ouvrir les paramètres » (va sur le
   bouton de synchro Connect) et « Continuer ».
4. Téléchargement (« Préparation de tes cartes ») : liste des **vraies tâches de Connect, dans l'ordre réel
   d'exécution** (voir « Avancement de la synchro » dans le module connect), ligne de résumé
   « 3 tâches sur 9 · 42 % · Carte · Haute-Vienne ». Texte fixe « Conseillé : en Wi-Fi (cartes et modèle d'identification
   volumineux) » (l'appli n'a pas ACCESS_NETWORK_STATE : la détection Wi-Fi / données mobiles est dans Connect).
   « Continuer, ça finit en arrière-plan ».
Indicateur « Étape x sur 4 » + barre de progression en 4 segments.

### Accueil (module reglages)
- « Bonjour <prénom> » + **bouton invisible** à côté (easter egg → route AVATAR) + avatar choisi s'il existe.
- Ligne : date, « coucher du soleil 17 h 46 », phase de lune.
- Carte verte : titre simple centré « Prêt pour l'aventure ? » (la phrase de départ aléatoire s'affiche au
  lancement de la sortie : page de transition, cercle façon iris + phrase ~2 s, toucher pour passer ;
  pioche sans répétition du module bonus), bouton « Démarrer une sortie » (icône boussole) → route DEPART,
  sous-texte centré « Lance la sortie à côté de ta voiture : sa position est enregistrée au départ. »
  Si une sortie est en cours : bouton « Reprendre la sortie ».
- Bandeau chasse (seulement de début septembre à fin février), centré, 4 lignes :
  « Période de chasse » / « Début septembre à fin février » / « Reste visible ! » /
  lien souligné « Consulte les arrêtés locaux » (ouvre le navigateur avec une recherche
  « Période de chasse 2026-2027 <département> arrêté préfectoral », département déduit hors ligne).
  Croix pour masquer jusqu'à la prochaine sortie. Jour de battue saisi → bandeau « Battue prévue aujourd'hui… ».
- « Cette saison » : 3 tuiles (kg récoltés, sorties, spots).
- « Dernières sorties » + lien « Calendrier » : à gauche une **pastille de date** (jour + mois).
  (Picto par sortie : abandonné, la date reste affichée. Le champ `pictoKey` de la base est conservé mais inutilisé.)

### Démarrer (module sortie)
Panneau « Comment tu démarres ? » : « Je suis garé » (on enregistre la voiture ici puis les déplacements) ou
« Je suis à pied » (déplacements directement). « Sortie à plusieurs » (→ GROUPE_CREER après création),
« Rejoindre » (→ GROUPE_REJOINDRE), « C'est parti ! ».

### Sortie en cours (module sortie) — thème sombre conseillé
- Carte (ChampiMapView) : trace GPS (pointillés), position, **épingles des spots colorées selon l'espèce**
  (disque clair au centre), spots vides en épingle grise + cercle pointillé, départ : icône voiture (garé)
  ou bottes (à pied) dans un rond, badge **chaîne** si la sortie est partagée. Si l'easter egg est débloqué et
  `markerStyle` = MUSHROOMS ou TREES : pictos du module bonus dans les épingles.
- Barre haute : « Sortie en cours · 1 h 12 », « 3 spots · 1,1 km parcourus », bouton « Terminer » (second appui
  de confirmation). Pastilles : « Avec Julie · synchro OK », « GPS ±4 m », « Batterie 64 % ».
- Panneau bas : flèche + distance vers la voiture (boussole), « 1 h 34 avant la nuit en forêt » (AstroCalc),
  gros bouton orange « Marquer un spot », « Spot existant », « Je suis perdu » (rouge clair).
- Service de premier plan `SortieTrackingService` : notification « Sortie en cours » avec actions
  « Marquer un spot » et « Terminer » (« Terminer » demande un second appui).
- **GPS précision max** : toutes constellations (LocationManager GPS_PROVIDER, pas de Google Play Services) ;
  « Marquer un spot » = moyenne pondérée 30–60 s en restant immobile, rejet des valeurs aberrantes, jauge
  « ±12 m → ±3 m » en direct, validation manuelle. Mode approche à < 15 m (guidage fin + photo du spot).
- **Batterie** : point toutes les 5–10 s en marche, GPS en pause à l'arrêt (capteur de mouvement significatif /
  accéléromètre basse conso), boussole active seulement quand la flèche est visible, mode économie < 20 %
  (point toutes les 30 s, bandeau). Rien en arrière-plan hors sortie.
- Alertes (SafetyTheme) : nuit en forêt dans 45 min (avec distance à la voiture), batterie faible.
- Bandeau chasse dans la période, appui long sur la carte → « Créer un spot ici ? » (placé à la main).
- « Je suis perdu » : coordonnées en grand, flèche vers la voiture, distance, bouton appel 112 (ouvre le clavier,
  n'appelle pas directement).
- Fin de sortie : récap (durée, km, spots), liste des spots (« Compléter » si vide), « Ajouter une autre sortie »,
  « Retour à l'accueil », lien discret « En cas d'intoxication » (masquable dans les réglages).
- Pages de transition (composant réutilisable `CircularReveal`, core/ui) :
  - démarrage d'une NOUVELLE sortie : le fond du thème envahit l'écran en cercle depuis « C'est parti ! »,
    phrase de départ en surimpression ~2 s (trait du temps restant), puis ouverture en cercle sur la carte ;
  - fin de sortie : fermeture en cercle sur la carte, ouverture du récap ; les chiffres (durée, distance, spots,
    poids) comptent ; l'XP se remplit en direct ligne par ligne (« Sortie terminée +20 »…), la barre de niveau
    suit, se vide et repart si un niveau est atteint (« Niveau N ! » qui rebondit) ; puis carte de niveau et
    succès un par un (carte qui se retourne, « Suivant ») ; seulement juste après la fin (pas depuis l'historique) ;
  - toucher n'importe où = état final ; « animations réduites » d'Android = état final direct ;
  - couleurs du thème ; en Psyché, fond dégradé violet nuit et liseré jaune, texte blanc (toujours lisible).
- Carte globale (onglet Carte) : tous les spots, cadrage automatique sur l'ensemble des spots, sinon centrée sur
  les départements cochés. Légende des couleurs d'un appui.

### Spot et récoltes (module spot)
- Fiche spot : photo du spot ou **illustration de forêt du Limousin** par défaut (fournie par le module bonus :
  `ForestIllustration(modifier)`), bouton « Photo du spot », Retour, Partager (→ PARTAGE_SPOT).
- Nom par défaut « Spot N (lat ; lon) », modifiable ; coordonnées masquées/affichées (œil) ; précision ±m ;
  nombre de visites ; pastilles statut (Actif, Épuisé, Détruit, À explorer), essence d'arbre, note 1–5 étoiles,
  repère texte, note, mémo vocal.
- Récolte du jour : verrouillable (cadenas = non modifiable/non supprimable). Plusieurs espèces : photo
  (miniature), nom, poids en g (boutons +50/+100 et saisie libre, facultatif), pastilles stade (Bouchon, Jeune,
  Beau, Ouvert, Passé), qualité (Ferme, Véreux, Limacé, Moisi, Parasité, Abîmé), état (Gorgé d'eau, Sec, Gelé).
- Bouton « **Trouvé !** » → petit menu « Photo » (icône) / « Liste » (icône). Photo : appareil photo système
  (ActivityResultContracts.TakePicture + FileProvider), puis identification par IA (voir ci-dessous) et liste.
  Liste → route ESPECES_PICKER.
- « Spot vide » (visite horodatée), « Je suis au pied » (nouvelle mesure précise, moyenne de toutes les visites).
- « Historique des positions (N) » : liste datée par origine, « Revenir à cette position ».
  « Ajuster la position » : déplacement sur la carte avec distance en direct, confirmation au-delà de 50 m.
- Photos : appui long → suppression avec confirmation, sélection multiple, corbeille 30 j.
- Liste « Complète ta cueillette » : recherche (nom courant ou latin, tolérante aux fautes),
  « Tes habituels », « Les plus présents dans tes départements » (pour l'instant : espèces `common`),
  pastille Comestible / À éviter / Toxique / Mortel / Psychotrope, « Voir toutes les espèces (A → Z) ».
- Catalogue (onglet Espèces) + fiche espèce : comestibilité, sosies dangereux (avertissement), saison, habitat,
  rappel « En cas de doute, fais vérifier par un pharmacien ou une société mycologique ».
- `SpeciesLoader.ensureLoaded(context)` : charge `assets/species.json` dans la base si vide, puis ajoute
  (insert ignore, à chaque nouvelle version du catalogue mémorisée dans le DataStore `species_catalog`) les espèces
  du modèle (`especes_ia.json`, lu par `IaAssets` : `filesDir/ia/`, sinon assets) absentes du catalogue : nom français avec majuscule (repli latin),
  comestibilité INCONNU (pastille « Non renseigné », fiche « Comestibilité non renseignée — ne pas consommer »),
  `common = false`. Lichens, rouilles, charbons, oïdiums et moisissures (liste de genres dans `IaCatalog.kt`)
  restent dans le modèle mais ne sont ni au catalogue ni proposés.

### Identification par IA (module spot, hors ligne)
- Modèle EfficientNet-B2 (torchvision, poids ImageNet, BSD-3) ré-entraîné sur les espèces observées en France
  (iNaturalist), exporté en ONNX (entrée `image` [1,3,288,288] NCHW RGB normalisée ImageNet, sortie `scores`
  [1,N] logits ; `labels.json` : sortie i = `species[i]`). Exécuté sur le téléphone par ONNX Runtime
  (`com.microsoft.onnxruntime:onnxruntime-android`, aucune permission réseau).
- **Modèle livré par Champimap Connect** (plus dans l'APK ni dans git) : paquet `champi-ia-resultat.tar.gz`
  (champi-ia.onnx, labels.json, especes_ia.json, fiches.json, fiches/*.webp, credits.csv.gz, rapport.txt ;
  Connect ajoute stats.json). APK légère ; nouveau modèle sans réinstaller l'appli.
- Réception (`feature/connect/IaModelImport.kt`) : `ia_manifest.json` (version, empreinte de l'archive, fichiers avec
  taille + SHA-256) ; copie dans `filesDir/ia_tmp/` avec vérification taille + SHA-256 de chaque fichier, fichiers
  obligatoires (onnx, labels, especes_ia) et labels.json lisible, puis bascule par renommage (`IaModel.commitNew`) :
  `ia/` → `ia_precedent/` (secours **le temps de la bascule seulement**), `ia_tmp/` → `ia/`.
  Version, empreinte, nombre d'espèces, version fixée et dernier modèle refusé dans le DataStore `ia_model`.
- `IaAssets` lit `filesDir/ia/` en priorité, sinon les assets de l'APK (compatibilité), sinon « modèle absent »
  (`NoModelIdentifier`). `IaAssets.revision` change à chaque nouveau modèle : session ONNX rechargée, catalogue
  (`SpeciesLoader`) rejoué, photos des fiches relues — sans redémarrer l'appli.
- Test immédiat du nouveau modèle (`OnnxSpeciesIdentifier.validateInstalled`) : session ONNX chargée + une
  inférence de contrôle sur une image neutre (gris moyen) générée en mémoire ; la sortie doit avoir le nombre
  d'espèces de `labels.json` et aucune valeur NaN / infinie.
  - réussite → `ia_precedent/` supprimé automatiquement (`IaModel.dropBackup`, place libérée) ;
  - échec → nouveau modèle supprimé, ancien remis en place et **fixé** (pas de re-téléchargement en boucle),
    entrée au journal, message dans l'écran Connect « Le nouveau modèle n'a pas pu être chargé : l'ancien est
    conservé » (`IaModel.rejectNew`). Bascule interrompue (appli fermée pendant le test) → terminée à la
    synchro suivante ou au premier chargement. Plus aucune version précédente durable.
- Sans modèle : « Trouvé ! → Photo » mène directement à la liste avec « Identification par photo : télécharge le
  modèle avec Champimap Connect (≈ 140 Mo) » + bouton « Ouvrir Champimap Connect ».
- Statistiques d'un modèle : « ia-v1 · 1 032 espèces · réussite 73,8 % (top-5 92,7 %) · 35 Mo · date », lues dans
  stats.json (texte de la release), sinon rapport.txt + labels.json. Affichées dans la liste de choix de Connect
  et dans l'écran Connect de l'appli (modèle installé, menu des versions). Un seul modèle actif (pas de combinaison).
- `OnnxSpeciesIdentifier` : session chargée une fois par version du modèle, en arrière-plan,
  prétraitement identique à l'entraînement (orientation EXIF, petit côté 316 px, recadrage central 288, mean/std),
  softmax, 5 meilleures espèces présentes au catalogue. Erreur → journal (sans donnée personnelle) et liste.
- Écran de résultat (après « Trouvé ! → Photo ») : titre « Ressemble à… » (jamais « C'est un… »), 5 pistes
  (photo de fiche, nom, latin, %, lien discret « Voir la fiche »), toucher = ajout à la récolte ; **jamais de
  verdict de comestibilité** : aucune pastille « Comestible » / « À éviter » sur les pistes (seules TOXIQUE / MORTEL
  restent marquées, c'est de la sécurité). Confiance de la 1re piste < `SEUIL_CONFIANCE` (0,60) → « Identification
  incertaine » en tête (photo floue, angle, espèce peu connue ; reprendre dessus, dessous, pied) et pistes sans
  pourcentages. Rappel fixe en bas (hors défilement) : « Ne consomme jamais un champignon identifié seulement par
  l'appli : fais-le vérifier par un pharmacien ou une société mycologique. » ; « Aucune ne correspond → liste complète ». Bandeau permanent : « Pistes proposées
  par l'IA, jamais une preuve de comestibilité. Fais vérifier en cas de doute (pharmacien, société mycologique). »
  Piste comestible avec sosies toxiques/mortels → avertissement de sosie bien visible ; piste toxique/mortelle →
  fortement marquée. Note fixe + section dépliable « Espèces dangereuses que l'IA ne connaît pas encore »
  (espèces TOXIQUE/MORTEL du catalogue absentes de `labels.json`, calculées au chargement).
- Photos des fiches : `fiches/` + `fiches.json` du modèle (prioritaires), sinon `filesDir/fiches/` ; auteur et
  licence en petit sous chaque photo.
- Paramètres → Données → « Crédits des photos et du modèle » : modèle (EfficientNet-B2, BSD-3 ; ONNX Runtime, MIT),
  photos iNaturalist CC0 / CC BY (nombres), liste complète des auteurs lue en flux depuis `credits.csv.gz`.

### Statistiques (module stats)
- Segmenté Jour / Semaine / Mois / Saison ; total en kg ; barres par mois ; nombre de sorties/spots.
- Podium par poids (1, 2, 3) par personne, chiffres dans des cercles bien centrés.
- « Météo et récoltes » (expérimental) : corrélations calculées UNIQUEMENT sur les récoltes de l'utilisateur
  (pluie cumulée 7/10/15/30 j avant, températures min/max, gel, écart jour/nuit, phase de lune), affichées
  seulement avec assez de données (≥ 10 récoltes d'une espèce), avec « Calculé sur N récoltes · fiabilité ».
  Sinon « Pas encore assez de récoltes pour conclure ».
- « Carte aux trésors » (carte de chaleur des spots productifs) et « Export CSV ».
- Calendrier des sorties (« ce spot a bien donné à cette date »).
- Alertes de saison (WorkManager, 1 vérification par jour) : « Ton spot X donnait bien à cette période ».

### Mode vendeur (expérimental, dossier `feature/ventes`)
- Option des Paramètres (Données, désactivée par défaut, réglage `sellerMode`) : saisie des ventes (date, poids vendu en g, prix au kilo en €, espèce, sorties liées d'une même journée et note facultatives, montant = poids × prix/kg arrondi au centime) depuis la fin de sortie (« Ajouter une vente », poids proposé = poids pesé récolté) et l'écran « Mes ventes » (Stats), section « Ventes » des Stats (chiffre d'affaires, poids vendu, prix moyen au kilo, nombre de ventes, meilleure journée, part vendue / récoltée, répartition par espèce), ventes dans la corbeille, la sauvegarde et l'export CSV ; jamais échangées en sortie à plusieurs.

### Réglages (module reglages)
- Sections repliables + / − (état mémorisé, dépliées par défaut) : Profil (prénom, départements),
  Affichage (Thème : Auto/Clair/Sombre + « Psyché » si débloqué ; Affichage avec émojis ; « Tu dis le g toi ? » ;
  Lien « En cas d'intoxication »), Sécurité et alertes (Verrouillage code ou empreinte ; Mode discret ;
  Alertes de saison), Données (Champimap Connect ; Sauvegarde et export ; Journal ; Preuves « hors ligne »).
  Chaque option a une explication sous son titre. Le mode party n'apparaît PAS dans la liste.
- Bas de page : logo + « Développé en Limousin par PushPull-Tools ».
- Preuves : permissions réellement accordées lues en direct (PackageManager), « 0 accès Internet », lien vers
  le code source GitHub (ouvert dans le navigateur).
- Journal : erreurs seulement, détail repliable, Exporter (texte), Vider.
- Sauvegarde : export complet (base + photos) chiffré (mot de passe, AES-GCM, PBKDF2) ou non, import,
  export CSV, corbeille (restaurer / supprimer définitivement).
- Verrouillage : `LockGate { }` avec BiometricPrompt (empreinte ou code du téléphone).
- Avertissement « Prudence » (`core/ui/StartupWarning.kt`) : plein écran à chaque lancement à froid (état du
  processus, pas au retour d'arrière-plan ni à la rotation), après le verrouillage et avant l'accueil / le premier
  lancement : logo, « Prudence », texte validé (pas un expert, jamais consommer sur la seule foi de l'appli,
  pharmacien ou société mycologique, intoxication : 15 ou centre antipoison). Barre fine 4 s puis passage auto ;
  « J'ai compris » actif après 2 s. SafetyTheme (lisible clair / sombre / party). Un fichier .champi reçu reste en
  attente et l'import s'ouvre juste après.

### Sortie à plusieurs et partage (module groupe)
- Créer : QR code (zxing) avec les infos du groupe ; liaison Bluetooth Low Energy (L2CAP) — Wi-Fi Direct impossible sans permission INTERNET ; Rejoindre : scan.
  Pas de maître : tous égaux, synchro en direct (Bluetooth, paquets toutes les 30–60 s), relais
  (chaque téléphone retransmet), fusion par `updatedAt`, chaque saisie marquée de son auteur, prénom partagé.
  Jusqu'à ~6 en direct.
- Partager un spot : choix « Lieu seul » ou « Lieu + mon historique », « Garder la position GPS dans les
  photos ? » (Non par défaut, données EXIF retirées), par QR code (sans photos) ou fichier `.champi`
  (chiffré, avec photos) via le partage Android. Import d'un fichier reçu.

### Champimap Connect et sécurité (module connect)
- Texte d'explication validé :
  « Champimap ne donnera jamais tes coins à champignons ! » (titre centré)
  « Cette appli n'a pas accès à Internet, et c'est voulu ! » / « Rien ne peut en sortir, tes données
  t'appartiennent. » / « Les cartes, la météo et les mises à jour passent par un petit module séparé, qui ne
  connaît que des zones larges, jamais tes spots. » + 3 étapes numérotées (autoriser l'installation d'applis,
  valider l'installation, c'est fini, autorisation retirable).
- Installation de l'APK Connect embarquée (`assets/connect/champimap-connect.apk`, ajoutée par la CI)
  via PackageInstaller.
- Échange : l'appli lance l'activité Connect (action `fr.pushpull.champimap.connect.SYNC`) avec la liste des
  départements et des zones météo (carrés ~25 km, dates) ; Connect télécharge et renvoie des fichiers via
  FileProvider (URI avec permission de lecture) ; l'appli copie les fichiers. Avancement visible.
- Connect télécharge : cartes vectorielles des départements (extraction PMTiles depuis les builds Protomaps
  par requêtes HTTP Range, emprise du département), météo Open-Meteo (archive + 30 jours avant chaque sortie),
  urgences (Overpass API, amenity=hospital + emergency=yes, emprise du département), mises à jour des centres
  antipoison, **modèle d'identification**. Écran Connect dans les réglages : état, « Lancer la synchro », dernière
  synchro, rappel fixe « Conseillé : en Wi-Fi (cartes et modèle d'identification volumineux) ».
- Modèle d'identification (Connect, `download/IaModelDownloader.kt`, `download/TarGzExtractor.kt`) :
  - versions : `GET https://api.github.com/repos/PushPull-Tools/Champimap/releases` (public, non authentifié,
    aucune donnée envoyée) ; releases non brouillon dont l'étiquette est `ia-vN`, avec l'asset
    `champi-ia-resultat.tar.gz` ; la plus récente = N le plus grand (ia-v1 < ia-v2 < ia-v10) ;
  - demande (rétrocompatible) : `includeIa`, `iaVersionInstalled`, `iaForce` (« Actualiser » complet),
    `iaVersionWanted` (version fixée), `iaChoose` (liste affichée dans Connect, n'est plus utilisé par l'appli),
    `iaListOnly` (Connect 3 : seulement la liste des versions, sans fenêtre « données mobiles ») ;
  - liste des versions (Connect 3) : `ia_versions.json` renvoyé à chaque demande incluant le modèle (tableau, plus
    récente d'abord : `version`, `especes`, `top1`, `top5`, `tailleModeleMo`, `size`, `date`), gardé par l'appli
    dans `filesDir/ia_versions.json` (`feature/connect/IaVersions.kt`) pour le menu des versions, hors ligne ;
  - téléchargement seulement si la version de l'appli est plus ancienne (ou forcé / choisi), reprenable (Range,
    fichier `.part` dans `cacheDir/ia_download/`), % dans la liste d'avancement ; SHA-256 vérifié contre le
    `digest` de l'API (sinon calculé et transmis) ; extraction tar.gz maison (ustar, PAX, GNU ; chemins hors du
    dossier refusés, liens jamais créés) dans `sync/ia/<version>/` ; lecture accordée par préfixe d'URI ;
    `ia_manifest.json` annoncé en dernier ; snapshot.ia = version, empreinte, nombre de fichiers ;
  - version choisie dans le menu (ou remise après un échec) = version **fixée** : la synchro normale ne la
    remplace pas (« La plus récente (automatique) » dans le menu rétablit le suivi).
- Données mobiles (Connect, `util/Network.kt`, permission ACCESS_NETWORK_STATE de Connect uniquement) : si le
  réseau actif n'est pas Wi-Fi/Ethernet non facturé, fenêtre « Tu es sur les données mobiles » avec la taille
  estimée (cartes ≈ 35 Mo par département + modèle ≈ 140 Mo) : « Attendre le Wi-Fi » (rien n'est lancé, l'appli
  affiche « Synchro reportée ») / « Continuer quand même ».
- Écran Connect de l'appli, section « Identification par photo » : interrupteur « Identification par photo
  (≈ 140 Mo, Wi-Fi conseillé) » activé par défaut (la synchro normale inclut le modèle s'il y a plus récent),
  état « Modèle ia-vN installé · X espèces » + statistiques, message d'échec éventuel (« OK » l'efface),
  **menu déroulant** « Version du modèle » : « La plus récente (automatique) », puis chaque version connue avec
  ses stats (« ia-v1 · 1 032 espèces · 73,8 % · 35 Mo », « · installée »), puis « Actualiser la liste (via
  Connect) ». Choisir une version = la fixer : téléchargée à la prochaine synchro, ou tout de suite avec
  « Télécharger cette version » (modèle seul). Mention : « Les versions se téléchargent avec Champimap Connect ;
  l'appli reste hors ligne. » Plus de ligne « Modèle précédent ».
- **Avancement de la synchro** (Connect 4, `snapshot.format = 2`) : Connect diffuse les **vraies tâches, dans
  l'ordre réel d'exécution** : pour chaque département « Contour du département », « Urgences les plus proches »,
  « Carte » (groupe « 87 · Haute-Vienne »), puis le groupe « Général » : « Centres antipoison », « Météo de tes
  sorties · N zones », « Liste des versions du modèle », « Modèle d'identification ». Chaque tâche : identifiant
  (`contour_<code>`, `urgences_<code>`, `carte_<code>`, `antipoison`, `meteo`, `ia_versions`, `ia`), libellé court
  (`title`) et complet (`label`, « Carte · Haute-Vienne »), groupe, état (en attente / en cours / fait / « Déjà à
  jour » (`SKIPPED`, ex. modèle déjà installé) / erreur avec message court / « Annulé » (`CANCELLED`, pas fait)),
  avancement 0–100, taille en octets si connue. Une tâche qui n'a pas eu lieu n'est **jamais** marquée faite.
  Pourcentage global **pondéré par les octets estimés** (carte ≈ 35 Mo par département, modèle = taille de
  l'archive publiée dès qu'elle est connue, sinon ≈ 140 Mo ; petites tâches = poids faible), **monotone**.
  Le build de carte le plus récent est cherché une fois, au début de la première carte.
  Affichage identique dans Connect (écran et notification) et dans l'appli (étape « Préparation de tes cartes »,
  section « Avancement » de l'écran Connect, `feature/connect/SyncTasksUi.kt`) : ligne de résumé
  « 3 tâches sur 9 · 42 % · Carte · Haute-Vienne » + barre, un bloc par département puis « Général », tâche en
  cours surlignée (indicateur + %), tâches faites cochées, erreurs en rouge avec leur message et « Réessayer »
  (relance seulement cette tâche : `ConnectClient.buildRetryIntent`). Ancien Connect (sans `format`) : ancien
  affichage par famille.
- Connect `versionCode` 2 (0.2.0) = premier à gérer le modèle, 3 (0.3.0) = liste des versions, 4 (0.4.0) = liste des
  vraies tâches (`MIN_CONNECT_VERSION_CODE` = 4) ; un Connect plus ancien installé → carte
  « Mise à jour du module nécessaire » + « Mettre à jour le module » (APK incluse, `MIN_CONNECT_VERSION_CODE`).
  Toute évolution du protocole : augmenter `versionCode` de Connect et `MIN_CONNECT_VERSION_CODE`.
- **Publier un nouveau modèle** : créer sur GitHub la release `ia-vN` (N = précédent + 1, non brouillon) avec
  l'asset `champi-ia-resultat.tar.gz` (fichiers à la racine de l'archive), rien d'autre : ni recompilation, ni
  changement de code. Facultatif : dans le texte de la release, le commentaire
  `<!-- STATS {"version":"ia-vN","especes":…,"top1":…,"top5":…,"photosControle":…,"photosEntrainement":…,"architecture":"…","tailleModeleMo":…} -->`
  (champs absents = non affichés). **Les anciennes releases `ia-v*` ne sont jamais supprimées** : elles
  permettent de réinstaller une ancienne version depuis le menu des versions.
- Fiche « En cas d'intoxication » (SafetyTheme, jamais party) : bandeaux « 15 · SAMU », « Centre antipoison »,
  « Urgences » avec boutons-icônes à droite (appeler = ouvre le clavier avec le numéro, ne compose pas ;
  y aller = ouvre l'appli GPS via `geo:`), sélon la position GPS actuelle (repli : zone des cartes).
  Sortie concernée (espèces, photos, quantités), « Les bons réflexes » : « Garde les restes et les épluchures
  · note l'heure du repas et des premiers symptômes · ne te fais pas vomir. ».
  Liste des fiches + nouvelle fiche en cochant les sorties. Centres antipoison français intégrés.

### Bonus (module bonus)
- `PsycheBackground`, `Modifier.psycheCard()`, `champiText`, `emojiFor`, `HiddenRainsOverlay`, `AvatarScreen`
  (signatures fixes). Thème Psyché : fond en dégradé animé (18 s), cartes en dégradé animé (14 s) +
  bordure arc-en-ciel conique qui tourne (4 s) ; animations coupées si « réduire les animations ».
  Party = émojis partout dans les textes, jamais les pictos (sauf easter egg débloqué).
- Déblocage party : tirer fort vers le haut depuis le bas de l'écran (sur-défilement) → « Psyché » apparaît
  dans les thèmes et s'active.
- Émojis (option) : ajoute des émojis liés au texte (ex. « Trouvé ! 🍄 », « Période de chasse 🦺 »).
- « Champignong » : champignon(s) → champignong(s).
- Messages de départ (pioche) : « Je te souhaite une bonne cueillette ! », « Les champignons n'attendent que
  toi ! », « Est-ce que ta danse de la pluie a fonctionné ? », « Tu me gardes une place à ta table ? »,
  « Apparemment Toad serait dans les parages ! », « Paniers prêts, couteau affûté : c'est parti ! »,
  « Les cèpes se cachent, mais pas de toi. », « Ouvre l'œil, et le bon ! », « Aujourd'hui, c'est toi le
  chasseur de chapeaux. », « Silence… on entend presque pousser les girolles. », « Pense à l'omelette du
  retour. », « Que la mousse soit avec toi. », « Regarde bien au pied des châtaigniers… »,
  « Les trompettes sonnent le rassemblement ! », « Mission du jour : remplir le panier, garder le secret. »
- Pluies cachées : 6 / 12 / 24 appuis dans le vide (< 1 s entre appuis) → feuilles / pluie / champignons
  (silhouettes simples), ~4 s, ne bloquent rien.
- Easter egg : AvatarScreen « Oh, tu m'as trouvé ! » : 6 champignons illustrés (cèpe, amanite, girolle, morille,
  trompette, coulemelle), « C'est lui ! » → `avatarKey`, `easterUnlocked = true`.
  Débloque aussi 8 arbres (chêne, châtaignier, hêtre, bouleau, pin, douglas, charme, frêne) et 8 animaux
  (écureuil, hérisson, rouge-gorge, chevreuil, renard, sanglier, chouette, lapin) : pictos Compose
  (`Picto(key, modifier)`) sélectionnables dans l'écran secret et pour les repères de carte (pas de picto par sortie).
- `ForestIllustration(modifier)` : forêt dense du Limousin (lisière, hêtre cuivré, châtaigniers, bouleaux,
  chênes, granite moussu, fougères, bruyère, feuilles, châtaignes, petits champignons, écureuil, rouge-gorge,
  hérisson, chevreuil au loin), style plat.
