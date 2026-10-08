# Contexte du projet idées

## Slugs traduits des sorties — 8 octobre 2026

- Demande : le slug anglais « don-du-sang » doit être en anglais. Migration 016 : publication persistante des slugs depuis le titre traduit existant, rattrapage des cinq langues déjà enregistrées, déclenchement à l’enregistrement des prochaines traductions uniquement. Exemple attendu : /en/bas-rhin/rossfeld/blood-donation. Aucun nouvel appel OpenAI.
- Réutilise le registre de chemins de la migration 012. Anciens chemins préfixés français réservés avant publication ; collisions suffixées avec id, corrections éditoriales conservant les alias, slugs stables lors des titres/imports modifiés. Département/commune traduits si référentiel disponible, sinon graphie française. Sans géographie : /en/sorties/{slug-traduit}.
- Résolution API du chemin complet dans les six langues, carte `urls` par langue sur listes/détails ; Angular utilise les véritables adresses pour cartes, choix de langue, canoniques, partage et hreflang. Chemins invalides et archives restent 404 ; anciens chemins 301 dans la même langue avec paramètres conservés en SSR.
- Migration applicative réelle non exécutée, aucun serveur démarré/redémarré ni déploiement. L’utilisateur doit relancer l’API mise à jour pour appliquer 016 et Angular pour les nouveaux liens. Documentation docs/public-urls.md et docs/multilingual.md actualisée.
- Validation réussie : build Angular SSR, tests Maven, cinq suites PostgreSQL isolées supprimées (publication, stabilité, collisions, alias et géographie traduite), migration de traductions préexistantes dans une transaction annulée, SSR six langues et 301/404/503, navigateur mobile multilingue et régression française/hydratation. Archive deploy.sh --check vérifiée sans secrets.


## Interface Angular multilingue — 8 octobre 2026

- Bandeau : sélecteur accessible des six langues fr/en/de/it/nl/es, visible sur mobile. Interface et calendrier traduits, dates/heures/devises localisées, titres et descriptions OpenAI affichés dans les vignettes et le détail ; français de secours balisé avec sa langue. Les autres données source restent dans leur langue d’origine.
- Langue dans l’adresse : français sans préfixe, autres langues /en, /de, /it, /nl, /es. Conservation de la page, paramètres/ancre et filtres Angular. Segments géographiques et slug français stables ; pas de publication automatique des slugs traduits de la migration 012. Rendu SSR/hydratation, titre/description/canonique localisés, liens hreflang et x-default, redirections anciennes adresses conservant la langue.
- Catalogue API : paramètre language validé, titres/résumés traduits à jour chargés en une requête groupée ; recherche française et dans la traduction choisie, sans descriptions longues dans les listes. Source modifiée => traduction masquée également à la recherche.
- Aucune génération supplémentaire lancée, aucun appel fournisseur réel, aucune migration applicative, aucun déploiement ni démarrage/redémarrage de serveur. Documentation : docs/multilingual.md.
- Validation réussie : build Angular SSR, tests Maven, quatre suites PostgreSQL dans des schémas jetables supprimés (recherche traduite et source obsolète incluses), rendu des six langues/alias/404/503, navigateur SSR et mobile (filtres conservés, calendrier allemand, retour français, absence d’erreurs/hydratation sans nouvel appel), capture mobile vérifiée, archive deploy.sh --check sans secrets.


## Traductions du titre et des descriptions via OpenAI Batch — 8 octobre 2026

- Demande : traduire description_longue/courte en anglais, allemand, italien, néerlandais et espagnol via OpenAI Batch. Précision explicite : inclure le titre et NE PAS lancer le traitement ; l’utilisateur le lancera lui-même. Aucun appel OpenAI réel, lancement de batch, migration du schéma applicatif réel, démarrage/redémarrage ni déploiement effectué.
- Clé fournie enregistrée uniquement dans `.env` privé local (0600), jamais copiée dans les sources, exemples, logs ou production. Vérification de non-présence dans les sources/documents. Variables OPENAI_API_KEY, OPENAI_TRANSLATION_MODEL (défaut gpt-4.1-mini-2025-04-14) et OPENAI_TRANSLATIONS_ENABLED ajoutées à la configuration et Compose.
- Migration 015 : source = titre français + textes français générés à jour, empreinte incluant ces trois champs ; table séparée idee_outing_translation, file de demandes par sortie/langue et snapshots de batches persistants. Aucun backfill ni trigger automatique. Modification française seule ne lance pas de traduction.
- Nouvelle route privée POST `/api/admin/translations/generate-missing?limit=500` (1..500 sorties, cinq langues en/de/it/nl/es, au plus 2500 demandes). Réponse 202 avec accepted (sorties), requests (langues ajoutées), languages, limit, statusUrl. Répéter pour les lots suivants, sorties/langues déjà à jour ou en attente ignorées sans remise à zéro des reprises. GET `/api/admin/translations/status` privé pour le suivi.
- Worker ne soumet que les demandes explicitement mises en file. JSONL envoyé à /v1/files purpose=batch, puis /v1/batches chat/completions avec completion_window=24h, jusqu’à 2500 requêtes et budget source 2 millions de caractères. Métadonnée idee_translation_batch pour rapprochement après POST incertain, input_file_id persistant pour réutilisation après refus, aucun nouvel envoi payant aveugle. Collecte par custom_id sortie:langue, validation stricte titre/long/court et courte <=300 points de code Unicode, reprises des langues échouées seulement. Pas de transaction ni verrou de fiche pendant HTTP.
- Un résultat obsolète est écarté et la demande encore en file rapprochée de la référence française actuelle. Les traductions stockées sont masquées immédiatement si titre ou textes français changent. Le détail API expose `translations` (language/title/description_longue/description_courte) à jour ; français et traductions source importées conservés. Pas d’activation d’interface/URL multilingues dans cette tâche.
- Mode CLI français et génération unitaire désactivent le worker de traductions pour éviter de traiter des demandes étrangères à leur action. Recette : clés OpenAI/Mistral/DATAtourisme explicitement vides, workers Spring désactivés, quatre schémas jetables supprimés. Tests unitaires de JSON/protocole/authentification et six scénarios PostgreSQL de traduction réussis (lancement explicite, cinq langues/titre, source modifiée, erreurs partielles/reprises, coupure/refus de soumission, plafond500, concurrence). Toutes les suites Maven, Compose config et deploy.sh --check réussies.
- Documentation utilisée : compétence OpenAI Docs, pages officielles Batch API et GPT-4.1 Mini. Documentation API/MCP, déploiement, README et exemples d’environnement actualisés avec les commandes manuelles. Pour activation locale, l’utilisateur doit relancer l’API mise à jour ; la migration 015 seule ne met rien en traitement.

