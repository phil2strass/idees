# URL publiques et préparation du multilingue

Les fiches françaises localisées utilisent `/{departement}/{commune}/{slug-sortie}`, sans préfixe de langue. Exemple de structure : `/bas-rhin/strasbourg/exposition-wurth`.

La migration Liquibase `012-public-urls.sql` crée le référentiel géographique, rattache les lieux existants à une commune et génère les adresses des fiches publiées. Les prochaines créations et les imports sont pris en charge par les triggers PostgreSQL, y compris les écritures directes. Les fiches sans commune exploitable conservent `/sorties/{slug}` ; leur URL est créée lorsque la fiche est mise à jour avec une localisation.

## Identité, langues et stabilité

- `idee_department` et `idee_department_translation` : codes stables 67/68, noms et slugs par langue.
- `idee_city` et `idee_city_translation` : communes identifiées indépendamment de leur traduction. Le rattachement initial utilise le département et le nom normalisé. Les codes INSEE des sources DATAtourisme sont repris lorsque le département et le nom concordent. Les graphies contradictoires ne sont pas fusionnées automatiquement.
- `idee_place.city_id` : commune du lieu principal. Les autres lieux d’une sortie ne créent pas d’adresses concurrentes.
- `idee_outing_url` : slug éditorial propre à chaque couple sortie/langue.
- `idee_public_route` : chemin complet, sortie, langue et indicateur d’URL canonique. Les anciens chemins restent réservés à leur sortie ; une ancienne adresse redirige directement vers l’adresse courante.

Les slugs utilisent les minuscules, les tirets et une normalisation des accents latins, des ligatures et d’Unicode. Le titre détermine le slug à la première publication localisée. Un changement de titre ou de localisation lors d’un import ne modifie pas une URL déjà publiée. Les collisions, y compris avec un ancien chemin, reçoivent un suffixe basé sur l’identifiant de la sortie, puis un compteur si nécessaire.

Pour corriger volontairement une URL française, effectuer dans une transaction la modification de `idee_outing_url.slug` (ou du référentiel géographique), puis appeler `SELECT idee_publish_outing_url(id_de_la_sortie)`. Pour une correction géographique partagée, appeler la fonction pour chaque sortie concernée. La fonction conserve les anciens chemins et garantit une seule URL canonique française par fiche. Cette opération reste une opération SQL d’administration ; aucun écran d’édition n’est ajouté.

Les langues en/de/it/nl/es sont maintenant publiées par la migration 016 et l’interface Angular multilingue. Le format est `/{langue}/{departement}/{commune}/{slug-traduit}`, par exemple `/en/bas-rhin/rossfeld/blood-donation`. Le slug provient du titre OpenAI déjà enregistré ; la migration reprend les traductions existantes et un trigger publie les prochaines, sans nouvelle génération. Département et commune utilisent leurs traductions enregistrées si elles existent, avec repli sur la graphie française.

Les slugs traduits restent stables lors des nouvelles traductions/imports. Pour une correction éditoriale, modifier `idee_outing_url.slug` pour la langue concernée et appeler `SELECT idee_publish_translated_outing_url(id, 'en')` dans la même transaction. Les anciens chemins, y compris les liens précédemment préfixés avec le slug français, restent réservés et redirigent vers la nouvelle canonique. Les collisions avec ces alias et les URL d’autres sorties sont suffixées avec l’identifiant stable.

Une traduction de contenu devenue obsolète est masquée, mais l’adresse reste réservée et accessible avec le français de secours. Les brouillons/archives restent inaccessibles dans toutes les langues. La fiche peut rester accessible sous un préfixe avec un slug français si sa traduction n’existe pas encore. Voir [site multilingue](multilingual.md).

## API et serveur

Les listes et détails exposent `url` (français) et `urls` (adresses canoniques publiées par langue). Pour rester compatible avec une API pas encore mise à jour, le frontend utilise `/sorties/{slug}` lorsque `url` manque et essaie l’ancien endpoint de détail si le résolveur de chemins renvoie 404 sur une adresse historique. Ce repli ne s’applique jamais aux chemins géographiques. Le champ `slug` existant reste l’identifiant technique des API d’import et de description ; les intégrations existantes peuvent continuer à utiliser `/api/outings/{slug}`.

`GET /api/outing-by-path?path=...` résout le chemin complet, historique ou actuel, et retourne la fiche avec son URL courante. Un département ou une commune arbitraire ne suffit pas à retrouver une fiche. Les brouillons, archives et chemins non enregistrés retournent 404.

Nginx transmet désormais les pages à Angular SSR. Le composant de fiche résout le chemin auprès de l’API et fixe le statut HTTP via `RESPONSE_INIT` : 301 pour une ancienne adresse, 404 pour une fiche introuvable et 503 lorsque l’API est indisponible. La page valide contient son contenu et sa canonique dès la réponse HTML. Voir [rendu serveur](server-rendering.md).

L’ancien endpoint `/api/public-page` reste en Java pour compatibilité mais n’est plus appelé par Nginx. Le serveur de développement Angular utilise également SSR après rechargement de sa configuration. Pendant la navigation côté navigateur, les anciennes adresses sont remplacées avec conservation des paramètres et de l’ancre. Les paramètres sont conservés dans les redirections HTTP 301 SSR ; les URL de partage et canoniques n’en contiennent pas.

## Activation et vérification

La migration s’applique au prochain démarrage de l’API mise à jour. Déployer frontend, API et configuration Nginx ensemble avec le mécanisme existant. La modification du code ne redémarre aucun serveur et ne déploie rien automatiquement.

Vérifications disponibles :

```bash
mvn -f idee-service/pom.xml test
(cd idee-front && npm run build)
python3 scripts/check_public_urls.py
node tests/test_public_urls.cjs
./deploy.sh --check
```

Le contrôle Python utilise les identifiants locaux de `.env`, crée un schéma temporaire dans une transaction, applique les migrations à des fiches fictives préexistantes et annule intégralement la transaction. Les données réelles ne sont pas modifiées.

Le test navigateur sert le build via interception Playwright, sans démarrer de serveur. `IDEE_PLAYWRIGHT_MODULE` et `IDEE_CHROMIUM_PATH` permettent de sélectionner une installation existante.

`PublicUrlsDatabaseTest` et `DatatourismeDatabaseTest` s’activent avec `IDEE_DATATOURISME_DB_TEST=isolated` et une datasource pointant vers un schéma jetable `idee_datatourisme_test_*` ; Liquibase doit utiliser ce même schéma. Ils couvrent la résolution complète, les collisions, les corrections, les anciennes URL, les statuts HTTP, les archives et la stabilité lors des imports.
