# Déploiement natif sur serveur Linux

L’application utilise Java 21, Maven, Node compatible avec les moteurs déclarés dans `idee-front/package.json` (Node 24.15+ conseillé dans la branche 24), PostgreSQL 16 et Python 3 avec python-dateutil. Les processus sont gérés par systemd. Apache assure HTTPS et le proxy vers les ports locaux ; aucune couche de conteneurisation n’est utilisée.

## Organisation

- `deploy/.env` : configuration privée de production, mode 0600, jamais remplacée par un déploiement.
- `.tools/` : runtimes privés du projet sur OVH (Node 24, PostgreSQL 16 et ses clients), sans modifier les versions système des autres sites. Les lanceurs utilisent `.tools/bin` en priorité.
- `data/postgresql16/` : cluster privé OVH sur `127.0.0.1:5433`, socket dans `data/pgsocket/`, unité `idee-db.service`. Ne pas confondre avec le cluster système historique.
- `.venv/` : Python et les dépendances du calendrier.
- `.runtime/releases/<version>/` : JAR, frontend SSR compilé et scripts de calendrier immuables.
- `.runtime/current` : lien symbolique vers la version à exécuter. Un service résout ce lien au lancement et conserve sa version jusqu’à son redémarrage.
- `data/images/` : images téléchargées, accessibles directement dans le projet, exclues de Git et des archives de sources.
- `backups/` : sauvegardes de base, sources et images, conservées en cas d’échec.

Si `data/postgresql16/PG_VERSION` existe, `install-services.py` génère aussi `idee-db.service` et les dépendances API/import vers cette unité. La création du cluster et de ses rôles reste une opération initiale explicite ; une mise à jour ne les réinitialise jamais.

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

Configurer `deploy/.env` avec les identifiants d’une base PostgreSQL dédiée **déjà créée**. Le script génère un mot de passe indicatif mais ne crée ni rôle ni base ; faire correspondre cette valeur au rôle PostgreSQL. Utiliser un rôle applicatif propriétaire de sa base, sans privilège superutilisateur. `DB_CONTEXTS=production` évite les données de démonstration. La première migration conserve la sauvegarde de production ; remplacer par la base locale nécessite ensuite l’option explicite `--replace-db`.

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

Cette modification du dépôt ne migre pas le serveur automatiquement. `./deploy.sh --doctor` vérifie l’installation OVH en lecture seule ; le déploiement exécute aussi ce contrôle avant tout export local ou transfert.

Le diagnostic du 8 octobre 2026 a constaté sur OVH l’ancienne installation (`idee-web-1`, `idee-api-1`, `idee-db-1`), aucun `.venv` ni `.runtime/current`, et aucun service natif API/SSR. Node 22.17.1 est trop ancien pour le frontend, et les clients PostgreSQL 15 de l’hôte ne permettent pas d’exporter la base PostgreSQL 16 du conteneur. Le cluster PostgreSQL 15 existant est arrêté : ne pas le démarrer ou l’écraser pour contourner ce contrôle. Les autres sites et services de messagerie ne font pas partie de cette migration. Cette ancienne installation a ensuite été migrée le même jour à la demande du propriétaire ; le site utilise désormais les services natifs et la copie de la base locale.

Pour une future première migration sur un autre hôte, préparer Node compatible et un PostgreSQL 16 natif dédié au projet, avec ses outils clients dans le PATH. Conserver l’ancien stockage et les sauvegardes. La configuration historique `deploy/.env` peut ne contenir que le mot de passe : compléter `DB_HOST`, `DB_PORT`, `DB_NAME` et `DB_USER` pour la nouvelle instance, en conservant les secrets. Le proxy Apache doit ensuite viser les nouveaux services natifs ; conserver son VirtualHost et ses certificats.

Avant activation du fonctionnement natif :

1. Sauvegarder et vérifier la base existante au format `pg_dump -Fc`, ainsi que les images et les fichiers de configuration privés. Ne pas utiliser une base locale de développement pour cette opération.
2. Installer PostgreSQL nativement, créer le rôle et la base dédiés, puis restaurer **la sauvegarde de production** lors d’une fenêtre de maintenance. Vérifier les propriétaires et le nombre de sorties.
3. Copier les images existantes dans `data/images/`, les rendre lisibles et rendre le dossier inscriptible par l’utilisateur applicatif. Conserver l’ancien stockage jusqu’à validation.
4. Configurer les nouveaux accès PostgreSQL dans `deploy/.env`, construire une version et installer les unités comme ci-dessus.
5. Lors de la bascule, arrêter les anciens processus qui occupent les ports ou traitent les imports, et désactiver l’ancienne tâche planifiée d’import pour éviter deux planifications. Ne supprimer aucune donnée ni sauvegarde.
6. Démarrer les services natifs, adapter le proxy Apache, vérifier le catalogue, une fiche, une image et les compteurs de traitements. N’activer le timer quotidien qu’après ces contrôles.

