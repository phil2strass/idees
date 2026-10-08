# Installation sur serveur dédié

Stack autonome : PostgreSQL 16 (`idee`), service Java 21 + moteur Python, frontend Angular servi par Nginx. Pas d’authentification pour les visiteurs. L’API d’import exige une clé privée.

## Mettre à jour le site depuis cette machine

À la racine du projet :

```bash
./deploy.sh --check   # Vérifie l’archive, sans connexion au serveur
./deploy.sh           # Déploie le frontend et l’API sur ovh
```

Le déploiement conserve les données de la base distante. Il ne lit pas la base locale et ne transfère ni ne restaure de données locales. Les migrations Liquibase restent appliquées au démarrage de l’API pour maintenir sa compatibilité avec le code.

Le script cible l’installation existante `/home/debian/idee` sur l’alias SSH `ovh`, avec le projet Compose `idee`, puis vérifie `https://idees.cavousdit.com`. Le script distant est transféré comme fichier et exécuté sans entrée standard, pour que Docker ne puisse pas consommer ses instructions. Un identifiant SHA-256 de l’archive est intégré au frontend ; le succès exige que le site public serve exactement cet identifiant, et pas seulement une réponse HTTP 200. Il peut être lancé depuis un autre répertoire en utilisant son chemin absolu.

Il transfère uniquement les sources, construit les deux images pendant que le site actuel reste actif, puis sauvegarde les sources et PostgreSQL dans `backups/deploy-DATE-IDENTIFIANT/` sur le serveur. La sauvegarde contient `sources.tar.gz`, `database.dump` au format PostgreSQL custom et `image-ids.txt`. Le dump est vérifié avec `pg_restore --list` avant toute bascule. Si la construction ou la sauvegarde échoue, les sources de production et les conteneurs en cours ne sont pas remplacés.

La synchronisation supprime les fichiers obsolètes dans les seuls dossiers de sources gérés. Les fichiers `.env` et `.env.*` privés, le volume PostgreSQL, les sauvegardes, `.tunnel` et les environnements `.venv` sont conservés. Les fichiers `.env.example` sont actualisés. Apache, les certificats et les autres sites ne sont pas modifiés. Les migrations Liquibase s’appliquent au démarrage de l’API ; une courte interruption est possible pendant le remplacement de l’API et du frontend.

Prérequis locaux : Bash, Python 3, tar, SSH/SCP et curl. L’authentification SSH doit fonctionner par clé ou agent (`BatchMode=yes`). Sur le serveur : Bash, Python 3, tar, rsync, flock, curl, Docker et Docker Compose avec `--wait`/`--wait-timeout`. Docker doit être accessible directement ou via `sudo -n docker`. Le script exige une base existante démarrée et le fichier privé `deploy/.env` ; il n’effectue pas une installation neuve.

Pour mettre aussi à jour les dépendances MCP et relancer le tunnel existant :

```bash
./deploy.sh --with-mcp
```

Cette option exige `idee-mcp/.venv` et un service `idee-tunnel.service` déjà actif, ainsi que l’autorisation sudo sans mot de passe pour `/usr/bin/systemctl restart idee-tunnel.service`. Sans cette option, les sources MCP sont transférées mais ses dépendances et son processus ne sont pas relancés. Le contrôle MCP vérifie le service systemd ; il ne réalise pas un échange de protocole avec OpenAI.

La cible peut être adaptée pour une installation équivalente (toujours projet Compose `idee`) :

```bash
IDEE_DEPLOY_HOST=mon-alias \
IDEE_DEPLOY_DIR=/home/debian/idee \
IDEE_DEPLOY_URL=https://mon-domaine.example \
./deploy.sh
```

En cas d’échec après la bascule, le script indique l’étape et le dossier de sauvegarde et retourne un code non nul. Il ne restaure pas automatiquement la base : une telle restauration pourrait supprimer des sorties ajoutées depuis la sauvegarde. Consulter les journaux et décider d’une réparation ou d’une restauration manuelle. Une erreur du contrôle public peut aussi venir du reverse proxy alors que les conteneurs sont opérationnels.

Validation locale du mécanisme, avec Docker et HTTP simulés, sans connexion au serveur :

