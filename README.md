# Idées de sorties en Alsace

Premier socle issu de Wik : Angular 22, thème SCSS Modernize/Wik (couleurs, typo, navigation et cartes), Spring Boot 3.4.2 / Java 21, PostgreSQL et Liquibase. Nouveau logo vectoriel `idee-icon.svg`. Aucun compte, écran de connexion ni Keycloak. L’API d’écriture exige une clé d’import privée.

- `idee-front` : interface publique, recherche, filtres, fiches et prochains créneaux.
- Les fiches localisées utilisent `/{departement}/{commune}/{slug-sortie}`. L’API fournit `url` pour les liens, le partage et la balise canonique ; les anciennes pages `/sorties/{slug}` redirigent en HTTP 301 sur le déploiement Nginx. Les URL restent stables après import. Voir [URL publiques et multilingue](docs/public-urls.md).
- `idee-service` : catalogue public et migrations de la base `idee`.
- `scripts/project_calendar.py` : calcul borné des occurrences iCalendar.
- [Modèle de données](docs/data-model.md) : relations, règles et exemples.
- [Import DATAtourisme](docs/datatourisme.md) : catalogue des événements 67/68, reprise de pagination, mises à jour quotidiennes et suivi PostgreSQL.
- Descriptions Mistral automatiques : les sorties publiées nouvelles ou dont la description française a changé sont mises en file après l’import. Le worker régénère `description_longue` et `description_courte` via des batches Mistral de 500 maximum, avec reprises sur erreur, lorsque `MISTRAL_API_KEY` est configurée. Suivi protégé : `/api/admin/descriptions/status`. Voir [la documentation DATAtourisme](docs/datatourisme.md).
- Traductions à la demande via OpenAI Batch : titre et descriptions longue/courte en anglais, allemand, italien, néerlandais et espagnol, depuis le français généré. Aucun lancement automatique sans demande explicite. [Commandes et suivi](docs/api-mcp.md#traductions-du-titre-et-des-descriptions-avec-openai-batch).
- Synchronisation à la demande : `POST /api/admin/datatourisme/sync` avec la clé d'import ; met à jour les fiches modifiées depuis le précédent parcours et ajoute les nouvelles sans doublon. Suivi : `GET /api/admin/datatourisme/status`.
- Sur OVH, une tâche cron lance l'import dans un conteneur dédié chaque jour à 04:00 UTC (06:00 Paris en été, 05:00 en hiver). Configuration et suivi dans [la documentation DATAtourisme](docs/datatourisme.md).

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

L’API doit être démarrée avec les clés fournisseurs configurées dans son `.env` privé : `MISTRAL_API_KEY` et `MISTRAL_AUTO_ENABLED=true` pour les descriptions françaises ; `OPENAI_API_KEY` et `OPENAI_TRANSLATIONS_ENABLED=true` pour les traductions. Après modification de ces variables, relancer l’API pour les prendre en compte.

Depuis la racine du projet, charger la clé d’import dans le terminal et choisir l’API locale :

```bash
set -a
source .env
set +a
export IDEE_API_BASE_URL='http://127.0.0.1:8087'
test -n "$IDEE_IMPORT_TOKEN" && echo 'Clé chargée' || echo 'Clé absente'
```

Pour la production, définir `IDEE_API_BASE_URL='https://idees.cavousdit.com'` et utiliser son `IDEE_IMPORT_TOKEN`, distinct de celui du développement.

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

L’accueil et les fiches utilisent le rendu serveur Angular : Nginx sert les assets, transmet les pages au service Node `ssr` et `/api` au service Java. Le HTML initial contient le contenu et les métadonnées ; Angular reprend ensuite les interactions par hydratation. Voir [rendu serveur et configuration](docs/server-rendering.md). Le déploiement utilise quatre services Compose : PostgreSQL, API, SSR et Nginx.

Ce premier lot fournit le catalogue public et son schéma. La création de vraies sorties est disponible via une API protégée et un pont MCP stdio ; voir [API et MCP](docs/api-mcp.md). La modification et l’administration complète restent un prochain lot. Les horaires d’ouverture saisonniers sont modélisés ; le filtre par date porte pour l’instant sur les événements datés, et les sorties permanentes ont leur filtre distinct.

## Déploiement et intégration

Pour mettre à jour l’installation existante sur OVH : `./deploy.sh`.
Vérification locale sans déploiement : `./deploy.sh --check`.
Le script sauvegarde la base distante, reconstruit le frontend et l’API, conserve les secrets et vérifie le site public. Voir les options et prérequis dans [le guide de déploiement](deploy/README.md).


- [Installation vide sur serveur dédié](deploy/README.md) : Docker Compose, PostgreSQL persistant, frontend, API et reverse proxy HTTPS.
- [API REST et pont MCP](docs/api-mcp.md) : lire un jour, créer une sortie, références sources et doublons.
- Le frontend conserve le lockfile de Wik et son mode `legacy-peer-deps` dans `.npmrc` : certaines bibliothèques héritées déclarent encore des peer dependencies Angular 19. La construction conteneurisée et le catalogue sont vérifiés ; ne pas retirer ce réglage sans migrer ces dépendances.

Le choix de langue dans le bandeau affiche le site en français, anglais, allemand, italien, néerlandais ou espagnol, avec rendu serveur et contenus traduits disponibles. Voir [le fonctionnement multilingue](docs/multilingual.md).
