# Site multilingue Angular

Le bandeau propose Français, English, Deutsch, Italiano, Nederlands et Español. Le sélecteur garde la page courante, les paramètres, l’ancre et les filtres du catalogue lors de la navigation Angular. La langue est portée par l’adresse, sans dépendre du stockage du navigateur : un lien partagé et un accès direct rendent la même langue côté serveur et à l’hydratation.

- Français : `/` et `/bas-rhin/strasbourg/exposition-wurth`.
- Anglais : `/en` et `/en/bas-rhin/strasbourg/wurth-exhibition`.
- Autres préfixes : `/de`, `/it`, `/nl`, `/es`.

La migration 016 publie le slug de sortie à partir du titre traduit enregistré, par exemple `/en/bas-rhin/rossfeld/blood-donation`. Le slug est conservé lors des prochaines réécritures de titre. Les segments département et commune utilisent leur traduction en base lorsqu’elle existe, sinon leur graphie française. Les adresses précédentes avec slug français restent des alias et redirigent vers la canonique traduite.

Les traductions déjà présentes sont reprises par la migration ; les suivantes publient leur URL à leur enregistrement, sans appel supplémentaire à OpenAI. L’API expose `urls`, une correspondance langue/adresse, sur les listes et détails. Angular utilise cette correspondance pour les vignettes, les changements de langue, le partage, les canoniques et `hreflang`.

## Interface et contenus

`idee-front/src/app/translations.ts` contient les libellés de l’interface, avec les clés françaises et les cinq traductions. `LanguageService` centralise la langue, les liens, les paramètres des messages et le choix des contenus. Les dates, heures, montants, noms de langues et le calendrier Material suivent la langue sélectionnée. Les dates restent calculées dans le fuseau de la sortie ; les filtres utilisent les mêmes valeurs métier dans toutes les langues.

Les vignettes et fiches affichent le titre et les descriptions générées par OpenAI Batch lorsqu’ils sont disponibles et à jour. Sans traduction enregistrée, le contenu français reste affiché et porte `lang="fr"`. Le changement de langue ne crée aucun traitement payant.

Les autres données fournies par la source (consignes pratiques, conditions tarifaires, notes, noms de lieux, textes des documents, etc.) restent dans leur langue d’origine : le batch actuel couvre le titre et les deux descriptions uniquement.

`GET /api/catalog?language=en` ajoute les traductions courtes et titres à jour en une requête groupée, sans les descriptions longues. La recherche porte sur les titres/résumés français, la ville et les titres/résumés traduits à jour de la langue demandée. Le français reste la valeur par défaut ; les langues inconnues renvoient 400. Le détail expose les cinq traductions déjà disponibles via `translations`.

## Rendu serveur et référencement

Chaque version est rendue côté serveur avec son attribut HTML `lang`, son titre, sa description SEO et son URL canonique. Des liens `hreflang` relient les six versions, avec `x-default` vers le français. Les anciennes adresses techniques redirigent vers la même langue ; les chemins invalides et langues inconnues restent des 404. Les contenus de sorties encore non traduits retombent sur le français, même si l’interface est traduite.

Il faut mettre à jour l’API et le frontend ensemble pour afficher les traductions sur le catalogue. Aucun serveur n’est redémarré automatiquement par cette modification.

## Vérification

Après `npm --prefix idee-front run build` :

```bash
node tests/test_ssr.mjs
IDEE_PLAYWRIGHT_MODULE=/chemin/vers/playwright node tests/test_languages.cjs
```

Ces tests utilisent le vrai bundle SSR et le navigateur avec des réponses API simulées, sans serveur applicatif. Ils couvrent les cinq traductions, la navigation, le partage des adresses, les métadonnées, les alias, le français de secours, le calendrier et le mobile. `python3 scripts/check_description_jobs.py` vérifie également la recherche traduite et le masquage des traductions obsolètes dans des schémas PostgreSQL jetables, clés fournisseurs désactivées.
