# Retours de test — à traiter

État au 28/10/2026. ✅ = fait (branche dev, compile sur la CI), ⏳ = à faire.

## Lot 1 (premier test)
- ✅ Installation de Connect : explication d'abord, réglage Android sur bouton, reprise auto, jamais bloqué.
- ✅ Carte : noms des communes, lieux-dits, routes, chemins, cours d'eau ; polices dans l'APK ; zoom 15.
- ✅ Verrou par récolte de spot (appliqué aussi dans les données).
- ✅ Couleurs en thème sombre (texte par défaut du thème, titres, icônes).
- ✅ Filtre GPS : trace sans zigzags à l'arrêt, point bleu lissé, immobilité détectée.
- ✅ Photo pour une espèce déjà présente : rattachée, plus de doublon.
- ✅ Mémo vocal (boutons courts), libellé de semaine, sélecteur de date Material3, jour courant du calendrier, nom de spot sans coordonnées.

## Lot 2 (écran Spot et recherche) — compile, à tester
- ✅ Pas de message d'erreur « IA » : photo → choix d'espèce avec une info stable.
- ✅ Recherche d'espèces : index A–Z à droite, sections Récentes / De saison, filtres Comestible / Mortel…, recherche plus fluide (index en mémoire, recherche hors du fil principal).
- ✅ Barre de recherche : même style partout.
- ✅ « Trouvé ! » : pas d'aspect « sélectionné », choix Photo / Liste en feuille du bas (pas de menu déroulant).
- ✅ Champignons récoltés repliables (résumé replié, édition dépliée).
- ✅ Infos du spot en haut, en lecture compacte, crayon « Modifier ».
- ✅ Essences d'arbres en pastilles multiples + « Autre » libre.
- ✅ Affichage épuré partout, contenu dans des sections dépliables ; reprise page par page.

## Lot 3 (divers) — compile, à tester
- ✅ Accord « sortie / sorties », « spot / spots » partout.
- ✅ Phrase de départ affichée au lancement de la sortie, plus sur l'accueil.
- ✅ Mode party : l'étirement n'a aucun effet visible (fond et contours doivent bouger).
- ✅ Pluies cachées : aucune animation en tapotant.
- ✅ Carnet : info-bulle « ce qui rapporte quoi » (XP).
- ✅ Carnet : phrase sarcastique du succès « champignong » à remplacer.
- ✅ Connect : « Actualiser les cartes » au lieu de « Retélécharger ».
- ✅ Nouveau département coché : proposer « Synchroniser maintenant » (ou indiquer le chemin) ; la synchro n'ajoute que les nouveautés, la synchro complète reste dans Paramètres.
- ✅ Pictos débloqués (arbres, animaux) sélectionnables en haut, comme ceux des champignons (contenu en plus).

- ✅ Picto par sortie : abandonné (la date reste affichée).

## Idées (après la base)
- ✅ Pages de transition : démarrage et fin de sortie (cercle façon iris, phrase de départ ~2 s ; récap animé : chiffres qui comptent, XP en direct ligne par ligne, passage de niveau, succès un par un ; toucher pour passer, « animations réduites » respecté).
- Sortie à plusieurs : retrouver le téléphone d'un membre (dernière position connue) et le faire sonner fort.
- Seul : le téléphone sonne fort quand on siffle (détection de sifflement en local).
- ✅ Option vendeur : dans Paramètres, marquée « Expérimental — pourra être modifiée ou supprimée à la sortie officielle » ; ventes par sortie/jour (prix au kilo, poids vendu), stats de ventes. Paiement (Play Store, compte dév existant) plus tard.
- ✅ APK : garder arm64-v8a + armeabi-v7a (tous les vrais téléphones), retirer x86/x86_64 (émulateurs seulement). Taille APK non bloquante.
- ✅ Photos des espèces et identification par IA (modèle entraîné embarqué) ; entraînement dans un conteneur Docker (PC de jeu avec GPU recommandé, serveur possible mais lent).

## Couverture France (puis monde)
- ⏳ IA : entraînée sur le catalogue + toutes les espèces de champignons observées en France sur iNaturalist (≥ 15 observations, ~2 000 à 3 000 espèces). Lieu paramétrable pour d'autres pays plus tard.
- ✅ Catalogue de l'appli : `especes_ia.json` importé (noms latins + français) ; espèces sans fiche de comestibilité = « Comestibilité non renseignée — ne pas consommer » ; lichens, rouilles, moisissures masqués. ⏳ Relecture par un expert des espèces ajoutées.

