# Illustrations des vignettes

Les cartes sans photographie utilisent les fichiers WebP `idee-front/src/assets/illustration-*-simple.webp`. Les photographies importées restent prioritaires. Le choix suit la première catégorie de la sortie ; une catégorie inconnue utilise le vignoble alsacien.

| Catégorie | Illustration |
| --- | --- |
| Nature | Trois sapins, collines et chemin |
| Culture | Château fort avec donjon, créneaux et porte voûtée |
| Marchés | Panier et quelques fruits et légumes |
| Famille | Trois silhouettes se tenant par la main et un arbre |
| Spectacles | Guitare, petite scène et deux notes de musique |
| Ateliers | Palette, pinceau et bol en céramique |
| Autre / sans catégorie | Deux rangées de vignes et une petite maison |

## Création et provenance

Illustrations originales générées le 8 octobre 2026 avec l’outil intégré `image_gen`, suivant le skill `imagegen`. Ce sont des scènes imaginaires d’ambiance, sans représentation garantie d’un lieu ou d’un événement réel. Elles ne proviennent pas de DATAtourisme et ne doivent pas recevoir les crédits des photographies importées.

Les sept images sont enregistrées dans le projet en WebP, largeur 800 px, qualité 82, avec chargement différé. Leurs références se trouvent dans `outing-presentation.ts`.

## Prompts de création

La série active privilégie quelques grandes formes lisibles, des contours doux, un fond crème dégagé et une palette sauge, terre cuite et ocre. La texture gouache reste très discrète. Pas de décor chargé ni de petits détails.

Les prompts complets de chaque catégorie sont conservés dans [illustrations-prompts.json](illustrations-prompts.json). Génération avec l’outil intégré `image_gen`, sans référence photographique.

La première série plus détaillée, `illustration-*.webp` sans suffixe `-simple`, est conservée comme variante ; elle n’est plus utilisée par les cartes.
