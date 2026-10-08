# Directives pour les agents

## Architecture et décisions du propriétaire

- Le projet fonctionne **nativement, sans Docker ni Compose**. Ne pas réintroduire de conteneurs, de volumes ou de fichiers de construction associés.
- API : Java 21 / Spring Boot, port local 8087. Frontend : Angular SSR sous Node compatible avec `idee-front/package.json`, port local 4000. Base : PostgreSQL 16. Calendrier : Python 3 + python-dateutil.
- En production, `systemd` gère `idee-api`, `idee-ssr`, `idee-import` et son timer quotidien. Apache dirige `/api/` vers Java et le reste vers Node. Voir `deploy/README.md`.
- Domaine principal : `https://ideesdesorties.eu` ; `www.ideesdesorties.eu` redirige vers lui en conservant les chemins. Le sous-domaine `https://alsace.ideesdesorties.eu` sert également le même site, avec un certificat dédié ; les canoniques restent sur le domaine principal. Préserver les certificats du projet et les exceptions ACME. Le DNS A des deux nouveaux noms pointe vers `51.210.155.29`.
- OVH : runtimes privés dans `.tools/` (Node 24 et clients/serveur PostgreSQL 16), exclus de Git et des archives ; ne pas changer les versions système des autres sites. Le cluster du projet est dans `data/postgresql16/`, géré par `idee-db.service`, sur `127.0.0.1:5433`. Préserver `.tools/` lors des mises à jour.
- Les images téléchargées restent dans **`data/images/` à la racine du projet**, exclu de Git et des archives de sources. Les réimports réutilisent les fichiers ; crédits, licences et provenance restent conservés. Les noms descriptifs sont enregistrés une fois et restent stables ; une même image conserve son URL dans les six langues. Préserver les redirections des anciennes URL par empreinte.
- `CODEX.md` est un aide-mémoire ; ces directives et les instructions actuelles de l’utilisateur priment.

## Autonomie et périmètre

- Réaliser les changements demandés, leurs vérifications et leur documentation sans demander confirmation pour les modifications réversibles du dépôt.
- L’utilisateur gère les serveurs. Une modification de code seule n’autorise pas à démarrer, arrêter ou redémarrer ses services, déployer en production, migrer sa base réelle ou lancer des traitements fournisseurs payants. Faire ces actions lorsqu’elles sont explicitement demandées.
- Ne pas modifier les autres projets, sites, bases, certificats ni configurations globales du serveur. Préserver les modifications locales préexistantes.
- Ne pas utiliser de sous-agents sauf demande explicite de l’utilisateur.

## Données, secrets et déploiement

- Secrets : `.env` en développement, `deploy/.env` en production, éventuellement `deploy/.env.datatourisme` pour la clé d’import. Ne jamais les afficher, les versionner ou les inclure dans une archive. Ne pas copier les secrets locaux vers la production, sauf demande explicite du propriétaire pour des clés ciblées : transfert par SSH, fichier distant 0600, aucun autre secret remplacé.
- Par défaut, conserver la base distante et déployer uniquement les sources et artefacts construits. Le remplacement par la base locale est autorisé uniquement sur demande explicite via `./deploy.sh --replace-db` : sauvegarde distante vérifiée, arrêt des écrivains, remplacement transactionnel du schéma applicatif public. Créer ce script n’autorise pas à l’exécuter sur le serveur. Préserver `data/`, `.runtime/`, `.venv/`, `.tunnel/`, `logs/`, les fichiers d’environnement et les sauvegardes.
- Les migrations passent par Liquibase ; ajouter une migration, ne pas réécrire une migration déjà publiée. Sauvegarder la base avant une bascule de production.
- Ne pas écraser un JAR utilisé par un processus actif. Les services exécutent une version immuable sous `.runtime/releases/`, choisie par `.runtime/current`.
- Conserver les anciennes versions et les sauvegardes en cas d’échec. Ne pas restaurer automatiquement une base après une migration.
- Un changement de configuration ne constitue pas un déploiement effectif : indiquer clairement ce qui a été testé et ce qui attend encore activation.

## Import et contenus

- Préserver les UUID, slugs publics, alias et reprises transactionnelles des imports.
- En production native, le timer quotidien `idee-import.timer` (04:00 UTC) pilote import → Mistral → traductions dans le processus `import`, avec une fenêtre maximale de trois heures et reprise persistante le lendemain. Le lanceur `api` force les workers Mistral/OpenAI désactivés ; ne pas les remettre en continu. Les images restent traitées par l’API.
- Description française modifiée : Mistral, puis traductions. Titre seul modifié : traduction du titre uniquement. Ne pas régénérer les contenus déjà à jour.
- Conserver l’attribution des données originales, leur date de mise à jour et les crédits photographiques, même lorsque les textes sont adaptés.
- Traduire les nouveaux libellés d’interface dans les six langues. Préserver SSR, hydratation, métadonnées, liens canoniques et affichage mobile.

## Vérifications

- Utiliser `rg` pour les recherches. Ne pas transformer les classes CSS « container » : elles décrivent la mise en page.
- Frontend : `npm --prefix idee-front run build`, puis `node tests/test_ssr.mjs` si le rendu est concerné.
- Backend : `mvn -f idee-service/pom.xml test`.
- Base : `python3 scripts/check_description_jobs.py` utilise exclusivement des schémas jetables, clés fournisseurs désactivées. Ne jamais lancer une recette d’écriture contre les données applicatives.
- Déploiement : `python3 tests/test_deploy.py` et `./deploy.sh --check`, sans connexion distante. Tester les scripts modifiés et terminer par `git diff --check`.
