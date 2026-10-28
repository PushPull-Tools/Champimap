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
- Début d'aventure au lancement d'une sortie : cercle qui se ferme + UNE mélodie de démarrage originale (style Zelda, flûte/pipeau doux), phrase de départ affichée à ce moment ; son coupable.
- Sortie à plusieurs : retrouver le téléphone d'un membre (dernière position connue) et le faire sonner fort.
- Seul : le téléphone sonne fort quand on siffle (détection de sifflement en local).
- ✅ Option vendeur : dans Paramètres, marquée « Expérimental — pourra être modifiée ou supprimée à la sortie officielle » ; ventes par sortie/jour (prix au kilo, poids vendu), stats de ventes. Paiement (Play Store, compte dév existant) plus tard.
- ✅ APK : garder arm64-v8a + armeabi-v7a (tous les vrais téléphones), retirer x86/x86_64 (émulateurs seulement). Taille APK non bloquante.
- ✅ Photos des espèces et identification par IA (modèle entraîné embarqué) ; entraînement dans un conteneur Docker (PC de jeu avec GPU recommandé, serveur possible mais lent).

## Couverture France (puis monde)
- ⏳ IA : entraînée sur le catalogue + toutes les espèces de champignons observées en France sur iNaturalist (≥ 15 observations, ~2 000 à 3 000 espèces). Lieu paramétrable pour d'autres pays plus tard.
- ✅ Catalogue de l'appli : `especes_ia.json` importé (noms latins + français) ; espèces sans fiche de comestibilité = « Comestibilité non renseignée — ne pas consommer » ; lichens, rouilles, moisissures masqués. ⏳ Relecture par un expert des espèces ajoutées.

## IA intégrée (modèle ia-v1) — compile, à tester
- ✅ Modèle `ia-v1` (1 032 espèces, top-1 73,8 % · top-5 92,7 %) téléchargé par la CI depuis la release GitHub, jamais dans git (`IA_RELEASE` dans `build.yml`).
- ✅ Identification hors ligne (ONNX Runtime) : 5 pistes avec photo, pastille et %, bandeau de prudence, sosies dangereux, pistes toxiques/mortelles marquées, espèces dangereuses inconnues du modèle.
- ✅ Photos des fiches (3 327, auteur + licence sous chaque photo) et écran « Crédits des photos et du modèle ».
- ⏳ À vérifier sur téléphone : temps d'analyse, pertinence sur de vraies photos, taille de l'APK.