## Timeout API locale et copie du JAR exécuté — 8 octobre 2026

- Requête POST generate-missing expirant après 30 s. Diagnostic lecture seule : API PID 1053242 écoute 8087 ; routes en erreur restent sans réponse tandis que /api/categories répond 200. Aucun verrou PostgreSQL, aucun débogueur JDWP. `/tmp/idee-service-dev.log` montre NoClassDefFoundError/ClassNotFoundException de Logback ThrowableProxy. Le JAR target utilisé par l’API ancienne a été remplacé lors des constructions Maven des tâches précédentes.
- `start-front.sh` copie désormais le JAR construit dans un répertoire runtime privé temporaire, exécute cette copie et la supprime après arrêt de Java. `scripts/batch_descriptions.py` fait de même, y compris en arrière-plan via un enfant propriétaire de la copie. Évite que les constructions suivantes invalident les classes chargées tardivement.
- Vérification Bash, compilation Python et archive `deploy.sh --check` réussies. Aucun serveur arrêté/démarré/redémarré pendant le diagnostic ; l’utilisateur doit relancer l’aperçu pour charger les corrections, conformément à sa gestion des serveurs.
- État réel des traitements précédents : 8 batches SUCCESS totalisant 3 625 demandes, 3 622 résultats appliqués et 3 demandes en échec/remises en file. Le traitement global local demandé précédemment a donc bien soumis les sept lots suivants après le premier. Pas de nouvel appel Mistral pendant ce diagnostic.

## Premier batch Mistral réel local — 8 octobre 2026

- Utilisateur a demandé un premier traitement et choisi explicitement la base locale de développement (pas la production). 3 688 sorties publiées hors démo, 3 687 sans réécriture française avant traitement.
- Sauvegarde PostgreSQL locale privée vérifiée avant migrations : `/tmp/idee-before-first-batch-20261007T232333Z.dump` (0600). Migrations 013/014 appliquées par le lanceur ; la 012 était déjà présente.
- Ajout d’un mode CLI non HTTP `scripts/batch_descriptions.py` : un seul lot borné 1..500, collecte ciblée via --batch-id, --wait (26 h max), --background avec journal privé. Import DATAtourisme, calendrier et worker automatique désactivés pour ce processus ; collecte uniquement le batch soumis, sans autres envois. Test PostgreSQL supplémentaire vérifie limite et absence de soumission des autres travaux. Package Maven, 12 scénarios de worker et trois suites PostgreSQL jetables réussis.
- Lot réel de 500 accepté par Mistral : identifiant local `8034bc01-3169-4def-91ed-18b1ea82f979`, fournisseur `71724460-7889-46cf-982e-91277d12a620`, état initial QUEUED. Collecteur Java PID 1087708, journal `/tmp/idee-mistral-batch-uj7oui6y.log` (0600), actif après soumission. 500 jobs associés ; aucune description déjà existante écrasée à la soumission. Les résultats restent à produire/collecter ; vérifier le marqueur MISTRAL_BATCH_RESULT ou la base pour leur état courant.
- Commande exécutée : `python3 scripts/batch_descriptions.py --limit 500 --wait --background`. Pour reprendre seulement la collecte si le processus s’arrête : `python3 scripts/batch_descriptions.py --batch-id 8034bc01-3169-4def-91ed-18b1ea82f979 --wait --background`.
- Aucun serveur HTTP démarré/redémarré, aucun déploiement ni modification de la production. La clé Mistral reste dans le .env privé et l’environnement processus, jamais affichée.

## API Batch Mistral — 8 octobre 2026

- La génération automatique/import et le rattrapage API utilisent maintenant l’API Batch Mistral (prix annoncé -50 %, timeout 24 h), via demandes inline de 500 maximum, budget source 2 millions de caractères. La génération unitaire reste classique immédiate. Même clé et drapeau MISTRAL_AUTO_ENABLED ; aucun repli automatique au tarif classique.
- Migration 014 : batches persistants avec modèle/prompt/payload et identifiant fournisseur, snapshots et résultats par sortie ; association nullable depuis la file. Une nouvelle source détache le nouveau travail du batch ancien. Les résultats sont appliqués sous verrou bref si sortie publiée hors démo et source inchangée, sans écraser une génération manuelle à jour. Résultats absents/invalides/échoués seuls remis en file avec délai.
- Soumission/collecte sérialisées par verrou de session PostgreSQL API/cron, sans transaction ni verrou de fiche pendant HTTP. Intention SUBMITTING validée avant POST ; rapprochement par métadonnée idee_batch_id après coupure/timeout, pas de resoumission aveugle payante. Les refus 4xx hors 408 sont réessayables. Si aucune référence distante retrouvée, submission_unknown reste visible pour vérification opérateur, recherches automatiques maintenues.
- Worker examine toutes les 2 s, consulte chaque batch à 1 minute normalement, reprend erreurs réseau jusqu’à 1 h. Le cron termine après soumission des travaux prêts sans attendre 24 h ; garder l’API active avec sa clé pour collecter les résultats avant expiration. JSON inline ou fichiers JSONL pris en charge ; fichier confirmé disparu remet les demandes restantes en file.
- GET privé `/api/admin/descriptions/status` enrichi : mode=batch, inBatch, activeBatches, uncertainSubmissions et 20 batches récents avec identifiants/états/résultats/dates/erreurs sûres. Route generate-missing inchangée.
- Tests Java de protocole HTTP simulé et 11 scénarios PostgreSQL (dont lot de 500, réponses désordonnées/partielles/invalides, changement pendant attente, reprise après coupure, concurrence et import non verrouillé pendant POST) ; importeur et génération unitaire conservés. Recette dans trois schémas jetables supprimés. Documentation API, DATAtourisme, déploiement et README actualisés. Tests Maven, recette PostgreSQL et `deploy.sh --check` réussis. Aucun appel réel, déploiement, migration du schéma applicatif réel ni redémarrage.