## IA intégrée (modèle ia-v1) — compile, à tester
- ✅ Modèle `ia-v1` (1 032 espèces, top-1 73,8 % · top-5 92,7 %) publié en release GitHub, jamais dans git (désormais livré par Connect, voir plus bas).
- ✅ Identification hors ligne (ONNX Runtime) : 5 pistes avec photo, pastille et %, bandeau de prudence, sosies dangereux, pistes toxiques/mortelles marquées, espèces dangereuses inconnues du modèle.
- ✅ Photos des fiches (3 327, auteur + licence sous chaque photo) et écran « Crédits des photos et du modèle ».
- ⏳ À vérifier sur téléphone : temps d'analyse, pertinence sur de vraies photos, taille de l'APK.

## Modèle IA livré par Connect — compile, à tester
- ✅ Modèle IA livré par Connect, APK légère : la CI n'embarque plus le modèle ; Connect trouve la dernière release
  `ia-vN` (API publique GitHub), télécharge (reprenable, %), vérifie le SHA-256, extrait et remet le modèle à l'appli.
- ✅ Appli : réception vérifiée (tailles + SHA-256) puis bascule atomique, modèle rechargé sans redémarrer,
  catalogue rejoué ; sans modèle, « Trouvé ! → Photo » mène à la liste avec un lien vers Connect.
- ✅ Ancien modèle gardé seulement pendant la bascule : le nouveau est testé tout de suite (session + inférence de
  contrôle sur image neutre, nombre de sorties, pas de NaN) ; réussite → ancien supprimé ; échec → retour
  automatique à l'ancien, nouveau supprimé, journal + message dans l'écran Connect. Plus de « Revenir à ce
  modèle » / « Supprimer ».
- ✅ Menu déroulant « Version du modèle » dans l'écran Connect de l'appli : la plus récente (automatique) ou une
  version précise avec ses stats, liste reçue de Connect (`ia_versions.json`, « Actualiser la liste (via
  Connect) »), « Télécharger cette version ». Connect 0.3.0 (versionCode 3, `MIN_CONNECT_VERSION_CODE` = 3).
- ✅ Statistiques de chaque modèle (stats.json / rapport.txt) dans la liste de Connect et l'écran Connect.
- ✅ Wi-Fi conseillé : fenêtre « Tu es sur les données mobiles » dans Connect (taille estimée), rappel fixe dans l'appli.
- ⏳ À vérifier sur téléphone : lecture des fichiers par préfixe d'URI, reprise après coupure, durée de la copie
  (~150 Mo), place libre nécessaire (jusqu'à ~600 Mo au pic : archive + extraction + copie + ancien modèle
  pendant le test) ; menu des versions et test d'un modèle volontairement cassé.

## Sécurité IA et démarrage — compile, à tester
- ✅ Propositions de l'IA sans verdict de comestibilité : « Ressemble à… », lien « Voir la fiche », TOXIQUE / MORTEL et sosies toujours marqués, « Identification incertaine » sous 60 % (sans pourcentages), rappel fixe en bas ; avertissement « Prudence » plein écran (4 s, « J'ai compris » après 2 s) à chaque lancement à froid.

## Captures d'écran automatiques — fait (CI)
- ✅ Job CI `captures` (Robolectric 4.14 rendu natif + Roborazzi 1.32), séparé de l'APK (n'en bloque jamais la publication) :
  chaque écran en PNG 1080 × 2400, thèmes clair / sombre / Psyché, rangés par parcours (`1-premier-lancement`,
  `2-sortie`, `3-menu`), écrans longs en plusieurs hauteurs.
- ✅ Branche `captures` : `captures/derniere/index.html` (planche : une section par parcours, 3 colonnes) et
  `rapport.txt` (écrans manquants) ; aussi `captures/<sha>/` et l'artefact « Captures » du job.
- ✅ Données factices en base Room en mémoire (spots imaginaires, saison de sorties, récoltes, ventes, météo) ; IA factice.
- Limites connues : la carte MapLibre (GPU) est remplacée par une zone neutre ; « Sortie à plusieurs » reste sur
  l'étape « Autoriser le Bluetooth » (le QR code demande le vrai Bluetooth) ; la mesure « Marquer un spot » est
  montrée en tout début (positions simulées).
