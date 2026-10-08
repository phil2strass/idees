# API et MCP

Le public consulte librement le site. Les écritures passent uniquement par une clé d’import (`IDEE_IMPORT_TOKEN`, au moins 32 caractères aléatoires). Aucun compte utilisateur n’est ajouté.

## API HTTP

| Méthode | Route | Accès |
|---|---|---|
| GET | `/api/health` | Santé du service et de sa connexion DB |
| GET | `/api/categories` | Catégories disponibles, dont les slugs à utiliser |
| GET | `/api/catalog?period=today&limit=40&offset=0` | Catalogue filtré avant pagination : `items`, `total`, `hasMore` |
| GET | `/api/outings?date=2027-06-12` | Sorties publiées qui chevauchent cette journée en Europe/Paris |
| GET | `/api/outings/{slug}` | Fiche publiée et prochaines occurrences calculées |
| POST | `/api/admin/outings` | Création atomique avec `Authorization: Bearer …` |

Les listes `/api/outings` sont paginées avec `limit` (1–200, défaut 100) et `offset` (défaut 0). Incrémenter offset jusqu’à obtenir moins de limit éléments.

Le site utilise `/api/catalog` avec `limit` entre 1 et 40 (défaut 40), `offset`, `department`, `city`, `category`, `query` et `freeOnly`. Tous les critères sont appliqués à l’ensemble des sorties publiées avant pagination. La réponse contient `items`, le nombre total de résultats `total` et `hasMore`. L’ordre par identifiant est stable ; le défilement ajoute chaque lot aux cartes déjà affichées. Changer un filtre repart à zéro.

`period` vaut `today` par défaut, `weekend` (samedi et dimanche de la semaine courante), `week` (lundi à dimanche), `next-week`, `month`, `permanent`, ou une chaîne vide pour toutes les dates. Les bornes de dates utilisent Europe/Paris, fin exclusive ; récurrences et exceptions sont calculées sur toute la période et les séances annulées sont exclues. Sans calendrier confirmé sur la période, une sortie reste accessible dans « Toutes les dates » ou « Sans date imposée ». Le troisième bouton du site affiche « Cette semaine » du lundi au vendredi et « La semaine prochaine » le samedi et le dimanche.

Le paramètre `date` inclut les événements déjà commencés et non terminés. Les récurrences et exceptions sont calculées à la demande, même en dehors du cache des prochains mois. Les annulations sont exclues sauf `includeCancelled=true`. Une date invalide donne HTTP 400.

`includePermanent=true` ajoute les idées sans calendrier ; `availability=to_confirm` signifie que leur ouverture ce jour-là n’est pas confirmée. Les horaires saisonniers des sorties permanentes ne sont pas encore une promesse « ouvert ce jour ».

Sans `date`, la liste sert le catalogue avec son cache de prochaines occurrences. Les nouvelles fiches alimentent immédiatement ce cache, sans commande manuelle ni attente d’un batch. Le cache global est renouvelé au démarrage du service et chaque nuit à 03 h 15 (Europe/Paris).

Le bouton Calendrier ouvre un popup Angular Material et utilise `period=date&date=YYYY-MM-DD` pour une journée précise (Europe/Paris). La date reste appliquée aux pages suivantes et se combine avec tous les autres filtres. Une date manquante pour cette période, invalide, ou hors 1900–2200 renvoie HTTP 400. Les boutons de période permettent de quitter la date précise.

## Création

Le contrat est illustré dans [outing.example.json](outing.example.json). Cet exemple est un **gabarit à compléter**, jamais importé automatiquement. `status=draft` empêche l’apparition sur le site public ; `published` publie immédiatement. Cette première API crée uniquement, elle ne fournit pas encore la modification des brouillons.

Obligatoires : `sourceName`, `externalId`, `sourceUrl`, `slug`, `title`, `summary`, `description`, `place` et au moins une entrée de `categorySlugs`. Un événement doit posséder au moins un calendrier ; une sortie permanente peut n’en avoir aucun. Un tarif absent reste inconnu, jamais gratuit par défaut.