```bash
python3 -m unittest discover -s tests -p test_deploy.py -v
```

## Installation neuve vide

La configuration force `DB_CONTEXTS=production` : Liquibase crée les tables et six catégories, **aucune sortie, aucun lieu, aucun calendrier de démonstration**. Elle ne copie jamais la base locale. Les volumes existants sont conservés ; changer de contexte ne supprime pas leurs données.

Prérequis serveur : Docker Engine et Docker Compose, reverse proxy HTTPS déjà administré sur le serveur. Le nom de projet Compose est `idee`. Utiliser une autre valeur `-p` si ce nom est déjà occupé.

Depuis le répertoire du projet copié sur le serveur, sans `node_modules`, `.env` local ou données de DB :

```bash
python3 deploy/init_env.py
# Génère deploy/.env en mode 0600, refuse d'écraser un fichier existant.
docker compose --env-file deploy/.env up -d --build
curl --fail http://127.0.0.1:9081/api/health
curl --fail http://127.0.0.1:9081/api/outings
# Attendu à la première installation : []
```

Le port 9081 est lié à **127.0.0.1 seulement**. PostgreSQL et le service Java ne publient aucun port sur l’hôte. Si 9081 est occupé, modifier `IDEE_HTTP_PORT` dans `deploy/.env` avant le démarrage.

## Domaine et HTTPS

Raccorder le domaine au reverse proxy existant, avec un certificat TLS valide, vers `http://127.0.0.1:9081`. Préserver l’en-tête `Authorization`, prévoir 45 secondes de timeout et une limite de requête de 256 Ko. Ne pas remplacer la configuration globale du proxy ni les autres sites. La configuration précise dépendra du domaine et du proxy effectivement installé.

Un reverse proxy exécuté dans un autre conteneur ne doit pas utiliser son propre localhost ; dans ce cas raccorder les deux conteneurs par un réseau Docker partagé en adaptant les réseaux.

Vérifier ensuite :

```bash
curl --fail https://VOTRE_DOMAINE/api/health
curl --fail 'https://VOTRE_DOMAINE/api/outings?date=2027-01-02'
```

## Données et mises à jour

Le volume `idee_idee-db` conserve PostgreSQL. Ne jamais utiliser `docker compose down -v` sur cette installation en production. Sauvegarder avec :

```bash
mkdir -p backups
chmod 700 backups
docker compose --env-file deploy/.env exec -T db pg_dump -U idee -d idee -Fc > backups/idee.dump
chmod 600 backups/idee.dump
```

Avant une mise à jour : sauvegarde, copie des sources sans remplacer `deploy/.env`, puis `docker compose --env-file deploy/.env up -d --build`. Les migrations Liquibase sont appliquées par le service.

Consulter les journaux : `docker compose --env-file deploy/.env logs --tail=100 api ssr web`. Ne pas publier les sorties de `docker compose config` (elles peuvent contenir les secrets).

Les ajouts sont effectués via [l’API et les outils MCP](../docs/api-mcp.md). Le calcul par date est immédiat et à la demande. Les fiches comportent aussi un cache de prochaines séances ; voir les consignes de maintenance du calendrier dans le README principal.

## Tests de recette isolés

Le projet Compose `idee-integration`, sur le port 9082, sert exclusivement aux tests. `tests/test_api.py` exige `IDEE_ALLOW_TEST_WRITES=isolated` et une base initialement vide. Il écrit volontairement des fiches de test. Ne jamais le lancer sur une base publique. `tests/test_mcp.py` teste le véritable protocole MCP stdio et trois outils sur la même base isolée.

## Archive transférable

`./deploy/package.sh` prépare `/tmp/idee-deploy.tar.gz` avec les sources et les fichiers de déploiement, en excluant tous les fichiers `.env`, les dépendances installées, les données, les caches et les sorties compilées locales. Les secrets sont générés directement sur le dédié avec `deploy/init_env.py`. Cette archive ne contient aucune copie de la base locale.

## Déploiement effectif — 23 septembre 2026