## Rattrapage Mistral par API — 8 octobre 2026

- `POST /api/admin/descriptions/generate-missing?limit=500` protégé par le Bearer d’import, réponse 202 immédiate avec `accepted`, `limit`, `language`, `statusUrl`. Limite facultative 500, validée entre 1 et 500 ; 503 si worker désactivé ou clé Mistral absente.
- Ajoute les sorties publiées sans réécriture française correspondant à la source actuelle, hors démonstrations et sources françaises absentes. Ignore les travaux déjà en file, conserve les délais de reprise, prend les identifiants croissants et ignore les fiches verrouillées. Un nouvel appel ajoute le lot suivant. Verrou de demande distinct du verrou de génération pour ne pas attendre le réseau Mistral ; 409 en cas de demandes simultanées.
- Utilise la file et le worker existants, sans nouvelle migration. Suivi global via GET `/api/admin/descriptions/status`. Documentation et exemples curl dans `docs/api-mcp.md` et `docs/datatourisme.md`.
- Tests Maven réussis, tests PostgreSQL dans trois schémas jetables réussis et schémas supprimés : plafond 500, lot suivant, textes français à jour/obsolètes, textes anglais seuls, absence de source française, exclusions, reprises préservées, validation et authentification. Aucun appel fournisseur réel, déploiement ni redémarrage applicatif.

## Descriptions Mistral automatiques après import — 8 octobre 2026

- Demande : comparer la description lorsqu’une sortie est mise à jour et régénérer `description_longue`/`description_courte` avec Mistral si elle change ; même traitement pour les nouvelles sorties.
- Migration 013 : file persistante `idee_outing_description_job`, comparée à la source française des textes générés. Triggers différés à la validation de transaction sur sorties, traductions et relations DATAtourisme ; le DELETE/INSERT des traductions identiques n’entraîne aucun appel. Couvre aussi les créations API et écritures SQL. Brouillons/démonstrations/sources françaises absentes ignorés ; aucun rattrapage massif à la migration, les anciennes fiches manquantes sont traitées à leur prochain import/mise à jour.
- `OutingDescriptionWorker` : activé par défaut si clé présente (`MISTRAL_AUTO_ENABLED=true`), un job par passage après 60 s puis toutes les 2 s. Verrou PostgreSQL partagé API/cron, verrou de fiche compatible avec la génération manuelle, comparaison de la source courante avant génération. Les travaux réussis/manuels sont retirés de la file. Erreurs conservées avec code sûr et reprise progressive de 30 s à 1 h ; un nouvel import identique ne remet pas le délai à zéro, une nouvelle source le réinitialise. Les anciens textes restent conservés mais masqués par le mécanisme existant si leur source a changé.
- Batch DATAtourisme : traite aussi les descriptions dans sa boucle et poursuit les travaux prêts après la fin de pagination. Les jobs différés en échec restent pour l’API active ou le prochain cron. Le script cron lit aussi `deploy/.env` pour Mistral ; `remote-update.sh` rafraîchit l’image cron depuis `idee-api` après validation du site. Aucun secret transféré.
- Suivi privé ajouté : `GET /api/admin/descriptions/status` (Bearertoken d’import), avec enabled/configured/pending/ready/retrying/nextAttemptAt. Mode unitaire `MISTRAL_REWRITE_SLUG` désactive le worker automatique pour ne traiter que la sortie explicitement demandée.
- Tests : cinq scénarios PostgreSQL de file (source nouvelle/modifiée/inchangée, remplacement des traductions, échecs/reprises, rollback, brouillons/archives/démo, clé absente, concurrence), importeur réel simulant DATAtourisme et client Mistral factice, génération manuelle et protection du suivi. Runner reproductible `python3 scripts/check_description_jobs.py` : trois schémas jetables, clés fournisseurs explicitement vides, supprimés après tests. Tests Maven et 11 tests de déploiement simulé réussis ; Bash et Compose validés.
- Aucun appel Mistral réel, déploiement, redémarrage ou modification du schéma applicatif réel. La migration 013 et l’automatisation seront actives après démarrage de la version mise à jour avec MISTRAL_API_KEY configurée. Documentation DATAtourisme, API/MCP, déploiement et exemples d’environnement mis à jour.

## Rendu serveur Angular — 8 octobre 2026

