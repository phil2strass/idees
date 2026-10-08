# Déploiement natif sur serveur Linux

L’application utilise Java 21, Maven, Node compatible avec les moteurs déclarés dans `idee-front/package.json` (Node 24.15+ conseillé dans la branche 24), PostgreSQL 16 et Python 3 avec python-dateutil. Les processus sont gérés par systemd. Apache assure HTTPS et le proxy vers les ports locaux ; aucune couche de conteneurisation n’est utilisée.

## Organisation

- `deploy/.env` : configuration privée de production, mode 0600, jamais remplacée par un déploiement.
- `.venv/` : Python et les dépendances du calendrier.
- `.runtime/releases/<version>/` : JAR, frontend SSR compilé et scripts de calendrier immuables.
- `.runtime/current` : lien symbolique vers la version à exécuter. Un service résout ce lien au lancement et conserve sa version jusqu’à son redémarrage.
- `data/images/` : images téléchargées, accessibles directement dans le projet, exclues de Git et des archives de sources.
- `backups/` : sauvegardes de base, sources et images, conservées en cas d’échec.

Les services s’exécutent sous l’utilisateur propriétaire du projet (par exemple `debian`), jamais root. Java écoute sur `127.0.0.1:8087`, Node sur `127.0.0.1:4000`. PostgreSQL reste local ou sur l’hôte privé configuré. Les journaux des services sont accessibles par `journalctl`.

## Première installation native

Installer les prérequis sur l’hôte et rendre `java`, `mvn`, `node`, `npm`, `python3`, `psql`, `pg_dump`, `pg_restore`, `rsync`, `flock`, `curl` et `tar` accessibles dans le PATH système. Les outils PostgreSQL doivent être compatibles avec la version du serveur. Prévoir également `python3-venv`. Ne pas modifier les autres sites ou bases de l’hôte.

Depuis le projet copié sur le serveur, en tant qu’utilisateur applicatif :

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
python3 deploy/init_env.py
chmod 600 deploy/.env
```

Configurer `deploy/.env` avec les identifiants d’une base PostgreSQL dédiée **déjà créée**. Le script génère un mot de passe indicatif mais ne crée ni rôle ni base ; faire correspondre cette valeur au rôle PostgreSQL. Utiliser un rôle applicatif propriétaire de sa base, sans privilège superutilisateur. `DB_CONTEXTS=production` évite les données de démonstration. Ne jamais copier la base de développement vers la production.

Pour une installation existante, conserver ses secrets et ses données ; ne pas exécuter `init_env.py` sur une configuration déjà présente. Les fichiers privés utilisent des affectations `NOM=valeur` avec guillemets simples ou doubles si nécessaire, sans substitution shell.

```bash
python3 deploy/database.py check
mkdir -p .runtime/releases data/images logs
bash deploy/build-native.sh "$PWD/.runtime/releases/initial"
ln -s "$PWD/.runtime/releases/initial" .runtime/current
python3 deploy/install-services.py
sudo systemctl enable --now idee-api.service idee-ssr.service
curl --fail http://127.0.0.1:8087/api/health
curl --fail http://127.0.0.1:4000/_health
```

`install-services.py` installe uniquement les unités propres au projet et recharge systemd ; il ne démarre aucun service. Pour examiner leur contenu sans modifier le système : `python3 deploy/install-services.py --output-dir /tmp/idee-units`. Les lancements/redémarrages effectifs restent à la main de l’opérateur.

## Migration d’une installation existante

Cette modification du dépôt ne migre pas le serveur automatiquement. Avant activation du fonctionnement natif :

1. Sauvegarder et vérifier la base existante au format `pg_dump -Fc`, ainsi que les images et les fichiers de configuration privés. Ne pas utiliser une base locale de développement pour cette opération.
2. Installer PostgreSQL nativement, créer le rôle et la base dédiés, puis restaurer **la sauvegarde de production** lors d’une fenêtre de maintenance. Vérifier les propriétaires et le nombre de sorties.
3. Copier les images existantes dans `data/images/`, les rendre lisibles et rendre le dossier inscriptible par l’utilisateur applicatif. Conserver l’ancien stockage jusqu’à validation.
4. Configurer les nouveaux accès PostgreSQL dans `deploy/.env`, construire une version et installer les unités comme ci-dessus.
5. Lors de la bascule, arrêter les anciens processus qui occupent les ports ou traitent les imports, et désactiver l’ancienne tâche planifiée d’import pour éviter deux planifications. Ne supprimer aucune donnée ni sauvegarde.
6. Démarrer les services natifs, adapter le proxy Apache, vérifier le catalogue, une fiche, une image et les compteurs de traitements. N’activer le timer quotidien qu’après ces contrôles.

Les mises à jour ordinaires refusent de migrer une installation : elles exigent une base accessible, une version native existante et des services natifs actifs. Aucun changement de la base réelle ni du serveur n’est effectué lors de `./deploy.sh --check`.

## Apache et HTTPS

Les exemples `apache-idees.conf` et `apache-idees-http.conf` dirigent `/api/` vers `127.0.0.1:8087` et le reste vers `127.0.0.1:4000`. L’ordre des règles est important : l’API précède `/`. Node sert les fichiers compilés et rend les pages à la demande ; servir seulement le dossier browser ne suffit pas.

Activer les modules proxy, proxy_http, headers, rewrite et ssl selon l’installation existante. Adapter uniquement le VirtualHost du site, conserver ses certificats et tester avec `apache2ctl configtest` avant rechargement. Les scripts de déploiement ne remplacent pas Apache et ne touchent pas TLS. Renseigner `IDEE_PUBLIC_ORIGIN` avec la vraie origine HTTPS.

Après configuration :

```bash
curl --fail https://VOTRE_DOMAINE/api/health
curl --fail https://VOTRE_DOMAINE/
```

## Mise à jour depuis la machine de développement

```bash
./deploy.sh --check       # Archive vérifiée, aucune connexion distante
./deploy.sh               # Mise à jour native de l’installation existante
./deploy.sh --with-mcp    # Met aussi à jour les dépendances MCP et son tunnel
```

Cible par défaut : `ovh:/home/debian/idee`, site `https://idees.cavousdit.com`. Variables facultatives : `IDEE_DEPLOY_HOST`, `IDEE_DEPLOY_DIR`, `IDEE_DEPLOY_URL`. SSH doit fonctionner avec une clé ou un agent (`BatchMode=yes`).

