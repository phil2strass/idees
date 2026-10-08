# Idées de sorties en Alsace

Premier socle issu de Wik : Angular 22, thème SCSS Modernize/Wik (couleurs, typo, navigation et cartes), Spring Boot 3.4.2 / Java 21, PostgreSQL et Liquibase. Nouveau logo vectoriel `idee-icon.svg`. Aucun compte, écran de connexion ni Keycloak. L’API d’écriture exige une clé d’import privée.

- `idee-front` : interface publique, recherche, filtres, fiches et prochains créneaux.
- Les fiches localisées utilisent `/{departement}/{commune}/{slug-sortie}`. L’API fournit `url` pour les liens, le partage et la balise canonique ; les anciennes pages `/sorties/{slug}` redirigent en HTTP 301 dans le rendu serveur. Les URL restent stables après import. Voir [URL publiques et multilingue](docs/public-urls.md).
- `idee-service` : catalogue public et migrations de la base `idee`.
- `scripts/project_calendar.py` : calcul borné des occurrences iCalendar.
- [Modèle de données](docs/data-model.md) : relations, règles et exemples.
- [Import DATAtourisme](docs/datatourisme.md) : catalogue des événements 67/68, reprise de pagination, mises à jour quotidiennes et suivi PostgreSQL.
- Descriptions Mistral automatiques : les sorties publiées nouvelles ou dont la description française a changé sont mises en file après l’import. Le worker régénère `description_longue` et `description_courte` via des batches Mistral de 500 maximum, avec reprises sur erreur, lorsque `MISTRAL_API_KEY` est configurée. Suivi protégé : `/api/admin/descriptions/status`. Voir [la documentation DATAtourisme](docs/datatourisme.md).
- Traductions automatiques via OpenAI Batch : après Mistral, titre et descriptions longue/courte en anglais, allemand, italien, néerlandais et espagnol. Un changement de titre seul traduit uniquement le titre et conserve les descriptions traduites. Les contenus à jour sont réutilisés ; les routes manuelles restent disponibles pour le rattrapage. [Commandes et suivi](docs/api-mcp.md#traductions-du-titre-et-des-descriptions-avec-openai-batch).
- Synchronisation à la demande : `POST /api/admin/datatourisme/sync` avec la clé d'import ; met à jour les fiches modifiées depuis le précédent parcours et ajoute les nouvelles sans doublon. Suivi : `GET /api/admin/datatourisme/status`.
- Historique des imports : table `idee_import_run` et `GET /api/admin/datatourisme/imports`, protégés par la clé d’import. Dates et compteurs de sorties créées, modifiées, mises en file Mistral et traductions des descriptions/titres. Voir [les règles de comptage](docs/datatourisme.md#historique-des-imports).
- Sur OVH, le timer systemd `idee-import.timer` lance le service Java natif d’import chaque jour à 04:00 UTC (06:00 Paris en été, 05:00 en hiver). Configuration et suivi dans [la documentation DATAtourisme](docs/datatourisme.md).

## Démarrer

Prérequis : Java 21, Maven, Node 22.23+ compatible avec le package.json, PostgreSQL 16, Python 3 avec python-dateutil (`pip install -r requirements.txt` dans un environnement virtuel si nécessaire).

Créer la base une seule fois avec le compte administrateur PostgreSQL :

```bash
sudo -u postgres createdb -O htpweb idee
cp .env.example .env
# Renseigner DB_PASSWORD dans .env.
./start-front.sh
```

Le front est sur http://localhost:4401, le service sur http://127.0.0.1:8087. Le proxy Angular transmet `/api` au service. Les migrations sont appliquées au démarrage et les occurrences calculées ensuite. Arrêter avec Ctrl+C. Le script ne modifie pas Wik et ne crée pas de compte utilisateur.

Les dépendances frontend sont propres au projet, avec Angular SSR et les types Node 22 : `cd idee-front && npm ci`. Le lien local historique vers les dépendances de Wik a été remplacé par une installation indépendante.

## Commandes API Batch

Pour lancer l’import des sorties des départements 67 et 68, avec chargement automatique de la clé d’import depuis `.env` :

```bash
./import-outings.sh
```

Suivre le travail sans lancer de nouvel import (la clé est chargée automatiquement) :

```bash
./import-outings.sh --status   # Import, descriptions Mistral et traductions
./import-outings.sh --watch    # Actualisation toutes les 10 secondes ; Ctrl+C pour arrêter
./import-outings.sh --history  # Dates, créations, mises à jour et traitements par import
```

Pour l’import, `sync_requested=true` indique une demande en attente, `running=true` un parcours en cours ; `pages` et `objects` augmentent à chaque page validée. `last_error` explique une reprise différée, et `nextRequestAt` indique la prochaine requête autorisée. Une synchronisation incrémentale peut terminer sans nouvelle sortie si la source ne fournit aucun changement. Pour Mistral et OpenAI, `enabled`/`configured` indiquent si le worker peut traiter la file ; `pending`, `inBatch`, `activeBatches` et les états/erreurs des batches montrent l’avancement. Les compteurs d’import représentent les mises en file, pas les résultats terminés. Si l’historique renvoie 404, redémarrer l’API avec la nouvelle version.

L’API doit être démarrée avec `DATATOURISME_API_KEY` et `DATATOURISME_ENABLED=true`. Le script demande la synchronisation puis rend la main ; l’import continue en arrière-plan, suivi automatiquement de Mistral et des traductions avec leurs clés et options activées. Les demandes répétées préservent l’import déjà en cours. L’URL locale par défaut est `http://127.0.0.1:8087`, configurable avec `IDEE_API_BASE_URL`.

L’API doit être démarrée avec les clés fournisseurs configurées dans son `.env` privé : `MISTRAL_API_KEY` et `MISTRAL_AUTO_ENABLED=true` pour les descriptions françaises ; `OPENAI_API_KEY` et `OPENAI_TRANSLATIONS_ENABLED=true` pour les traductions. Après modification de ces variables, relancer l’API pour les prendre en compte.

Depuis la racine du projet, charger la clé d’import dans le terminal et choisir l’API locale :

```bash
set -a
source .env
set +a
export IDEE_API_BASE_URL='http://127.0.0.1:8087'
test -n "$IDEE_IMPORT_TOKEN" && echo 'Clé chargée' || echo 'Clé absente'
```

Pour la production, définir `IDEE_API_BASE_URL='https://ideesdesorties.eu'` et utiliser son `IDEE_IMPORT_TOKEN`, distinct de celui du développement.

### Descriptions françaises avec Mistral Batch

Mettre en file jusqu’à **500 sorties** sans descriptions françaises longue et courte à jour :

```bash
curl --max-time 30 --fail-with-body -X POST "${IDEE_API_BASE_URL}/api/admin/descriptions/generate-missing?limit=500" -H "Authorization: Bearer ${IDEE_IMPORT_TOKEN}"
```

Consulter l’avancement sans lancer de nouveau lot :

```bash
curl --max-time 30 --fail-with-body "${IDEE_API_BASE_URL}/api/admin/descriptions/status" -H "Authorization: Bearer ${IDEE_IMPORT_TOKEN}"
```

### Traductions avec OpenAI Batch

Le script charge automatiquement `IDEE_IMPORT_TOKEN` depuis le `.env` du projet et met toutes les traductions manquantes en file :

```bash
./generate-all-translations.sh
```

L’API locale doit être démarrée. Pour une autre installation, définir `IDEE_API_BASE_URL` et utiliser sa clé d’import dans `.env`. La clé reste dans `.env`, ignoré par Git.

Mettre en file **toutes les traductions manquantes** en un appel, sans limite de 500 sorties (titre, description longue et courte dans les cinq langues) :

```bash
curl --max-time 120 --fail-with-body -X POST "${IDEE_API_BASE_URL}/api/admin/translations/generate-all-missing" -H "Authorization: Bearer ${IDEE_IMPORT_TOKEN}"
```

La réponse 202 indique `scope: "all"`, `accepted` (sorties ajoutées) et `requests` (demandes par langue). Le traitement continue en arrière-plan par batches. Les traductions à jour et les demandes déjà en file sont ignorées. Seules les sorties ayant des descriptions françaises générées à jour sont éligibles ; les nouvelles générations françaises déclenchent ensuite automatiquement leurs traductions.

Mettre en file jusqu’à **500 sorties**, pour traduire leur **titre, description longue et description courte** en anglais, allemand, italien, néerlandais et espagnol. Les descriptions françaises générées doivent être à jour. Cela représente au maximum **2 500 demandes**, une par sortie et par langue :

```bash
curl --max-time 30 --fail-with-body -X POST "${IDEE_API_BASE_URL}/api/admin/translations/generate-missing?limit=500" -H "Authorization: Bearer ${IDEE_IMPORT_TOKEN}"
```

Consulter l’avancement sans lancer de nouveau lot :

```bash
curl --max-time 30 --fail-with-body "${IDEE_API_BASE_URL}/api/admin/translations/status" -H "Authorization: Bearer ${IDEE_IMPORT_TOKEN}"
```

La réponse `accepted` indique les sorties ajoutées à la file, **pas les traitements terminés**. Répéter le POST pour ajouter le lot suivant ; les demandes déjà en file ou à jour sont ignorées. `accepted: 0` signifie qu’aucune nouvelle demande éligible n’a été ajoutée, même si des lots restent en cours. `limit` peut être compris entre 1 et 500.

Laisser l’API active pour envoyer les batches et récupérer leurs résultats en arrière-plan. Les POST ci-dessus répondent après la mise en file, sans attendre la génération. Les sorties sans source française exploitable, les brouillons et les démonstrations ne sont pas sélectionnés. Voir [les détails des API et reprises](docs/api-mcp.md).

## Données de démonstration

Six fiches **fictives** permettent de voir le résultat sans annoncer de vrais événements. La colonne `is_demo` alimente un avertissement visible. Le contexte de démarrage par défaut est désormais `production` : une base neuve reste vide de sorties. Les exemples ne sont chargés que si `DB_CONTEXTS=demo` est explicitement choisi pour une nouvelle base de démonstration. Changer le contexte ne supprime pas des exemples déjà chargés.

## Vérifier

```bash
python3 -m unittest discover -s scripts -p 'test_*.py' -v
mvn -f idee-service/pom.xml test
cd idee-front && npm run build
```

Les tests métier couvrent les récurrences ordinales, les exceptions, les reports, les intervalles multi-jours, les fins exclusives et les changements d’heure. `scripts/check_database.py` vérifie l’API et les contraintes sur une base initialisée avec les exemples (inspections et transactions annulées).

## Exploitation

Le service recalcule le calendrier au démarrage et chaque nuit à 03 h 15 (Europe/Paris). Les créations par API calculent leurs occurrences immédiatement. Pour les modifications SQL directes uniquement, relancer `python3 scripts/project_calendar.py` en chargeant les variables DB de `.env`. La projection par défaut va de J−31 à J+550. Elle est recalculée dans une transaction : un échec préserve la projection précédente. La table `idee_calendar_projection` indique sa couverture réelle.

L’accueil et les fiches utilisent le rendu serveur Angular : Apache transmet les pages au service Node `idee-ssr`, qui sert aussi les assets, et `/api` au service Java `idee-api`. Le HTML initial contient le contenu et les métadonnées ; Angular reprend ensuite les interactions par hydratation. Voir [rendu serveur et configuration](docs/server-rendering.md). Le déploiement utilise PostgreSQL natif et des services systemd pour Java, Node et l’import quotidien. Sur OVH, le timer quotidien lance import → Mistral → traductions à 04:00 UTC (06:00 Paris en été, 05:00 en hiver). Les workers Mistral/OpenAI de l’API permanente sont désactivés par le lanceur natif ; les batches en attente sont repris le lendemain. Suivi : `ssh ovh "sudo journalctl -u idee-import.service --since today -f"`. Voir [le fonctionnement et le suivi](deploy/README.md#import-quotidien-et-traitements).

Ce premier lot fournit le catalogue public et son schéma. La création de vraies sorties est disponible via une API protégée et un pont MCP stdio ; voir [API et MCP](docs/api-mcp.md). La modification et l’administration complète restent un prochain lot. Les horaires d’ouverture saisonniers sont modélisés ; le filtre par date porte pour l’instant sur les événements datés, et les sorties permanentes ont leur filtre distinct.

## Déploiement et intégration

Depuis la racine du projet, pour mettre à jour l’installation native existante sur OVH :

```bash
# Vérifier les prérequis OVH sans modification distante
./deploy.sh --doctor

# Déployer en conservant les données distantes
./deploy.sh

# Déployer et remplacer les données distantes par la base locale
./deploy.sh --replace-db

# Vérifier les sources et les options, sans connexion ni déploiement
./deploy.sh --check --replace-db
```

**État OVH :** le serveur a été migré vers les services natifs le 8 octobre 2026, avec remplacement de la base par la copie locale et transfert des images. Les prochains déploiements peuvent utiliser les commandes ci-dessus. Les sauvegardes de l’ancienne installation sont conservées ; voir [le compte rendu OVH](deploy/README.md#état-ovh-après-la-migration-du-8-octobre-2026).

**Première migration d’un autre serveur :** si le diagnostic indique que `.venv`, `.runtime/current` ou les services natifs sont absents, `deploy.sh` ne peut pas encore mettre à jour ce serveur. Retirer les fichiers Docker du dépôt ne migre pas une installation déjà en production. Préparer d’abord la [migration native du serveur](deploy/README.md#migration-dune-installation-existante), puis relancer le déploiement. Le script vérifie ces prérequis avant d’exporter la base locale.

La cible par défaut est `ovh:/home/debian/idee`, pour le site `https://ideesdesorties.eu`. Le script reconstruit le frontend et l’API, sauvegarde la base distante, conserve les secrets et vérifie le site public.

**Avec `--replace-db`, les données distantes sont écrasées par celles de la base configurée dans `.env` sur ta machine.** La destination est la base configurée dans `deploy/.env` sur OVH. Le script arrête les traitements et le site, sauvegarde la base distante dans `backups/deploy-*/database.dump`, remplace le schéma applicatif `public` dans une transaction, puis relance les services. Une erreur SQL annule le remplacement ; la sauvegarde reste conservée. Cette opération entraîne une interruption temporaire du site et exige une base dédiée au projet.

Les images locales ne sont pas transférées : les fichiers déjà présents sur OVH sont conservés. Les images référencées par la base importée mais absentes du serveur seront remises en attente de téléchargement si le worker d’images est activé. Les fichiers `.env` ne sont jamais transférés.

Le mode `--check` ne teste pas les connexions PostgreSQL/SSH ni les privilèges distants. Voir les options, les prérequis et la conduite à tenir en cas d’échec dans [le guide de déploiement](deploy/README.md).


- [Installation vide sur serveur dédié](deploy/README.md) : Java, Node, PostgreSQL, Python, services systemd et proxy Apache HTTPS.
- [API REST et pont MCP](docs/api-mcp.md) : lire un jour, créer une sortie, références sources et doublons.
- Le frontend conserve le lockfile de Wik et son mode `legacy-peer-deps` dans `.npmrc` : certaines bibliothèques héritées déclarent encore des peer dependencies Angular 19. La construction native et le catalogue sont vérifiés ; ne pas retirer ce réglage sans migrer ces dépendances.

Le choix de langue dans le bandeau affiche le site en français, anglais, allemand, italien, néerlandais ou espagnol, avec rendu serveur et contenus traduits disponibles. Voir [le fonctionnement multilingue](docs/multilingual.md).

Les agents doivent suivre [AGENTS.md](AGENTS.md). Les images restent dans `data/images/`, exclu de Git ; `./download-images.sh --watch` affiche leur progression.