- Demande explicite de rendu serveur pour le référencement. Ajout d’Angular SSR 22.1.8 et platform-server 22.1.7, configuration partagée navigateur/serveur, entrée Node et rendu de toutes les routes à la requête. Aucun prérendu de catalogue au build.
- HTML initial : accueil, fiches, descriptions, titre, description SEO, canonique et Open Graph. Hydratation avec rejeu des événements et transfert des réponses HTTP ; origine API interne associée à l’origine publique pour éviter les appels en double. Garde navigateur pour `requestAnimationFrame`, reprise du lien canonique SSR sans duplication. Statuts SSR 301 pour les anciennes adresses, 404 pour les fiches/routes inexistantes, 503 si l’API est indisponible. Compatibilité conservée avec l’API sans `url`.
- Service Compose `ssr` Node 22 non-root, sans port hôte, santé et limite mémoire ; Dockerfile frontend à deux cibles `ssr`/`web`. Nginx sert les ressources statiques, proxifie `/api` vers Java et les pages vers Node. `deploy/remote-update.sh` construit/recrée `api ssr web` et vérifie le marqueur SSR du HTML de l’accueil, en plus de la version d’archive et des contrôles précédents.
- `IDEE_PUBLIC_ORIGIN` dans `deploy/.env` configure l’origine canonique de production (défaut `https://idees.cavousdit.com`). Node ignore les origines fournies par les en-têtes visiteurs ; l’API utilisée en SSR est fixe (`API_ORIGIN`). Appels bornés à 10 s et HTML sans cache partagé. Le serveur autonome sert aussi les assets, mais le proxy API navigateur reste assuré par Nginx ou Angular CLI.
- Le lien `idee-front/node_modules` vers Wik a été retiré et remplacé par une installation locale propre à Idées. Wik n’a pas été modifié. Types Node 22.20.5 ajoutés pour compatibilité TypeScript 6 ; lockfile mis à jour. `npm start` définit l’origine locale par défaut sur `http://localhost:4401`.
- Validation : build SSR de production, `tests/test_ssr.mjs` sur le bundle normal puis sur une copie /tmp sans node_modules, tests Playwright sans JavaScript puis hydratation/DOM/navigation/filtres/mobile, tests des URL anciennes/nouvelles, 11 tests de déploiement simulé, Compose config, `nginx -t` sur copie de configuration et `deploy.sh --check`.
- Aucun déploiement, migration de base ni démarrage/redémarrage de serveur applicatif. Le compilateur Angular utilise un listener temporaire pour extraire les routes. En fin de tâche, l’API 8087 et l’aperçu 4401 ne répondaient pas ; la recette utilise des données simulées et le vrai moteur SSR compilé. Docker n’étant pas démarré, les images n’ont pas été construites/exécutées localement.
- Activation locale via `./start-front.sh`, déploiement via `./deploy.sh`. Documentation : `docs/server-rendering.md`, README et guide de déploiement mis à jour.

## Compatibilité des vignettes avec l’API existante — 8 octobre 2026

- Régression constatée : l’API locale active ne fournit pas encore `url`, donc les trois liens de chaque vignette étaient désactivés. Le champ est désormais facultatif côté frontend ; `outingUrl` centralise le repli `/sorties/{slug}` pour les cartes, le partage et la canonique.
- Si le résolveur de chemins renvoie 404 sur une adresse historique `/sorties/{slug}`, la fiche essaie l’API existante `/api/outings/{slug}`. Aucun repli sur les routes géographiques : département/commune incorrects restent refusés.
- Vérification : build Angular réussi, tests Playwright avec API ancienne/nouvelle (image, titre, bouton, détail et métadonnées), puis clic réel sur l’accueil local 4401 avec l’API existante 8087 réussi. Aucun redémarrage ni déploiement.

## URL publiques françaises et socle multilingue — 8 octobre 2026

- Demande : mettre en œuvre `/{departement}/{commune}/{slug-sortie}`, préparer les traductions des trois segments et préserver les anciens liens.
- Migration 012 : départements/communes et traductions, rattachement des lieux, reprise des codes INSEE concordants de DATAtourisme, slugs par sortie/langue, registre des chemins actuels et historiques. Génération à la première publication localisée, reprise des fiches existantes ; titres et localisations importés ne changent pas une URL déjà publiée. Collisions suffixées avec l’identifiant stable. Correction volontaire via `idee_publish_outing_url` ; fiches non localisées conservées sous `/sorties/{slug}`.
- API : champ `url` sur listes/détails, résolution complète via `/api/outing-by-path`, contrôle serveur via `/api/public-page`. Nginx sert le shell Angular par redirection interne, renvoie des 301 relatifs pour les anciennes adresses et conserve le statut 404 pour les chemins incorrects/archives. Préfixes API prioritaires.
- Angular : liens catalogue, partage et canonique utilisent `url` ; chemins historiques remplacés dans l’historique du navigateur en conservant paramètres/ancre. Le français seul est publié ; les traductions peuvent être stockées mais les pages de/en et `hreflang` attendent leur activation complète.
- Validation : tests Java, build Angular, recette PostgreSQL dédiée (HTTP via MockMvc, collisions, archives, anciennes URL, INSEE, Unicode), recette de l’import DATAtourisme, migration de fiches préexistantes dans une transaction annulée, test Playwright sur assets compilés sans serveur, syntaxe Nginx (`nginx -t` sur une copie avec upstream local et port non privilégié), `deploy.sh --check`.
- Aucun serveur d’application démarré/arrêté/redémarré, aucun déploiement ni migration du schéma applicatif réel. Les schémas PostgreSQL de recette ont été supprimés. La migration 012 s’appliquera au prochain démarrage de l’API mise à jour ; frontend/API/Nginx doivent être déployés ensemble.
- Documentation : `docs/public-urls.md`. Tests reproductibles : `scripts/check_public_urls.py`, `tests/test_public_urls.cjs`, `PublicUrlsDatabaseTest` et `DatatourismeDatabaseTest` dans un schéma isolé.

## Fiche en français, description longue en haut — 7 octobre 2026

- Demande utilisateur : afficher `description_longue` en haut de la fiche, retirer la présentation en bas et utiliser uniquement le français. Le texte long figure sous le titre ; l'ancre `presentation` est déplacée sur l'en-tête pour conserver le lien « La sortie ». Le bloc « Au programme » et le sélecteur de langue sont supprimés.
- Repli sur la description source française si aucune réécriture n'existe ; aucune sélection automatique d'une autre langue. Les vignettes conservent `description_courte`. Les traductions restent conservées en base.
- Build Angular et tests navigateur des descriptions et détails source réussis (vignette, texte long en haut, absence de doublon/sélecteur, cas sans français, contacts et mobile). La vraie API locale expose maintenant les descriptions générées : vérification de la fiche réelle réussie, sans redémarrage de serveur par l'agent.

## Descriptions Mistral — test local du 7 octobre 2026