Les images sont transmises par `images` (0 à 12). Chaque image exige une URL HTTPS directe et un texte alternatif ; crédit et licence sont facultatifs. Dès qu’une image est fournie, exactement une porte `primary=true`. Elle apparaît en tête et est recadrée au centre dans un cadre fixe de 180 px (`object-fit: cover`) ; les autres forment la galerie. Le service conserve les URL et métadonnées, pas une copie du fichier : utiliser une URL HTTPS stable et publique. Le crédit et la licence restent des métadonnées facultatives et ne bloquent pas l’import.

- Source publique HTTP(S), sans récupération automatique d’une page par le serveur. Le producteur de l’import reste responsable de vérifier la source et les horaires.
- `sourceName` + `externalId` constituent l’identité d’import. Même identité et même JSON (ordre des propriétés ignoré) → HTTP 200, `created=false`. Identité existante et contenu différent → HTTP 409, sans modification. Nouveau → HTTP 201, `created=true`.
- Un slug déjà utilisé provoque aussi HTTP 409. Le titre seul ne permet pas de détecter un doublon entre deux sources : consulter les sorties du jour avant d’importer.
- Les calendriers acceptent une `rrule` iCalendar jour/semaine/mois/année, un lieu optionnel et des `exceptions`.
- Exceptions : `originalStartLocal`, `action` (`exclude`, `cancel`, `override`, `include`) ; les deux dernières requièrent `startsLocal` et `endsLocal`, avec un lieu et une note facultatifs.
- Les heures sont locales en `Europe/Paris`, sans offset ; les heures inexistantes au changement d’heure sont rejetées à la saisie. Les journées entières utilisent `allDay=true`, de minuit à minuit, fin exclusive.
- Une erreur annule aussi les insertions de lieux, catégories de la fiche, tarifs et calendriers. HTTP 400 = données invalides ; 401 = clé absente/erronée ; 409 = conflit ; 422 = calendrier trop complexe ; 503 = import/calcul non configuré ou indisponible.

Exemple depuis un shell où la clé privée est déjà chargée dans l’environnement :

```bash
curl --fail-with-body "https://idees.cavousdit.com/api/outings?date=2027-06-12"
curl --fail-with-body -X POST "https://idees.cavousdit.com/api/admin/outings" \
  -H "Authorization: Bearer $IDEE_IMPORT_TOKEN" \
  -H 'Content-Type: application/json' \
  --data-binary @ma-sortie-verifiee.json
```

## Serveur MCP

