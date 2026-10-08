# Import DATAtourisme

Le service importe `/v1/entertainmentAndEvent` pour le Bas-Rhin (67), puis le Haut-Rhin (68). Configuration privée dans `.env` en local ou `deploy/.env` sur le serveur :

```dotenv
DATATOURISME_API_KEY=cle-fournie-par-datatourisme
DATATOURISME_ENABLED=true
```

Redémarrer le service après configuration (en production, recréer le conteneur API pour actualiser son environnement). Liquibase applique automatiquement les migrations 007 à 014 au démarrage. Le job démarre après 30 secondes puis traite une page par passage, avec un délai de 5 secondes entre passages.

Le premier parcours n'utilise ni `start`, ni `end`, ni `update`. Chaque page demande 250 fiches, tous les champs (`fields=*`) et toutes les langues disponibles (`lang=*`). Le site continue d'afficher les textes français. La page suivante reprend exactement `meta.next` ; aucune pagination numérique n'est reconstruite. L'API réelle omet parfois `next` sur la dernière page : l'import accepte aussi cette forme lorsque les métadonnées confirment la dernière page ou un catalogue vide. La clé est envoyée dans `X-API-Key`, jamais dans l'URL. Les redirections HTTP sont refusées et les curseurs doivent rester sur l'origine DATAtourisme.

La migration 009 versionne la sélection des champs. Au premier démarrage du nouveau connecteur, chaque département repart une fois en parcours complet, même si le précédent import vient de finir : les anciennes fiches inchangées sont ainsi enrichies. Un curseur portant l'ancienne sélection n'est pas repris. Les pages du nouveau parcours restent reprenables normalement. Déployer en arrêtant les anciens workers avant de démarrer les nouveaux, pour éviter qu'une ancienne version traite une page sans ses nouvelles projections.

Chaque page est transactionnelle : fiches, périodes, occurrences et curseur sont validés ensemble. Une erreur conserve le dernier curseur validé. L'unicité du UUID est assurée dans `idee_datatourisme_event` et celle de `(source_name, external_id)` dans le catalogue. Les mises à jour conservent l'identifiant et le slug de la sortie. Le JSON original conserve aussi les offres, producteurs, lieux et métadonnées non projetés dans le modèle du site.

Les périodes originales sont toutes conservées dans `idee_datatourisme_period`. Les jours `appliesOnDay` deviennent des récurrences hebdomadaires ; les plages horaires sur plusieurs jours deviennent des récurrences quotidiennes. Une date de fin sans heure est inclusive. Pour les horaires incomplets, le calendrier emploie des bornes de journée pour la recherche et expose `startTimeKnown` / `endTimeKnown` : les bornes inconnues ne sont pas des horaires communiqués. Le site affiche explicitement « fin non communiquée », « début non communiqué » ou « Horaires non communiqués ». Les dates incohérentes et jours non pris en charge restent en base avec `parse_error`. Une fiche sans calendrier exploitable reste consultable au catalogue, mais n'apparaît pas dans une recherche par date.

Une nouvelle synchronisation a lieu 24 heures après la fin du dernier parcours. Elle utilise `update` avec la date UTC de début du précédent parcours, moins deux jours. Tous les 30 jours, le job effectue un parcours complet. Les absences ne sont marquées qu'à la fin réussie d'un parcours complet du département ; une fiche n'est archivée que lorsqu'aucun département ne la référence encore. Les fiches `isObsolete=true` sont archivées, les données brutes conservées.

Le quota persistant impose au minimum quatre secondes entre requêtes (le délai du job ajoute cinq secondes après chaque traitement). Les erreurs HTTP 429 suspendent les appels au moins une heure, ou plus si `Retry-After` le demande. Les autres erreurs attendent au moins cinq minutes. Cette limitation coordonne les instances partageant cette base, pas les autres applications utilisant la même clé.