- Demande : conserver la description source multilingue et ajouter `description_longue` et `description_courte` multilingues (courte <=300 caractères). Génération en français uniquement pour commencer, avec Mistral Small 4 (`mistral-small-2603`).
- Migration 011 appliquée à la base locale : table `idee_outing_description` par sortie/langue avec les deux textes, la source utilisée, le modèle, la version du prompt et la date ; vue `idee_outing_description_source` depuis les traductions DATAtourisme (ou description française des fiches hors source). Les imports ne remplacent pas les textes générés ; une source modifiée les masque jusqu'à régénération explicite.
- Clé utilisateur stockée uniquement dans `.env` privé 0600, variable `MISTRAL_API_KEY` ; ne jamais l'afficher ni la copier dans les documents/tests. Compose prévoit ses variables facultatives, mais aucun secret n'a été transféré sur OVH et aucun appel Mistral n'a été ajouté au cron.
- Traitement unitaire : `python3 scripts/rewrite_description.py SLUG` après construction Maven. Mode sans HTTP, import DATAtourisme et projection calendrier désactivés pour ce processus. Endpoint protégé également prêt : `POST /api/admin/outings/{slug}/descriptions/generate`. Résultats identiques réutilisés sans appel ; validations JSON, longueur Unicode et contrainte SQL ; verrou transactionnel sur la sortie.
- Test réel réussi pour `datatourisme-0d8c9292-f0d7-349a-b90b-00c4987cd936` (id 95, exposition de Thanvillé) : un appel Mistral, un seul enregistrement français, texte long de 647 caractères et court de 219 caractères. Six descriptions sources comparées avant/après et intégralement conservées. Compte rendu `/tmp/idee-mistral-test.txt`, résultat JSON `/tmp/idee-mistral-result.json`.
- Sauvegarde locale avant migration : `/tmp/idee-before-mistral-20261007T212013Z.dump`, vérifiée, 0600. Aucune modification distante.
- API et frontend adaptés : détail `descriptions` par langue, champ français `description_courte` sur les listes ; texte court en carte/introduction, texte long dans la présentation, repli sur la source dans les autres langues.
- Validation : 21 tests unitaires Java, recette PostgreSQL isolée supprimée, build Angular et `deploy.sh --check` réussis. Test navigateur français/anglais/mobile réussi via interception avec le JSON réellement lu en base, car l'API locale existante n'a pas été redémarrée. L'utilisateur doit relancer l'API pour charger les nouveaux champs sur la fiche ; aucun serveur démarré/arrêté/redémarré par l'agent.

## Déploiement sans transfert de données locales — 7 octobre 2026

- Demande de retirer la migration des données du déploiement : suppression des options `--export-db` et `--replace-db`, de l'export local, du transfert de dump, des restaurations et bascules de bases dans `deploy.sh` et `deploy/remote-update.sh`.
- Le script distant refuse désormais tout cinquième argument pour empêcher un ancien appel de fournir un dump. La sauvegarde de la production avant mise à jour et les migrations Liquibase au démarrage restent conservées ; aucune base locale n'est lue par le déploiement.
- Documentation révisée, tests de restauration supprimés, tests de rejet des anciennes options et de l'ancien argument ajoutés. `./deploy.sh --check`, syntaxe Bash et 10 tests de déploiement simulé réussis. Aucun accès ni déploiement distant.
- Les sections historiques ci-dessous décrivant les anciennes options sont obsolètes sur ce point.

## Correction de l'archive de déploiement — 7 octobre 2026

- Le lanceur présent est `start-front.sh`. L'archive et la copie/chmod distante utilisaient encore `run-dev.sh`, absent : références corrigées dans `deploy/package.sh`, `deploy/remote-update.sh`, les fixtures de test et le README.
- Ajout d'une vérification de l'archive du projet réel via `deploy.sh --check`, pour détecter un fichier référencé mais absent des sources (les fixtures seules masquaient ce cas).
- Validation : `./deploy.sh --check` réussi, 17 tests de déploiement simulé réussis, syntaxe Bash vérifiée. Aucun déploiement ni accès distant pour cette correction.

## Cron quotidien sur OVH — 7 octobre 2026

- Demande utilisateur de tâche cron quotidienne exécutée : cron installé et activé au démarrage. `/etc/cron.d/idee-datatourisme` lance à 04:00 UTC chaque jour (06:00 Paris en été, 05:00 en hiver), sous `debian`, `deploy/datatourisme-cron.sh`.
- Image indépendante `idee-datatourisme:cron`, mode Java `DATATOURISME_BATCH=true` sans HTTP, réseau `idee_default`, base existante. Verrou flock, nom de conteneur unique, respect des quotas et checkpoints, sortie après les deux départements ou délai maximal de trois heures. L'image est à reconstruire séparément si le connecteur évolue.
- Clé DATAtourisme locale copiée sans affichage dans le fichier privé distant `deploy/.env.datatourisme` (0600), avec les secrets DB/import distants. Aucun changement du `deploy/.env` du site ; son import intégré reste désactivé. Ne jamais afficher ni archiver les fichiers privés.
- Première exécution réussie le 7 octobre, 23:03 Europe/Paris : 866 fiches traitées pour le 67 et 749 pour le 68, soit 1 615 fiches ; catalogue source passé de 2 969 à 3 688 UUID (719 nouvelles fiches). Aucun département en attente, aucun curseur actif ni erreur. Migration 010 appliquée en production par le conteneur de traitement.
- Sauvegarde vérifiée avant exécution : `/home/debian/idee/backups/datatourisme-cron-20261007T210132Z/database.dump`, 0600. Conteneur API existant conservé et santé HTTPS vérifiée ; aucun redémarrage du site.
- Journal : `/home/debian/idee/logs/datatourisme-cron.log`, rotation logrotate quotidienne, 14 archives. Sources du traitement préparées dans `.datatourisme-build` sur OVH, fichiers cron/install/rotation dans `deploy/`. Documentation : `docs/datatourisme.md`. Les 17 tests unitaires passent.

## Synchronisation à la demande — 7 octobre 2026

- Import quotidien incrémental DATAtourisme existant conservé ; ajout de `POST /api/admin/datatourisme/sync`, protégé par le Bearer d'import, pour demander un parcours sans attendre 24 heures.
- Migration 010 : `sync_requested` persistant par département. Demandes répétées sans remise à zéro du curseur ni de la date du précédent parcours ; même verrou inter-instances et même quota que le worker planifié. HTTP 202 pour mise en attente, 409 si une page détient le verrou, 503 si import désactivé ou clé source absente.
- Suivi enrichi : `enabled`, `nextRequestAt`, `sync_requested`. Documentation et exemples dans `docs/datatourisme.md`.
- Validation : 17 tests unitaires réussis et recette PostgreSQL dans un schéma isolé (migration, ajout, modification, watermark, pagination et demandes répétées), supprimé après réussite. Les sorties réelles n'ont pas été modifiées.
- Aucun redémarrage de serveur ni déploiement. Migration 010 appliquée uniquement dans le schéma de recette ; sera appliquée au prochain démarrage de la version mise à jour.