`idee-mcp/server.py` utilise le [SDK Python MCP officiel, branche 1.x](https://py.sdk.modelcontextprotocol.io/v1/) verrouillé en version 1.30.0. Il est un pont **stdio**, lancé par le client MCP sur votre machine, et appelle l’API HTTPS du serveur dédié. Il n’expose pas de port MCP public et n’a aucun accès PostgreSQL.

Outils :

- `list_categories()` : catégories et slugs.
- `list_outings(date, include_permanent=false, include_cancelled=false, limit=100, offset=0)` : lecture d’un jour.
- `create_outing(outing)` : ajout sourcé, éventuellement publié, avec le même contrôle de doublons que l’API.
- `add_outing_images(slug, images)` : ajoute jusqu’à 12 images à une fiche existante ; le premier ajout doit désigner l’unique image principale.
- `add_primary_image_by_url(slug, image_url, alt, credit, license)` : ajoute simplement la couverture depuis une URL HTTPS publique, sans téléversement de fichier.

Installation locale du pont :

```bash
python3 -m venv idee-mcp/.venv
idee-mcp/.venv/bin/pip install -r idee-mcp/requirements.txt
```

Configuration générique à adapter au client MCP :

```json
{
  "mcpServers": {
    "idee-alsace": {
      "command": "/CHEMIN/idees/idee-mcp/.venv/bin/python",
      "args": ["/CHEMIN/idees/idee-mcp/server.py"],
      "env": {
        "IDEE_API_URL": "https://idees.cavousdit.com",
        "IDEE_IMPORT_TOKEN": "CLE_PRIVEE_DU_SERVEUR"
      }
    }
  }
}
```

La clé reste dans la configuration privée du client, jamais dans le frontend ou un dépôt. Sans clé, les outils de lecture restent utilisables. HTTPS est obligatoire hors localhost ; le pont ne suit pas les redirections et vérifie le certificat TLS.

Les clients qui acceptent uniquement une URL MCP distante nécessiteront un transport HTTP MCP authentifié supplémentaire ; cette livraison fournit stdio → API HTTPS.

## Instance déployée

API : `https://idees.cavousdit.com`. Le projet se trouve sur `ssh ovh`, dans `/home/debian/idee`. La clé d’import effective est celle de `/home/debian/idee/deploy/.env` sur le serveur ; la clé de développement locale est différente. Le site public ne nécessite pas de connexion.


## Tunnel privé OpenAI sur OVH

Installé le 23 septembre 2026 : `tunnel-client` v0.0.14 (archive Linux amd64
vérifiée avec SHA256SUMS officiel), dans `/home/debian/idee/.tunnel/bin`.
Environnement Python MCP installé ; handshake, découverte des trois outils et
lectures catégories/sorties vérifiés sans créer de données.

Unité systemd `idee-tunnel.service` installée, non activée tant que les identifiants
OpenAI ne sont pas fournis. Aucun tunnel associé au compte n'a encore été vérifié.
Le service utilise `debian`, des restrictions systemd et une interface de santé
liée uniquement à 127.0.0.1 sur un port automatique. Aucun port entrant ajouté.

Créer un tunnel dans https://platform.openai.com/settings/organization/tunnels,
l'associer à l'espace ChatGPT, puis créer une clé API runtime dont le titulaire
possède Tunnels Read + Use. Ne pas utiliser une clé administrateur.

Saisir les identifiants dans un terminal local :

```bash
ssh -t ovh 'python3 /home/debian/idee/deploy/activate-tunnel.py'
```

Le script demande l'identifiant puis la clé en saisie masquée, protège la clé en
0600, crée le profil et active le service au démarrage. Il contrôle `/readyz`.
La clé n'est ni passée dans les arguments, ni dans l'historique, ni dans le chat.
Le MCP charge uniquement sa clé d'import depuis `deploy/.env` et appelle l'API HTTPS.
Les secrets du tunnel restent dans `.tunnel/`, ignoré Git et hors archive de sources.

Diagnostic : `sudo systemctl status idee-tunnel.service` ; journaux à consulter
sur place avec `sudo journalctl -u idee-tunnel.service -n 50`.

Documentation : https://developers.openai.com/api/docs/guides/secure-mcp-tunnels

## Génération automatique des descriptions

Les créations publiées déclenchent automatiquement un travail Mistral après validation de la transaction, comme les imports DATAtourisme. Le worker nécessite `MISTRAL_API_KEY` et `MISTRAL_AUTO_ENABLED=true`. Les textes français inchangés et déjà générés sont réutilisés. `GET /api/admin/descriptions/status` donne l’état de la file et exige le Bearer d’import ; l’endpoint unitaire `POST /api/admin/outings/{slug}/descriptions/generate` reste disponible. Voir [les règles de comparaison et de reprise](datatourisme.md).

Pour rattraper jusqu’à 500 sorties sans descriptions longue et courte françaises à jour :

```bash
curl --fail-with-body -X POST \
  'https://idees.cavousdit.com/api/admin/descriptions/generate-missing?limit=500' \
  -H "Authorization: Bearer ${IDEE_IMPORT_TOKEN}"
```

Utiliser la clé d’import de l’instance ciblée. `limit` est facultatif (500 par défaut), compris entre 1 et 500. Réponse immédiate **202 Accepted**, par exemple :

```json
{"accepted":500,"limit":500,"language":"fr","statusUrl":"/api/admin/descriptions/status"}
```

`accepted` compte les nouveaux travaux ajoutés, pas les générations terminées. Le worker les regroupe en batches Mistral de 500 maximum et récupère les résultats en arrière-plan, avec reprise des échecs uniquement. Le mode Batch bénéficie de la réduction annoncée de 50 % ; le délai fournisseur peut aller jusqu’à 24 heures ([documentation Mistral](https://docs.mistral.ai/studio/batch-processing)). La sélection prend les sorties publiées par identifiant croissant, ayant une source française, hors démonstrations. Les fiches déjà en file sont ignorées sans réinitialiser leur délai de reprise ; les textes générés correspondant à la source actuelle sont conservés. Les textes devenus obsolètes ou générés uniquement dans une autre langue sont éligibles. La table impose les deux textes ensemble, donc une réécriture française à jour contient toujours la description longue et la courte.

Rappeler la route ajoute le lot suivant ; une réponse `accepted: 0` signifie qu’aucune nouvelle fiche éligible disponible n’a été ajoutée, même si des travaux sont encore en attente. Les fiches verrouillées par une modification en cours sont ignorées pour cet appel. Le suivi est global à la file :

```bash
curl --fail-with-body \
  'https://idees.cavousdit.com/api/admin/descriptions/status' \
  -H "Authorization: Bearer ${IDEE_IMPORT_TOKEN}"
```

Erreurs : 401 sans Bearer valide, 400 pour une limite invalide, 409 si une autre demande de lot est en cours, 503 si la clé Mistral manque ou si `MISTRAL_AUTO_ENABLED=false`. Aucun appel fournisseur n’a lieu dans la requête HTTP d’ajout du lot.

Le suivi expose désormais `mode: "batch"`, `inBatch`, `activeBatches`, `uncertainSubmissions` et `batches` (20 derniers batches avec identifiants, états, résultats locaux et erreurs sûres). La collecte reprend après redémarrage. Un résultat est enregistré uniquement si sa source française correspond toujours à celle de la fiche. L’API doit rester active avec sa clé pour collecter régulièrement les résultats. Les soumissions incertaines sont rapprochées avec Mistral avant tout nouvel envoi ; voir [les règles de reprise et d’exploitation](datatourisme.md#descriptions-réécrites-avec-mistral). L’endpoint unitaire conserve une génération classique immédiate.

## Traductions du titre et des descriptions avec OpenAI Batch

Les traductions portent sur **le titre, `description_longue` et `description_courte`**, depuis les textes français générés et à jour. Langues : anglais (`en`), allemand (`de`), italien (`it`), néerlandais (`nl`) et espagnol (`es`). Les sorties sans réécriture française à jour, brouillons et démonstrations sont exclues.

Configurer `OPENAI_API_KEY` dans le `.env` privé, jamais dans le frontend. Modèle par défaut : `gpt-4.1-mini-2025-04-14`, configurable via `OPENAI_TRANSLATION_MODEL`. Il prend en charge Batch et les réponses JSON structurées ([modèle](https://developers.openai.com/api/docs/models/gpt-4.1-mini)). `OPENAI_TRANSLATIONS_ENABLED=false` désactive soumission et collecte. Compose transmet ces variables à l’API ; les secrets du développement ne sont pas transférés en production.

**Aucune traduction ne démarre sur simple configuration de la clé, au démarrage ou à la migration.** La migration 015 crée les tables vides, sans rattrapage automatique ni trigger de mise en file. Le lancement exige un appel explicite avec le Bearer d’import. Depuis le terminal où `IDEE_IMPORT_TOKEN` est chargé, après démarrage de la version mise à jour :

```bash
curl --max-time 30 --fail-with-body -X POST 'http://127.0.0.1:8087/api/admin/translations/generate-missing?limit=500' -H "Authorization: Bearer ${IDEE_IMPORT_TOKEN}"
```

Le paramètre facultatif `limit` compte les **sorties**, de 1 à 500 (défaut 500). Réponse 202 immédiate, par exemple :

```json
{"accepted":500,"requests":2500,"limit":500,"languages":["en","de","it","nl","es"],"statusUrl":"/api/admin/translations/status"}
```

`accepted` est le nombre de sorties avec au moins une nouvelle langue mise en file ; `requests` compte les nouvelles demandes par langue (au maximum cinq par sortie). Les traductions déjà à jour et les langues déjà en attente sont ignorées. Rappeler cette route ajoute le lot suivant ; `accepted: 0` signifie qu’aucune demande supplémentaire n’a été ajoutée, pas nécessairement que la file est terminée.

Suivi privé, sans lancement :

```bash
curl --max-time 30 --fail-with-body 'http://127.0.0.1:8087/api/admin/translations/status' -H "Authorization: Bearer ${IDEE_IMPORT_TOKEN}"
```

La réponse contient `provider: "openai"`, `mode: "batch"`, `enabled`, `configured`, les compteurs de demandes `pending`, `ready`, `inBatch`, `retrying`, `activeBatches` et les 20 derniers batches (`id`, `providerId`, état fournisseur, total, réussites/échecs après collecte, dates et erreur sûre). Erreurs de lancement : 400 pour une limite invalide, 401 sans Bearer valide, 409 pour une demande concurrente, 503 si clé absente ou traitement désactivé.

Le worker soumet les demandes explicitement mises en file : jusqu’à 2 500 demandes par batch et un budget de deux millions de caractères source. Chaque requête traduit une langue pour une sortie, avec les trois champs, en JSON strict. La description courte reste limitée à 300 caractères Unicode, espaces compris. Le modèle conserve les faits, noms de lieux/marques et incertitudes, sans ajout d’informations.

Le protocole utilise un fichier JSONL envoyé à `/v1/files` avec `purpose=batch`, puis `/v1/batches` pour `/v1/chat/completions`, fenêtre `24h`. Les résultats sont rapprochés par `custom_id` (`identifiant-sortie:langue`), indépendamment de leur ordre. OpenAI annonce une réduction de 50 % par rapport aux requêtes synchrones et un délai de 24 heures ([OpenAI Docs — Batch API](https://developers.openai.com/api/docs/guides/batch)). Laisser l’API active pour soumettre et collecter les demandes lancées. Aucun appel synchrone de secours.

La source française est conservée sous forme d’empreinte et de snapshot. Si le titre, le long ou le court français change, les traductions précédentes sont masquées immédiatement. Les résultats obsolètes d’un batch en cours sont écartés et les demandes encore en file sont rapprochées de la nouvelle source. Une modification française seule ne lance pas de traduction : refaire l’appel de rattrapage. Le français et les traductions importées originales sont conservés.

La file, le fichier d’entrée, les identifiants distants et les résultats restent persistants après redémarrage. Les demandes échouées/invalides/absentes sont seules réessayées (30 secondes à une heure) ; les erreurs de suivi distant sont reprises entre une minute et une heure. Un POST de soumission incertain est rapproché par métadonnée `idee_translation_batch` dans les 10 000 batches récents, sans nouvel envoi payant aveugle. Si aucun batch distant n’est retrouvé, `lastError: "submission_unknown"` reste visible et nécessite une vérification opérateur avant remise manuelle en état `PREPARED`. Les erreurs ne stockent ni clé ni corps de réponse fournisseur.

Le détail public `GET /api/outings/{slug}` fournit un tableau `translations` contenant uniquement les traductions à jour, avec `language`, `title`, `description_longue` et `description_courte`. Ces données préparent les futures pages multilingues ; cette modification n’active pas de nouvelles routes linguistiques ni de sélecteur de langue sur le site.

Validation sans appel réel : `python3 scripts/check_description_jobs.py` utilise quatre schémas jetables, clés fournisseurs vides et clients simulés. Les tests couvrent lancement explicite, cinq langues, titre, fraîcheur de la source, limites, reprises partielles, redémarrages, concurrence et authentification.