Suivi protégé par le Bearer `IDEE_IMPORT_TOKEN` existant : `GET /api/admin/datatourisme/status`. Il expose les pages, fiches et erreurs par département, la version de sélection, le nombre de fiches enrichies (`completeEvents`), contacts, traductions, ressources et périodes non interprétées. Désactiver `DATATOURISME_ENABLED` puis redémarrer suspend l'import ; le réactiver reprend la progression en base. Les curseurs expirés ou pages durablement invalides nécessitent un diagnostic ; ils ne déclenchent jamais un archivage automatique.

## Déclenchement manuel

### Cron sur OVH

La tâche `/etc/cron.d/idee-datatourisme` lance chaque jour à **04:00 UTC** (06:00 à Paris en été, 05:00 en hiver) `/home/debian/idee/deploy/datatourisme-cron.sh`. Cron est activé au démarrage du serveur. Le traitement utilise l'image dédiée `idee-datatourisme:cron`, le réseau `idee_default` et la base de production, sans redémarrer l'API du site. L'import intégré à l'API reste désactivé pour conserver une seule planification.

Le mode `DATATOURISME_BATCH=true` démarre sans serveur HTTP, demande une synchronisation des deux départements, traite les pages avec les quotas et checkpoints habituels, puis ferme le contexte Java et sort. Après trois heures de traitement maximum, il sort en erreur en conservant la progression pour le prochain lancement. Un verrou `flock` et un nom de conteneur unique empêchent les exécutions concurrentes.

La configuration privée `deploy/.env.datatourisme` (0600) contient la clé DATAtourisme, le mot de passe de la base et la clé d'import. Elle est exclue des archives de sources. Si un secret de production change, actualiser aussi ce fichier. Les journaux sont dans `logs/datatourisme-cron.log`, avec rotation quotidienne et 14 archives compressées. L'état détaillé reste dans `idee_datatourisme_state`.

Lancer manuellement le même traitement sur le serveur :

```bash
/home/debian/idee/deploy/datatourisme-cron.sh
```

Le déploiement normal actualise l’image `idee-datatourisme:cron` à partir de la nouvelle image API. Une reconstruction manuelle avec le Dockerfile du service reste possible pour une mise à jour isolée du connecteur. Les fichiers d'installation sont conservés dans `deploy/` ; `install-datatourisme-cron.py` lit la clé via une entrée standard privée, jamais via les arguments. Ne pas afficher ni copier les fichiers d'environnement dans les journaux.

### API protégée

`POST /api/admin/datatourisme/sync`, avec le Bearer `IDEE_IMPORT_TOKEN`, demande une synchronisation des deux départements sans attendre les 24 heures. Exemple avec les variables privées déjà chargées dans le shell :

```bash
curl --fail-with-body -X POST \
  -H "Authorization: Bearer $IDEE_IMPORT_TOKEN" \
  http://127.0.0.1:8087/api/admin/datatourisme/sync

curl --fail-with-body \
  -H "Authorization: Bearer $IDEE_IMPORT_TOKEN" \
  http://127.0.0.1:8087/api/admin/datatourisme/status
```

En production, utiliser l'origine HTTPS du site et sa clé d'import distante. Ne pas activer la trace du shell lors de ces appels.

La réponse HTTP 202 contient `queuedDepartments` (0 à 2) et `statusUrl`. Elle confirme la mise en attente, pas la fin de l'import. Le worker traite la demande au prochain passage autorisé par le quota. Une page déjà en cours peut provoquer HTTP 409 : réessayer quelques instants plus tard. HTTP 503 indique que le connecteur est désactivé ou que sa clé DATAtourisme manque.

Les demandes répétées ne remettent pas à zéro un parcours en cours. La demande est persistée et survit à un redémarrage, tout comme le curseur de pagination. Le watermark du précédent parcours est conservé : le filtre `update` inclut les sorties modifiées et nouvelles avec deux jours de recouvrement ; l'upsert conserve identifiants et slugs sans doublons. Le premier chargement et le renouvellement complet tous les 30 jours suivent les mêmes règles que le job quotidien.

Le suivi expose `enabled`, `nextRequestAt` (prochaine requête permise par le quota) et, par département, `sync_requested`, `running`, `started_at`, `completed_at`, `pages`, `objects` et `last_error`. Après une demande acceptée, attendre `sync_requested=false` et `running=false` pour les deux départements, avec une nouvelle date `completed_at` et sans erreur. Une réponse incrémentale vide ne provoque aucun archivage.