Les mises à jour ordinaires refusent de migrer une installation : elles exigent une base accessible, une version native existante et des services natifs actifs. Aucun changement de la base réelle ni du serveur n’est effectué lors de `./deploy.sh --check`.

## Apache et HTTPS

La configuration `apache-idees.conf` sert `https://ideesdesorties.eu` : `/api/` est dirigé vers `127.0.0.1:8087` et le reste vers `127.0.0.1:4000`. `www.ideesdesorties.eu` et HTTP redirigent vers cette adresse. `apache-idees-http.conf` est la configuration temporaire de validation ACME, avant installation du certificat. L’ordre des règles est important : l’API précède `/`. Node sert les fichiers compilés et rend les pages à la demande ; servir seulement le dossier browser ne suffit pas.

Activer les modules proxy, proxy_http, headers, rewrite et ssl selon l’installation existante. Adapter uniquement le VirtualHost du site, conserver ses certificats et tester avec `apache2ctl configtest` avant rechargement. Les scripts de déploiement ne remplacent pas Apache et ne touchent pas TLS. Renseigner `IDEE_PUBLIC_ORIGIN` avec la vraie origine HTTPS.

Après configuration :

```bash
curl --fail https://VOTRE_DOMAINE/api/health
curl --fail https://VOTRE_DOMAINE/
```

## Mise à jour depuis la machine de développement

```bash
./deploy.sh --doctor      # Prérequis OVH en lecture seule, aucun export
./deploy.sh --check       # Archive vérifiée, aucune connexion distante
./deploy.sh               # Mise à jour native de l’installation existante
./deploy.sh --with-mcp    # Met aussi à jour les dépendances MCP et son tunnel
./deploy.sh --replace-db  # Remplace aussi les données OVH par la base locale
./deploy.sh --check --replace-db # Vérifie l’archive et les options, sans exporter de base
```

Cible par défaut : `ovh:/home/debian/idee`, site `https://ideesdesorties.eu`. Variables facultatives : `IDEE_DEPLOY_HOST`, `IDEE_DEPLOY_DIR`, `IDEE_DEPLOY_URL`. SSH doit fonctionner avec une clé ou un agent (`BatchMode=yes`).

Sans `--replace-db`, le script transfère uniquement les sources. Sur le serveur, il vérifie les services et PostgreSQL, construit une nouvelle version pendant que l’ancienne fonctionne, sauvegarde et vérifie la base, puis conserve les sources et images dans `backups/deploy-*`. Il synchronise les sources sans toucher aux secrets, données, environnements Python ou anciennes versions, actualise les unités systemd, bascule le lien courant et redémarre uniquement `idee-api` et `idee-ssr`. Il vérifie ensuite API, rendu SSR, identifiant de version local et version servie publiquement. Une simple réponse HTTP 200 ne suffit pas à déclarer un déploiement réussi.

L’utilisateur distant doit pouvoir exécuter sans mot de passe les commandes `sudo` nécessaires à l’installation **des unités propres au projet**, au `daemon-reload` et au redémarrage de ces deux services (et du tunnel avec `--with-mcp`). Configurer ces droits avec l’administrateur. Ne pas accorder un accès sudo général à partir d’un exemple automatique.

En cas d’échec, les sauvegardes et les versions sont conservées. Aucun retour arrière de base automatique : Liquibase peut avoir appliqué une migration. Après vérification de sa compatibilité avec le schéma courant, l’opérateur peut repointer `.runtime/current` vers le chemin enregistré dans `previous-release.txt`, puis redémarrer les services. Ne pas effacer les anciennes versions tant qu’un processus ou une reprise peut en dépendre.

## Remplacer la base OVH par la base locale

`./deploy.sh --replace-db` exporte les données applicatives du schéma `public` de la base indiquée dans **`.env` à la racine locale**, puis transfère cette archive privée par SSH. La destination reste exclusivement la base configurée dans **`deploy/.env` sur OVH**. Aucun fichier de secrets local, rôle PostgreSQL ou mot de passe n’est copié. Sans cette option, aucun export ni remplacement de données locales n’est effectué.

Le serveur construit d’abord la nouvelle version, vérifie les privilèges, arrête `idee-import.timer`, `idee-import.service`, `idee-api` et `idee-ssr`, puis sauvegarde les données distantes dans `backups/deploy-*/database.dump`, ainsi que les sources et images. Il recrée `public` et restaure l’export dans **une seule transaction** : un échec SQL annule aussi la suppression du schéma. Après réussite, il bascule la version, démarre API et SSR, vérifie le site et réactive le timer uniquement s’il était actif avant l’opération. Liquibase applique les éventuelles migrations restantes.