- URL : https://idees.cavousdit.com ; DNS IPv4 déjà pointé vers OVH.
- Serveur : alias SSH `ovh`, sources `/home/debian/idee`, projet Compose `idee`.
- Après déploiement de la version SSR, quatre conteneurs : `idee-db-1`, `idee-api-1`, `idee-ssr-1`, `idee-web-1`. Seul Nginx est publié sur `127.0.0.1:9081`. Le service SSR reste interne.
- Apache système : `/etc/apache2/sites-available/idees.cavousdit.com.conf`, vhosts 80/443 ; HTTP redirige vers HTTPS, sauf le chemin ACME.
- Certificat Let's Encrypt : `/etc/letsencrypt/live/idees.cavousdit.com/`, échéance initiale 22 décembre 2026. Renouvellement via le timer Certbot existant et le webroot `/var/www/idee-acme` ; hook dédié `/etc/letsencrypt/renewal-hooks/deploy/idee-reload-apache` pour recharger Apache.
- Clés privées de cette instance : `/home/debian/idee/deploy/.env` (0600), générées sur le serveur, distinctes des clés locales.
- Recette publique : site/health/lecture HTTP 200, redirection 301, POST sans clé 401 et avec clé + JSON incomplet 400. Zéro sortie, six catégories ; aucune donnée de test ajoutée.
- Les configurations Apache finales sont conservées dans `deploy/apache-idees.conf` et le hook dans `deploy/idee-reload-apache.sh`. La configuration HTTP seule est celle d’amorçage pour obtenir le premier certificat.

- Test de renouvellement Let’s Encrypt (`certbot renew --cert-name idees.cavousdit.com --dry-run`) réussi après mise en place du HTTPS.

## Rendu serveur

Le frontend utilise désormais Angular SSR via le service Node `ssr`. Le déploiement construit et recrée `api`, `ssr` et `web` ensemble. Renseigner `IDEE_PUBLIC_ORIGIN` dans `deploy/.env` si le domaine public diffère de `https://idees.cavousdit.com` ; cette origine détermine les URL canoniques. La configuration Nginx transmet toutes les pages à SSR et conserve les fichiers statiques et l’API sur leurs chemins habituels. Voir [configuration et vérifications SSR](../docs/server-rendering.md).

## Réécriture automatique des descriptions

Les migrations 013/014 mettent les descriptions françaises nouvelles ou modifiées dans une file persistante, traitée par l’API Batch Mistral (lots de 500 maximum). Avec `MISTRAL_API_KEY` renseignée dans `deploy/.env` et `MISTRAL_AUTO_ENABLED=true` (défaut), l’API et le cron quotidien peuvent traiter cette file. Le cron lit maintenant la configuration commune et le déploiement actualise son image `idee-datatourisme:cron` à partir de `idee-api`. Les appels manuels et automatiques réutilisent les textes déjà à jour ; les erreurs sont reprises progressivement. La génération unitaire reste immédiate. Garder l’API active avec la clé pour récupérer les résultats Batch sans attendre le cron quotidien ; celui-ci termine après soumission, sans attendre le traitement distant. Le suivi expose les batches et les éventuelles soumissions incertaines à vérifier avant réenvoi. Voir [le fonctionnement et le suivi](../docs/datatourisme.md).

## Traductions OpenAI à la demande

Migration 015 : stockage et file séparés pour traduire le titre et les descriptions longue/courte en en/de/it/nl/es. Configurer `OPENAI_API_KEY` dans le `deploy/.env` privé de l’instance cible ; aucune clé locale n’est transférée par le déploiement. Modèle configurable par `OPENAI_TRANSLATION_MODEL` (défaut `gpt-4.1-mini-2025-04-14`). `OPENAI_TRANSLATIONS_ENABLED=false` coupe soumission et collecte.

La configuration et la migration ne lancent aucun rattrapage : seul `POST /api/admin/translations/generate-missing?limit=500` avec le Bearer d’import ajoute des demandes. Garder ensuite l’API active pour les soumettre et collecter via OpenAI Batch. Suivi : `GET /api/admin/translations/status`. Voir [les commandes et règles de reprise](../docs/api-mcp.md#traductions-du-titre-et-des-descriptions-avec-openai-batch).