## Catalogue complet et périodes — 25 septembre 2026

- Bouton Calendrier ouvrant un popup Angular Material français, semaine dès lundi, thème crème/vert sauge. `period=date&date=YYYY-MM-DD`, filtres et pagination conservés. Date affichée sur le bouton et conservée au retour d’une fiche ; boutons de période pour quitter la date précise. Recette : `tests/test_catalog_date.cjs`.
- L’utilisateur gère les serveurs : ne jamais les démarrer, arrêter ou redémarrer sans demande explicite. Pour cette modification du calendrier Material, seul l’aperçu déjà actif a été consulté.

- Filtres département et dix grandes villes visibles ; listes fondées sur la population de l’API géographique publique, sélection conservée dans CatalogState.
- `/api/catalog` applique période, département, ville, catégorie, recherche et gratuité avant pagination ; réponse `items`, `total`, `hasMore`, lots de 40 maximum. L’ancienne route `/api/outings` conserve son contrat API/MCP.
- Aujourd’hui par défaut ; boutons Ce week-end, Cette semaine (ou La semaine prochaine samedi/dimanche), Toutes les dates. Calcul serveur Europe/Paris, récurrences et exceptions sur la période ; dates de fin exclusives.
- Défilement automatique par lots de 40, reprise manuelle en cas d’erreur, annulation des anciennes requêtes et remise à zéro au changement de filtre. Compteur affichés/total.
- Recette en lecture seule : `tests/test_catalog.cjs`. Tests de bornes : `CatalogPeriodTest`. Aperçu local relancé via `./run-dev.sh` ; aucun déploiement distant.

## Informations enrichies sur la fiche sortie — 25 septembre 2026

- `GET /api/outings/{slug}` ajoute `sourceDetails` (null sans source DATAtourisme), avec champs publics sélectionnés : contacts et canaux, traductions, thèmes/services/langues, lieux, ressources non-images et provenance. Les listes restent légères ; aucun JSON brut ni checkpoint n'est exposé.
- Page détail : présentation avec choix de langue (français par défaut si disponible), contacts par rôle et liens tel/mail/web, thèmes et langues d'accueil, lieux supplémentaires, documents/liens, provenance repliable (producteur, éditeur, dates, référence). Les sections absentes sont masquées. Les libellés techniques des périodes DATAtourisme ne sont plus affichés.
- Valeurs source affichées comme texte ; liens web limités à HTTP(S) sans identifiants, téléphone et courriel validés avant création du lien. Les producteurs/éditeurs restent distincts des organisateurs et contacts visiteurs.
- Contrôle navigateur via `tests/test_source_page.cjs` : fiche réelle sur ordinateur/mobile, langue anglaise et absence de français, contacts, provenance, ressources et lieux multiples simulés, liens dangereux neutralisés, fiche sans enrichissement, absence de débordement mobile. Aucune fiche de test ajoutée au catalogue ; recette SQL dans un schéma isolé supprimé ensuite.
- Builds Angular et Maven réussis, contrat API vérifié en PostgreSQL. Aucun déploiement OVH pour ces modifications ; aperçu local relancé via `./run-dev.sh`.

## Enrichissement DATAtourisme — 25 septembre 2026

- Migration 009 appliquée à `idee` local : métadonnées source typées, traductions, contacts par rôle et canaux multiples, termes/classifications, lieux/adresses multiples, ressources principales et secondaires. Contraintes d'unicité, clés étrangères et index de recherche.
- Import désormais en `fields=*` et `lang=*`. `selection_version` provoque un nouveau parcours complet une fois après migration, puis conserve la reprise et les synchronisations normales. Ne pas faire tourner un ancien worker pendant ce parcours.
- Rechargement terminé pour les deux départements, sans erreur : 2 969 fiches enrichies, 17 579 lignes de traduction dans six langues, 14 336 contacts/rôles rattachés aux fiches (pas des personnes uniques), 13 918 coordonnées, 2 969 lieux/adresses, 3 459 références de ressources. Galerie : 2 146 images dédupliquées par sortie, contre 1 104 auparavant.
- Les 5 160 périodes et les six sorties préexistantes hors DATAtourisme sont conservées ; les 13 périodes incohérentes restent signalées. Une fiche complète JSONB a été comparée à son détail API : égalité exacte.
- Producteurs et éditeurs ne sont pas assimilés aux organisateurs. Les pages d'accueil ne sont pas transformées en liens de réservation. Les liens non reconnus comme images restent dans la table de ressources et le JSON source.
- Tests : 11 tests unitaires DATAtourisme, test de volume calendrier et recette PostgreSQL isolée réussis ; proxy Angular, fiche enrichie et archive de déploiement vérifiés. Schéma de recette supprimé.
- Sauvegarde locale avant migration : `/tmp/idee-before-details-20260925.dump`, format pg_dump custom, schéma public, permissions 0600. Aucun changement sur OVH.
- L'aperçu lancé par l'utilisateur a été redémarré via `./run-dev.sh` et reste disponible sur 4401 avec son API sur 8087. Aucun second serveur de vérification n'a été laissé en parallèle.
- Documentation à jour : `docs/datatourisme.md` et `docs/data-model.md`.

## Import DATAtourisme — 25 septembre 2026