Cette option **efface les données distantes absentes de la base locale**. Elle exige une base dédiée au projet, avec `idee_outing` dans `public`, aucun autre schéma utilisateur, et un utilisateur propriétaire du schéma disposant du droit `CREATE` sur la base. Les outils `pg_dump`, `pg_restore` et `psql` doivent être compatibles avec les versions PostgreSQL locale et distante. L’utilisateur SSH doit également pouvoir arrêter les quatre unités et démarrer les deux services et, si nécessaire, le timer. `--check --replace-db` reste hors ligne : il ne teste pas ces connexions ou privilèges.

Les images locales ne sont pas transférées : les images déjà présentes sur OVH restent conservées ; les fichiers référencés par la base importée mais absents du serveur seront remis en attente par le worker d’images s’il est activé. Prévoir ce délai avant qu’ils soient de nouveau disponibles. La restauration copie aussi les historiques et files de traitements locaux ; les workers reprendront leur traitement au redémarrage.

En cas d’échec, consulter la phase affichée et la sauvegarde conservée. Le script ne restaure pas automatiquement la base précédente et ne relance pas les services en secours. Après un échec de restauration SQL, la transaction préserve les anciennes données ; après une réussite suivie d’un échec de migration ou de démarrage, la nouvelle base peut déjà être en place. Vérifier ces éléments avant toute intervention. Aucun remplacement de données ne résulte de la simple création ou vérification de ces scripts.

## Import quotidien et traitements

`idee-import.service` lance le même JAR en mode non HTTP. `idee-import.timer` le déclenche chaque jour à 04:00 UTC (06:00 Paris en été, 05:00 en hiver). La clé peut être définie dans `deploy/.env` ou dans `deploy/.env.datatourisme` privé. Le processus quotidien importe les sorties, traite les descriptions françaises avec Mistral puis leurs traductions avec OpenAI. Les modifications de titre seules conservent les descriptions traduites. Le lanceur de l’API désactive ses workers Mistral/OpenAI : aucun traitement fournisseur automatique en continu dans l’API ; le téléchargement des images y reste actif.

```bash
sudo systemctl enable --now idee-import.timer
systemctl list-timers idee-import.timer
sudo systemctl start idee-import.service  # Lancement manuel explicite
journalctl -u idee-import.service -n 100
```

Par défaut `DATATOURISME_ENABLED=false` dans l’API lorsque le timer possède la planification. Pour piloter l’import par l’API et `import-outings.sh`, activer cette variable dans `deploy/.env`, redémarrer `idee-api` et désactiver le timer si l’API devient le planificateur. Le helper historique `datatourisme-cron.sh` reste un lanceur natif manuel ; aucun cron n’est installé par défaut. `install-datatourisme-cron.py` lit une clé depuis une entrée JSON privée, installe les unités et active le timer natif.

Les clés Mistral/OpenAI sont chargées uniquement par Java, jamais par Node ni dans les sources frontend. Le processus quotidien active les deux workers fournisseurs, même si leurs options sont désactivées dans `deploy/.env`, et exige leurs clés. Son téléchargeur d’images est désactivé pour laisser l’API gérer les fichiers. Une seule boucle pilote import → Mistral → traductions, sans ticks concurrents de ces trois workers. Elle attend au maximum trois heures, puis conserve les batches asynchrones et les reprises différées dans PostgreSQL pour le lendemain. Elle termine plus tôt si toutes les files sont vides ; un import inachevé à la limite ou une clé absente produit un échec visible dans le journal. Les contenus déjà à jour sont réutilisés.

Suivi de l’exécution quotidienne :

```bash
sudo systemctl list-timers idee-import.timer
sudo systemctl status idee-import.service
sudo journalctl -u idee-import.service --since today -f
```