Le script transfère uniquement les sources. Sur le serveur, il vérifie les services et PostgreSQL, construit une nouvelle version pendant que l’ancienne fonctionne, sauvegarde et vérifie la base, puis conserve les sources et images dans `backups/deploy-*`. Il synchronise les sources sans toucher aux secrets, données, environnements Python ou anciennes versions, actualise les unités systemd, bascule le lien courant et redémarre uniquement `idee-api` et `idee-ssr`. Il vérifie ensuite API, rendu SSR, identifiant de version local et version servie publiquement. Une simple réponse HTTP 200 ne suffit pas à déclarer un déploiement réussi.

L’utilisateur distant doit pouvoir exécuter sans mot de passe les commandes `sudo` nécessaires à l’installation **des unités propres au projet**, au `daemon-reload` et au redémarrage de ces deux services (et du tunnel avec `--with-mcp`). Configurer ces droits avec l’administrateur. Ne pas accorder un accès sudo général à partir d’un exemple automatique.

En cas d’échec, les sauvegardes et les versions sont conservées. Aucun retour arrière de base automatique : Liquibase peut avoir appliqué une migration. Après vérification de sa compatibilité avec le schéma courant, l’opérateur peut repointer `.runtime/current` vers le chemin enregistré dans `previous-release.txt`, puis redémarrer les services. Ne pas effacer les anciennes versions tant qu’un processus ou une reprise peut en dépendre.

## Import quotidien et traitements

`idee-import.service` lance le même JAR en mode non HTTP. `idee-import.timer` le déclenche chaque jour à 04:00 UTC (06:00 Paris en été, 05:00 en hiver). La clé peut être définie dans `deploy/.env` ou dans `deploy/.env.datatourisme` privé. L’API garde Mistral, OpenAI et les images actifs pour terminer les files en arrière-plan.

```bash
sudo systemctl enable --now idee-import.timer
systemctl list-timers idee-import.timer
sudo systemctl start idee-import.service  # Lancement manuel explicite
journalctl -u idee-import.service -n 100
```

Par défaut `DATATOURISME_ENABLED=false` dans l’API lorsque le timer possède la planification. Pour piloter l’import par l’API et `import-outings.sh`, activer cette variable dans `deploy/.env`, redémarrer `idee-api` et désactiver le timer si l’API devient le planificateur. Le helper historique `datatourisme-cron.sh` reste un lanceur natif manuel ; aucun cron n’est installé par défaut. `install-datatourisme-cron.py` lit une clé depuis une entrée JSON privée, installe les unités et active le timer natif.

Les clés Mistral/OpenAI sont chargées uniquement par Java, jamais par Node ni dans les sources frontend. L’import ponctuel désactive son téléchargeur d’images et son worker OpenAI : l’API continue ces traitements. Les reprises, quotas, verrous et identifiants des batches restent dans PostgreSQL.

## Images, sauvegardes et diagnostic

```bash
./download-images.sh --watch
journalctl -u idee-api.service -u idee-ssr.service -n 100
systemctl status idee-api.service idee-ssr.service
mkdir -p backups
python3 deploy/database.py backup "$PWD/backups/idee-$(date -u +%Y%m%dT%H%M%SZ).dump"
tar -czf backups/images.tar.gz data/images
```

Les sauvegardes contiennent des données privées : les garder dans un dossier 0700 et ne pas les publier. Ne pas afficher `deploy/.env` dans les journaux. Les images sont dans le projet ; aucune synchronisation des sources ne doit les effacer.

## Vérifications sans production

```bash
python3 tests/test_deploy.py
./deploy.sh --check
mvn -f idee-service/pom.xml test
npm --prefix idee-front run build
node tests/test_ssr.mjs
```

Les tests de déploiement utilisent des commandes simulées ; ils ne se connectent pas au serveur. Pour les recettes PostgreSQL d’import, utiliser uniquement `python3 scripts/check_description_jobs.py` et ses schémas jetables, fournisseurs désactivés. Les anciens fichiers d’orchestration et de construction de conteneurs ne font plus partie du projet.