- Connecteur Spring Boot activé localement via `DATATOURISME_API_KEY` et `DATATOURISME_ENABLED` dans `.env` privé (0600). Ne jamais afficher la clé ni la copier dans les sources.
- Migrations 007/008 appliquées à la base locale `idee` : fiches JSONB avec UUID unique, périodes brutes, présence par département, checkpoints, quota persistant et précision des horaires.
- Chargement complet local terminé : 67 = 1 623 fiches / 7 pages ; 68 = 1 346 fiches / 6 pages ; 2 969 UUID uniques, 5 160 périodes, 1 104 images. Les six anciennes démos ont été conservées.
- Treize périodes sont conservées avec `parse_error` car aucun jour de semaine indiqué ne tombe dans leur plage de dates. Les heures partielles sont prises en charge avec `startTimeKnown` / `endTimeKnown`, sans afficher les bornes techniques comme des heures annoncées.
- Pagination : suivi exact de `meta.next`. L'API réelle omet ce champ sur la dernière page ; le connecteur exige alors une confirmation via les métadonnées. Aucun filtre start/end au premier import. `update` vérifié sur l'API réelle.
- Synchronisation 24 h après chaque fin de parcours, recouvrement de deux jours depuis le début du précédent ; parcours complet tous les 30 jours, archivage des absents après achèvement seulement. Quota et temporisation 429 persistants, reprise transactionnelle par page, verrou PostgreSQL inter-instances.
- Suivi protégé : `GET /api/admin/datatourisme/status`. Documentation : `docs/datatourisme.md`.
- Calendrier désormais calculé par lots pour dépasser les 20 000 occurrences du pont Python ; insertion du cache par lots. Frontend adapté aux horaires inconnus.
- Validations : 10 tests DATAtourisme, test calendrier Java de 22 000 occurrences, recette PostgreSQL isolée (pagination, rollback, upsert, images, horaires partiels, archivage), 9 tests Python existants, build Angular et archive de déploiement. Schéma de recette supprimé après validation.
- Aucun déploiement distant effectué pour cette intégration. Compose transmet les variables DATAtourisme, mais l'activation sur OVH nécessite leur configuration dans le `deploy/.env` distant et le déploiement du code.

- Demande : copier le visuel Wik, nouveau nom « Idées de sorties en Alsace », préfixe `idee`, pas de connexion utilisateur.
- Source du thème : `../wik/wik-front`, Angular 22, styles Modernize/Wik réutilisés. Le projet Wik n’a pas été modifié.
- Front : `idee-front`, port 4401, proxy API 8087. Dépendances localement liées à celles de Wik ; package et lock autonomes.
- Service : `idee-service`, Spring Boot 3.4.2, Java 21, JdbcTemplate, Liquibase. API publique de lecture et création protégée par clé, plus pont MCP stdio.
- Base cible : PostgreSQL localhost:5432, nom `idee`, utilisateur `htpweb`. Mot de passe par environnement, jamais à committer.
- Initialisation : administrateur `sudo -u postgres createdb -O htpweb idee`, puis `.env` et `./run-dev.sh`.
- Calendrier : règles RRULE en heure locale Europe/Paris, exceptions, cache d’occurrences calculé par `scripts/project_calendar.py` (python-dateutil). Le service renouvelle le cache au démarrage et chaque nuit (03:15 Europe/Paris). La lecture par date est calculée à la demande et les imports calculent leurs occurrences immédiatement.
- Six fiches de démonstration fictives. Ne pas les présenter comme des événements vérifiés.
- Structure détaillée et limites : `docs/data-model.md`.
- Aucun dépôt Git initialisé dans ce répertoire à la création.

## État actuel

- Base `idee` créée par l’utilisateur et vérifiée sur PostgreSQL localhost:5432 ; connexion applicative `htpweb`.
- Les quatre migrations Liquibase sont appliquées : 16 tables métier, six fiches fictives, 181 occurrences calculées.
- Aperçu actif sur http://localhost:4401 ; service 8087 connecté à la base définitive sur 5432. Démarrage via `./run-dev.sh`.
- Instance PostgreSQL temporaire 5547 arrêtée après la bascule. Son répertoire `/tmp/idee-test-postgres` n’est plus utilisé.
- `.env` local ignoré et protégé contient la configuration de la base ; ne pas afficher ni committer les identifiants.
- Validation : build Angular et package Maven, 9 tests calendrier ; tests d’intégration sur la vraie base (4 contraintes, catalogue, détail et couverture). Front et proxy API HTTP 200.
- Contrôle des ports de `run-dev.sh` corrigé avec SO_REUSEADDR pour éviter de confondre TIME_WAIT et un serveur actif.
- Aucun déploiement distant : Spring Boot + PostgreSQL local incompatibles tels quels avec le runtime Cloudflare Sites.

## API, MCP et serveur dédié

- Demande actuelle : installer un site vide de sorties sur le serveur dédié et utiliser MCP/API pour lire les sorties d’une date et en créer.
- `GET /api/outings?date=YYYY-MM-DD` : Europe/Paris, récurrences et exceptions à la demande, pagination limit/offset. `includePermanent` et `includeCancelled` optionnels.
- `POST /api/admin/outings` : clé Bearer `IDEE_IMPORT_TOKEN` (>=32 caractères), validation, transaction, identité sourceName/externalId et empreinte de contenu pour les répétitions sans doublon. Toute écriture exige la clé.
- Nouvelle migration 004 : source_name, external_id, import_hash et unicité d’import.
- `idee-mcp/server.py` : SDK MCP officiel 1.30.0, transport stdio, outils list_categories/list_outings/create_outing. Appelle l’API HTTPS, n’accède pas à PostgreSQL. Ce n’est pas un endpoint MCP HTTP public.
- `compose.yaml` + Dockerfiles + deploy/nginx.conf : serveur autonome, base sans sorties en contexte production, API/DB sans ports hôte et web lié à localhost:9081. Raccorder au reverse proxy HTTPS du dédié.
- `deploy/.env` privé généré (chmod 0600), secrets distincts du développement, jamais à afficher/committer. `deploy/init_env.py` refuse d’écraser des secrets existants.
- Recette Docker sur le projet isolé idee-integration / port 9082 : base vide vérifiée, 7 tests API réussis, handshake et trois outils MCP testés avec création et répétition sans doublon. Images compilées. Les données de cette recette ne doivent jamais être envoyées au serveur public.
- `.npmrc` frontend reproduit `legacy-peer-deps` hérité de Wik, nécessaire au `npm ci` propre (angular-tabler-icons déclare Angular 17–19). Le lockfile est conservé et la construction Docker est validée.
- **Déploiement distant en attente** : utilisateur interrogé sur le domaine et la cible SSH ; un alias `ovh` existe dans sa configuration locale mais aucune connexion ou publication distante n’a été effectuée pour cette tâche.
- Documents : deploy/README.md et docs/api-mcp.md ; docs/outing.example.json est un gabarit, pas des données à charger.