## Descriptions réécrites avec Mistral

La migration 011 ajoute `idee_outing_description`, indexée par sortie et langue, avec `description_longue`, `description_courte` (contrainte PostgreSQL de 300 caractères), le texte source utilisé, le modèle et la date de génération. La vue `idee_outing_description_source` expose les descriptions DATAtourisme dans leurs langues d'origine ; les fiches hors DATAtourisme utilisent leur description existante en français.

Les descriptions sources sont conservées intégralement. Les imports DATAtourisme continuent à actualiser leurs traductions sans écraser les textes générés. Si la source change, les anciens textes générés sont conservés mais ne sont plus affichés jusqu'à une nouvelle génération explicite.

Configurer `MISTRAL_API_KEY` dans le `.env` privé et `MISTRAL_MODEL=mistral-small-2603` ([Mistral Small 4](https://docs.mistral.ai/models/mistral-small-4-0-26-03)). La clé n'est jamais envoyée au navigateur. La génération automatique est intégrée à l’import et au cron DATAtourisme via une file persistante et des batches Mistral (migrations 013 et 014). Le traitement actuel génère uniquement du français ; le stockage accepte plusieurs langues.

Pour traiter exactement une sortie locale, sans démarrer de serveur HTTP ni lancer l'import DATAtourisme :

```bash
mvn -f idee-service/pom.xml package
python3 scripts/rewrite_description.py datatourisme-0d8c9292-f0d7-349a-b90b-00c4987cd936
```

Le programme applique les migrations, transmet uniquement la description française source à `https://api.mistral.ai/v1/chat/completions`, puis enregistre les deux textes dans une transaction. Un schéma JSON impose les deux champs ; une réponse incomplète, un champ vide ou un résumé de plus de 300 caractères est refusé sans écriture. En mode unitaire, une erreur est renvoyée à l’appelant. La file automatique conserve les travaux en échec et les reprend avec un délai progressif. Un résultat identique (source, modèle et version du prompt) est réutilisé sans facturation supplémentaire. Le verrou sur la sortie sérialise les générations concurrentes et les mises à jour de cette fiche par l’import pendant l’appel (délai maximal HTTP de 90 secondes).

La génération automatique est active par défaut lorsque `MISTRAL_API_KEY` est configurée (`MISTRAL_AUTO_ENABLED=true`). À la validation d’un import ou d’une création par API, la description source française est comparée au texte ayant servi à produire les descriptions générées. Une sortie publiée sans réécriture, ou une source française différente, crée un travail dans `idee_outing_description_job`. Un changement de titre, de calendrier ou d’une traduction étrangère seul ne relance pas Mistral si la réécriture française est à jour.

La comparaison est différée à la fin de la transaction : le remplacement des traductions par suppression/réinsertion dans DATAtourisme ne produit pas de fausse modification. Un import annulé ne crée aucun travail visible. Les brouillons, démonstrations et fiches sans description française ne sont pas générés. La migration n’ajoute pas tous les anciens contenus à la file : une fiche existante sans réécriture sera prise en charge à sa prochaine mise à jour/importation.

Le worker automatique utilise désormais l’[API Batch Mistral](https://docs.mistral.ai/studio/batch-processing), annoncée à 50 % du prix des appels classiques. Il regroupe jusqu’à 500 demandes par batch inline (avec un budget supplémentaire de deux millions de caractères source pour les gros contenus), envoyé à `/v1/batch/jobs` pour `/v1/chat/completions`, avec `timeout_hours=24`. La génération unitaire conserve l’appel classique immédiat. Aucun repli automatique vers le tarif classique en cas d’erreur Batch.

L’API examine la file toutes les deux secondes après une minute au démarrage et consulte chaque batch distant au plus une fois par minute en fonctionnement normal. Le cron d’import soumet également les travaux prêts, puis termine sans attendre jusqu’à 24 heures les résultats distants. **Laisser l’API active avec sa clé Mistral pour collecter les résultats** : le seul cron quotidien ne garantit pas de récupérer des fichiers avant leur expiration. Les erreurs de suivi réseau sont reprises progressivement, entre une minute et une heure.

La migration 014 conserve chaque batch (`idee_description_batch`), son identifiant fournisseur, son modèle, sa version de prompt et la source de chaque sortie (`idee_description_batch_item`). Les requêtes sources préparées restent en base jusqu’à la collecte, puis le payload est vidé. Le même verrou PostgreSQL sérialise soumission et collecte entre API et cron. Aucune transaction ni aucun verrou de fiche n’est conservé pendant l’appel HTTP Batch ; les imports peuvent continuer. Chaque résultat est rapproché via `custom_id`, validé avec les mêmes règles JSON/longueur que la génération unitaire, puis enregistré sous un bref verrou de fiche, seulement si la sortie est encore publiée, hors démonstration, avec la même source française. Un texte déjà généré manuellement pour cette source est conservé.

Un changement de source libère immédiatement le nouveau travail pour un futur batch. Le résultat de l’ancienne source est écarté. Les anciens textes restent conservés mais masqués lorsqu’ils sont obsolètes ; le site affiche la source en attendant. Les demandes échouées, absentes ou invalides d’un batch terminé sont seules remises en file avec reprise de 30 secondes à une heure. Une réimportation identique conserve ce délai, une nouvelle source le réinitialise. Les succès ne sont pas resoumis. Un fichier de résultats confirmé expiré (404) remet uniquement les demandes restantes en file.

Avant tout POST, l’intention `SUBMITTING` est enregistrée. Si le POST est interrompu ou reçoit une erreur ambiguë, le worker cherche le batch dans la liste Mistral par métadonnée `idee_batch_id` (jusqu’à 10 000 batches récents). Aucun nouvel envoi payant n’est lancé à l’aveugle. Un refus HTTP explicite 4xx, sauf 408, permet de réessayer la soumission après délai. Si aucun batch n’est retrouvé, `lastError=submission_unknown` reste visible et les recherches reprennent automatiquement ; une vérification opérateur est nécessaire si l’incertitude persiste (notamment une coupure entre l’enregistrement de l’intention et l’envoi effectif). Ne remettre la soumission en état `PREPARED` qu’après avoir vérifié dans Mistral qu’aucun job n’a été accepté. Aucun secret ni contenu fournisseur n’est enregistré dans les erreurs.

Sans clé, ou avec `MISTRAL_AUTO_ENABLED=false`, les travaux restent en base. Le cron lit la configuration commune `deploy/.env` en plus de sa configuration DATAtourisme privée ; renseigner la clé Mistral dans `deploy/.env` pour la production. Le déploiement normal rafraîchit aussi l’image `idee-datatourisme:cron` à partir de la nouvelle image API pour que l’import quotidien utilise ce worker.

Suivi protégé : `GET /api/admin/descriptions/status` avec le Bearer `IDEE_IMPORT_TOKEN`. La réponse contient `enabled`, `configured`, `mode: "batch"`, `pending` (tous les travaux non terminés), `ready` (prêts à soumettre), `inBatch`, `retrying`, `nextAttemptAt`, `activeBatches`, `uncertainSubmissions` et les 20 batches les plus récents dans `batches`. Pour chaque batch : identifiant local/fournisseur, état, total, nombres effectivement enregistrés ou échoués après collecte, dates et erreur sûre. Les états `SUCCESS`, `FAILED`, `TIMEOUT_EXCEEDED`, `CANCELLED` peuvent être terminés chez Mistral avant la collecte locale ; `completedAt` indique que les résultats locaux ont été traités. Les résultats écartés comme obsolètes ne sont pas comptés comme échecs à réessayer.

Rattrapage des anciennes fiches : `POST /api/admin/descriptions/generate-missing?limit=500`, avec le même Bearer, ajoute jusqu’à 500 sorties publiées sans réécriture française à jour et sans travail déjà en file. La réponse 202 contient `accepted` (nouveaux travaux), `limit`, `language` et `statusUrl`. La génération longue/courte est ensuite assurée par le worker, sans attente HTTP. Limite facultative de 1 à 500, 500 par défaut ; fiches de démonstration et sans source française exclues, délai des échecs préservé. Un nouvel appel prend le lot suivant. Voir [l’exemple d’appel et les erreurs](api-mcp.md#génération-automatique-des-descriptions).

Après chargement de la nouvelle version de l'API, `POST /api/admin/outings/{slug}/descriptions/generate` réalise le même traitement, protégé par le Bearer `IDEE_IMPORT_TOKEN`. Réponses : 404 si sortie absente, 422 sans description française, 409 si modification concurrente, 503 sans clé et 502 en cas d'échec Mistral.

Le détail public expose `descriptions`, avec `language`, `description` (source), `description_longue` et `description_courte`. Les listes exposent aussi `description_courte` en français. L'interface affiche uniquement le français : texte court sur les vignettes et texte long en haut de la fiche, sous le titre. Le bloc de présentation inférieur et le sélecteur de langue sont supprimés. Sans réécriture, la fiche utilise la description source française ; sans français, elle signale son absence. Le champ historique `description` et les traductions `sourceDetails` restent disponibles.

Validation automatique : `python3 scripts/check_description_jobs.py` exécute les tests d’import, de génération et de file dans trois schémas PostgreSQL jetables, avec les clés fournisseurs désactivées et Mistral simulé. Les tests couvrent les nouvelles sorties, descriptions modifiées/inchangées, traductions remplacées, transactions annulées, lot de 500, résultats désordonnés/partiels/invalides, erreurs et reprises, soumissions concurrentes, redémarrage après envoi incertain et absence de verrou de fiche pendant le réseau. Des tests de protocole HTTP simulé vérifient également requêtes inline, métadonnées et lecture JSONL de fichiers résultats.

Validation : tests Java de format, longueur Unicode et protection de l'endpoint ; `OutingDescriptionsDatabaseTest` en schéma isolé ; `tests/test_descriptions.cjs` pour la vignette courte, la description longue française en haut, l'absence de doublon et l'affichage mobile. `IDEE_DESCRIPTION_FIXTURE` permet de fournir une réponse JSON issue de la base pour vérifier le frontend avant redémarrage de l'API.

## Champs structurés supplémentaires

Le JSONB `idee_datatourisme_event.payload` conserve la réponse complète, y compris les champs nouveaux ou non projetés. La migration additive 009 rend les informations courantes directement interrogeables :

| Table | Données exploitables |
|---|---|
| `idee_datatourisme_event` | Identifiants source/producteur, dates source de création et mise à jour, obsolescence, version de sélection. Les dates source restent distinctes des dates d'import et de création locale. |
| `idee_datatourisme_translation` | Titre, résumé, description et commentaire par langue. `und` désigne un texte sans langue explicite. |
| `idee_datatourisme_contact` | Coordonnées par rôle : contact, réservation, producteur, éditeur, propriétaire, administration, communication. Adresses et autres propriétés conservées dans le JSON du contact. |
| `idee_datatourisme_contact_channel` | Tous les téléphones, courriels, fax et sites web d'un contact, sans doublons exacts. |
| `idee_datatourisme_term` | Types, thèmes, portée géographique, langues disponibles et autres vocabulaires configurés, avec leurs codes et libellés multilingues. |
| `idee_datatourisme_location` | Tous les lieux/adresses : commune et code INSEE, département, adresse et coordonnées. |
| `idee_datatourisme_resource` | Tous les liens des représentations principales et secondaires, avec type, crédit, licence et données source ; les documents ne sont pas présentés comme images. |

Chaque table est rattachée au UUID source par clé étrangère ; les clés composées empêchent les doublons d'import. Des index ciblent les identifiants source, dates, contacts, thèmes et communes. Les collections d'une fiche sont remplacées dans la même transaction que sa mise à jour : une coordonnée retirée de la source ne subsiste pas en base structurée.

Le producteur et l'éditeur d'une fiche ne sont pas automatiquement assimilés à l'organisateur. Un site web est projeté sur `idee_outing.website` depuis le contact général, sans assimiler une page d'accueil à un lien de réservation. La galerie reçoit les images HTTPS des deux collections de représentations, dédupliquées par URL ; une image principale explicite passe en premier. Sans type MIME, une extension d'image connue est requise pour l'affichage ; toute ressource non reconnue reste conservée en base. Les sources ne renseignant pas une licence restent sans licence explicite : aucune autorisation n'est inventée.

Source : https://api.datatourisme.fr/v1/docs . Ce connecteur couvre les fêtes et manifestations ; les lieux permanents, produits et itinéraires ne sont pas importés.

## Vérification

La page `/sorties/{slug}` utilise les informations structurées : présentation dans les langues disponibles, thèmes et langues d'accueil, contacts par rôle avec liens téléphone/courriel/web, lieux supplémentaires, documents et provenance (producteur, éditeur, dates et référence source). Les images et leurs crédits restent dans la galerie. Les sections vides sont masquées ; les producteurs/éditeurs ne sont pas présentés comme organisateurs. Les informations de provenance sont repliables.

L'API de détail `GET /api/outings/{slug}` expose `sourceDetails` (ou `null` pour une fiche sans source DATAtourisme), avec `translations`, `contacts` et leurs `channels`, `terms`, `locations`, `resources` non-images, `reference`, `createdOn`, `updatedOn`, `updatedAt`. Le JSON brut et les checkpoints ne sont pas exposés ; l'API de liste reste inchangée.

`tests/test_source_page.cjs` vérifie la page dans Playwright : version ordinateur/mobile, changement de langue, contacts, provenance, documents, lieux multiples, valeurs dangereuses affichées sans exécution et fiches sans enrichissement. Il ne crée aucune donnée en base : les cas fictifs interceptent uniquement les réponses dans le navigateur. Variables facultatives : `IDEE_BROWSER_URL`, `IDEE_PLAYWRIGHT_MODULE`, `IDEE_CHROMIUM_PATH`.

`mvn -f idee-service/pom.xml test` vérifie les URL, les cas de dates et un calendrier de 22 000 occurrences (Python et python-dateutil requis). Le calcul du calendrier découpe les gros catalogues en lots pour conserver les limites de chaque processus Python.

`DatatourismeDatabaseTest` est une recette optionnelle qui écrit des données fictives. L'exécuter uniquement avec `IDEE_DATATOURISME_DB_TEST=isolated`, une URL JDBC contenant `?currentSchema=idee_datatourisme_test_<suffixe>` et `SPRING_LIQUIBASE_DEFAULT_SCHEMA` pointant vers ce même schéma préalablement créé. Fournir aussi le chemin absolu `IDEE_CALENDAR_SCRIPT`. Le test vérifie la pagination, le rollback d'une page partiellement traitée, la reprise, l'upsert, les images/crédits, les heures inconnues et la distinction entre mise à jour incrémentale et archivage après parcours complet. Supprimer ensuite uniquement ce schéma de recette.

## Premier batch local sans serveur HTTP

Construire le service (`mvn -f idee-service/pom.xml package`), puis soumettre un seul batch, borné à 500 sorties, en utilisant les secrets du `.env` local :

```bash
python3 scripts/batch_descriptions.py --limit 500 --wait --background
```

Le lanceur affiche le PID et le chemin d’un journal privé (0600) dans `/tmp`. Il applique les migrations nécessaires, complète la file si besoin puis soumet un seul batch. Le processus reste actif pour collecter **ce batch seulement**, sans serveur HTTP, sans import DATAtourisme et sans génération automatique d’autres lots. Il termine après collecte ou après 26 heures. Des demandes individuelles échouées restent dans la file pour une reprise ultérieure. Le marqueur `MISTRAL_BATCH_RESULT=` du journal donne les identifiants et l’état.

Pour reprendre la collecte d’un batch local après arrêt du processus, sans soumettre de nouveau lot :

```bash
python3 scripts/batch_descriptions.py --batch-id IDENTIFIANT_LOCAL_DU_BATCH --wait --background
```

Sans `--wait`, le lanceur affiche l’état et termine après un seul passage. Sans `--background`, il reste attaché au terminal. Une sauvegarde est recommandée avant le premier lancement si des migrations sont en attente.