Le journal indique chaque minute les unités restantes (`imports`, `descriptions`, `translations`), incluant files et batches non terminés ; ce ne sont pas des nombres de sorties distinctes. Les endpoints de statut restent consultables dans l’API : `enabled=false` y désigne le worker de l’API, et non celui du processus quotidien.

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
# Facultatif : test réel sur un cluster PostgreSQL jetable sous /tmp, sans .env
IDEE_TEST_POSTGRES_BIN=/usr/lib/postgresql/16/bin python3 tests/test_deploy_database.py
./deploy.sh --check
mvn -f idee-service/pom.xml test
npm --prefix idee-front run build
node tests/test_ssr.mjs
```

Les tests de déploiement utilisent des commandes simulées ; ils ne se connectent pas au serveur. Pour les recettes PostgreSQL d’import, utiliser uniquement `python3 scripts/check_description_jobs.py` et ses schémas jetables, fournisseurs désactivés. Les anciens fichiers d’orchestration et de construction de conteneurs ne font plus partie du projet.

## État OVH après la migration du 8 octobre 2026

Le site public `https://ideesdesorties.eu` utilise les services natifs `idee-db`, `idee-api` et `idee-ssr`. Node 24.21.0 et PostgreSQL 16.15 sont installés dans `.tools/`, à partir des distributions officielles vérifiées (signature du dépôt PGDG et empreintes SHA256), sans remplacement des outils système. Le cluster dédié écoute sur `127.0.0.1:5433`. `dynamic_shared_memory_type=mmap` évite la suppression des segments POSIX lors de la déconnexion SSH de l’utilisateur applicatif ; ce réglage reste propre à ce cluster. Voir [la documentation PostgreSQL sur RemoveIPC](https://www.postgresql.org/docs/16/kernel-resources.html).

La base locale a été copiée : 3 694 sorties, 2 719 références d’images et 18 103 traductions lors de la vérification initiale. Les 2 570 fichiers image locaux ont également été transférés dans `data/images/`. Le VirtualHost existant et les certificats ont été conservés, avec uniquement les cibles du proxy adaptées. Les autres applications et le cluster PostgreSQL système historique n’ont pas été modifiés.

L’ancien cron du projet est remplacé par `idee-import.timer` (04:00 UTC), sans import immédiat à la première activation. Les anciens conteneurs `idee-api-1`, `idee-web-1` et `idee-db-1` sont arrêtés et leur redémarrage automatique désactivé ; leur ancien volume et les sauvegardes restent conservés pour reprise manuelle.

Les sauvegardes initiales et finales de l’ancien projet, de sa base, du VirtualHost et du cron se trouvent dans `/home/debian/idee/backups/migrate-native-20261008T103914Z-p7YUm9/`. La configuration privée distante a été conservée lors de la migration. À la demande explicite du propriétaire, les seules clés `MISTRAL_API_KEY` et `OPENAI_API_KEY` ont ensuite été copiées depuis sa configuration locale vers `deploy/.env` sur OVH (mode 0600), sans affichage de leurs valeurs ni remplacement des autres secrets. La clé DATAtourisme historique reste dans `deploy/.env.datatourisme`. À la demande du propriétaire, Mistral et OpenAI sont activés exclusivement dans le traitement quotidien de 04:00 UTC ; leurs workers restent désactivés dans l’API permanente. Les textes et traductions existants restent réutilisés.

## Domaine principal : ideesdesorties.eu

Depuis le 8 octobre 2026, l’adresse principale est `https://ideesdesorties.eu`. Le propriétaire a modifié le DNS OVH de la racine du domaine ; les enregistrements A de `ideesdesorties.eu` et `www.ideesdesorties.eu` pointent vers `51.210.155.29`.

Le certificat Let’s Encrypt `ideesdesorties.eu` couvre les deux noms. La validation utilise `/var/www/idee-acme`, et le hook `idee-reload-apache.sh` recharge Apache après renouvellement des certificats du projet. Les autres certificats et sites ne sont pas modifiés.

Les variantes HTTP et `www` redirigent en 301 vers l’adresse principale, en conservant le chemin et les paramètres. L’ancienne adresse a été retirée d’Apache et son certificat Let’s Encrypt supprimé à la demande du propriétaire. Le service MCP utilise aussi la nouvelle adresse. `IDEE_PUBLIC_ORIGIN=https://ideesdesorties.eu` dans `deploy/.env` donne cette même origine aux liens canoniques et aux métadonnées SSR.

Les configurations précédentes et l’environnement privé sont sauvegardés dans `/home/debian/idee/backups/domain-20261008T113825Z-bdrunW/`. Aucun remplacement de base ni déploiement des artefacts applicatifs n’est nécessaire pour ce changement de domaine.

```bash
curl -I https://ideesdesorties.eu/
curl -I https://www.ideesdesorties.eu/
curl --fail https://ideesdesorties.eu/api/health
```

### Adresse régionale : alsace.ideesdesorties.eu

`https://alsace.ideesdesorties.eu` sert le même frontend et la même API. Son enregistrement A pointe vers `51.210.155.29`. Le VirtualHost dédié est fourni dans `deploy/apache-alsace.conf` : HTTP redirige vers HTTPS sur ce même sous-domaine, et le certificat Let’s Encrypt `alsace.ideesdesorties.eu` est renouvelé automatiquement avec rechargement d’Apache par le hook du projet.

Cette adresse supplémentaire ne change pas `IDEE_PUBLIC_ORIGIN` : les canoniques SSR restent sur `https://ideesdesorties.eu`, pour conserver une adresse de référence unique.