- Recette achevée : conteneurs et volume du seul projet `idee-integration` supprimés après vérification de persistance/recalcul au redémarrage. Aucun test importé dans la base locale ou distante.
- Aperçu local redémarré avec les nouvelles API ; migration 004 appliquée, clé d’import locale privée générée dans `.env`. Les anciennes démos locales sont conservées ; l’installation distante neuve sera vide.
- Archive prête `/tmp/idee-deploy.tar.gz`, recréable avec `deploy/package.sh`, vérifiée sans .env, sans node_modules et sans copie de base. Aucun déploiement distant effectué.

## Transfert sur OVH

- L’utilisateur a confirmé l’alias SSH `ovh` et demandé de déposer le projet dans son dossier `idee`.
- Dossier résolu et initialement vide : `/home/debian/idee`.
- Sources et fichiers de déploiement transférés via l’archive contrôlée ; aucun .env, base de données, dépendance installée ni sortie compilée locale n’est transféré.
- Aucun service distant démarré pour cette étape. Le domaine reste à préciser pour le déploiement HTTPS.

## Mise en ligne — 23 septembre 2026

- Utilisateur a autorisé le raccordement et la mise en ligne de `idees.cavousdit.com` sur `ovh`.
- Site en ligne : https://idees.cavousdit.com ; DNS existant correctement configuré, aucun changement DNS nécessaire.
- Compose démarré depuis `/home/debian/idee`, secrets créés sur place dans deploy/.env, contexte production, 0 sorties et 6 catégories.
- Apache système (pas Nginx système) gère le domaine avec le nouveau fichier `/etc/apache2/sites-available/idees.cavousdit.com.conf` ; autres vhosts conservés. Proxy vers 127.0.0.1:9081.
- Certificat Let's Encrypt obtenu, HTTPS et redirection validés ; timer de renouvellement existant actif, hook Apache dédié ajouté.
- API publique accessible par date. Import autorisé seulement avec la clé du deploy/.env **distant**, différente de la clé locale. Vérification HTTPS sans création : sans clé 401, clé valide + objet vide 400.
- Informations distantes précédemment marquées « en attente » sont désormais résolues. Les documents deploy/README.md et docs/api-mcp.md ont l’URL et les chemins effectifs.

- Test de renouvellement Let’s Encrypt (`certbot renew --cert-name idees.cavousdit.com --dry-run`) réussi après mise en place du HTTPS.

## Audit de sécurité demandé le 23 septembre 2026

- Audit OVH en lecture seule terminé, aucune correction distante appliquée.
- Rapport privé local : `audits/ovh-2026-09-23.md` (0600, répertoire 0700, ignoré Git, hors archive de déploiement).
- Risque P0 : PostgreSQL Wik 4047 accessible extérieurement ; secret faible configuré confirmé identique au vérificateur actuel, rôle htpweb superutilisateur. Aucun secret affiché dans le rapport.
- Autres constats : ports Keycloak DB 6570/6571 publics, accès HTTP backend directs, chaînes DOCKER-USER vides malgré UFW actif, Fail2ban en échec, noyau installé plus récent que noyau actif et redémarrage demandé, .env indicatifs lisible localement, sauvegardes complètes non confirmées, logs non bornés.
- Le site idées a sa DB et son API isolées, mais son rôle DB est superutilisateur et son durcissement en-têtes/logs/sauvegardes reste à faire. Ne pas présenter la configuration livrée comme entièrement durcie.
- L’utilisateur n’a pas encore demandé d’appliquer les corrections de cet audit. Ne pas modifier les accès, ports ou secrets sans une demande correspondante.


## Tunnel OpenAI — préparation sur OVH

- Demande explicite d'installation : binaire officiel tunnel-client v0.0.14 téléchargé et SHA256 vérifié, `/home/debian/idee/.tunnel/bin/tunnel-client`.
- MCP .venv installé sur OVH ; wrapper deploy/run-mcp.py charge uniquement IDEE_IMPORT_TOKEN distant et lance le MCP. Handshake + list_tools + deux lectures vérifiés.
- Unité idee-tunnel.service installée et vérifiée, pas encore activée : manque identifiant tunnel et clé runtime OpenAI du compte utilisateur. Ne pas annoncer le tunnel connecté.
- Saisie privée prête : ssh -t ovh 'python3 /home/debian/idee/deploy/activate-tunnel.py'. Crée profil, clé0600, active service et vérifie readyz. Interface admin loopback seulement, aucun port entrant ajouté.
- .tunnel ignoré Git/hors archive ; aucune sortie créée et aucune correction de l'audit serveur appliquée.


## Images des sorties — déployé le 23 septembre 2026

- Migrations 005/006 : `idee_media.is_primary`, une seule principale par sortie, URL unique par sortie et index de lecture.
- API de création accepte `images` (0–12) ; si présentes, exactement une principale, URL HTTPS et alt obligatoires.
- POST `/api/admin/outings/{slug}/images` ajoute des images à une fiche existante de façon idempotente.
- MCP expose désormais cinq outils, dont `add_outing_images` et le raccourci à paramètres plats `add_primary_image_by_url`; `create_outing` accepte aussi les images.
- Frontend : couverture fixe 180 px recadrée au centre par `object-fit: cover`, image principale dans le détail et galerie secondaire avec crédits. Les fichiers restent hébergés à leur URL HTTPS ; aucune copie binaire locale.
- Validation : builds Maven/Angular, 9 tests API isolés, handshake et appels MCP création/ajout/répétition. Environnement et volume de test supprimés.
- Production OVH sauvegardée avant migration dans `backups/idee-before-images-20260923T144138Z.dump`; une sortie avant/après, migrations appliquées, API/tunnel ready.
