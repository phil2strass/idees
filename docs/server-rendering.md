# Rendu serveur Angular

L’accueil et les fiches sont rendus à chaque requête par Angular SSR. Le HTML contient déjà les vignettes, le titre de la sortie, la description et les métadonnées : titre, description, URL canonique et Open Graph. Les changements provenant des imports sont lus dans l’API à la requête suivante, sans reconstruire les pages.

Le navigateur hydrate ce HTML pour activer la navigation, les filtres et les boutons. Le cache de transfert Angular réutilise les réponses publiques de l’API obtenues sur le serveur pendant cette première hydratation. Les origines interne et publique sont associées côté serveur pour éviter une seconde requête immédiate du navigateur.

## Architecture

```text
Apache HTTPS
  ├─ /api/* → Java / idee-api (127.0.0.1:8087) → PostgreSQL
  └─ pages et assets → Node / idee-ssr (127.0.0.1:4000) → API locale
```

Les services natifs `idee-api` et `idee-ssr` sont gérés par systemd et s’exécutent sous l’utilisateur applicatif. Les ports ne sont accessibles que sur l’interface locale. Le serveur Node fournit le rendu Angular et les ressources compilées.

`deploy.sh` construit une version immuable sous `.runtime/releases/`, sauvegarde les données puis bascule `.runtime/current` et redémarre les deux services. Le contrôle local exige le marqueur SSR et l’identifiant de version ; un simple shell statique ne suffit pas. PostgreSQL et Apache ne sont pas redémarrés par le déploiement ordinaire. Voir [le guide natif](../deploy/README.md).

## Configuration

- `API_ORIGIN` : adresse de l’API utilisée par Node, `http://127.0.0.1:8087` dans le déploiement natif et par défaut en local. Aucun secret n’est nécessaire pour lire le catalogue public.
- `PUBLIC_ORIGIN` : origine publique utilisée par le serveur Node pour les liens canoniques. Le lanceur natif utilise `IDEE_PUBLIC_ORIGIN`, dont la valeur par défaut est `https://ideesdesorties.eu`. Renseigner la bonne origine HTTPS dans `deploy/.env` pour un autre domaine.
- `PORT` et `HOST` : écoute du serveur Node autonome, `4000` et `127.0.0.1` par défaut.

Le serveur Node construit les URL à partir de `PUBLIC_ORIGIN`, pas des en-têtes Host ou X-Forwarded-Host transmis par un visiteur. Il ne transmet ni cookie ni autorisation utilisateur à l’API publique. Les appels HTTP sont bornés à dix secondes et les réponses HTML ne sont pas mises en cache (`Cache-Control: no-store`).

## Statuts et compatibilité

Les anciennes adresses de fiches et les chemins historiques renvoient un vrai HTTP 301 vers l’URL actuelle. Les fiches introuvables et les routes inconnues renvoient 404 avec du HTML. Une indisponibilité de l’API renvoie 503, pour éviter une page vide présentée comme une réponse réussie. Le résolveur valide toujours le chemin géographique complet.

Si l’API n’a pas encore été mise à jour, les liens `/sorties/{slug}` continuent de fonctionner ; le frontend utilise l’ancien endpoint de détail lorsque le nouveau résolveur est absent. Les URL parlantes demandent toujours la migration 012.

Apache transmet les pages au service SSR ; il n’utilise plus `/api/public-page` ni `X-Accel-Redirect`. L’ancien endpoint Java reste disponible pour compatibilité, mais ne participe plus au rendu déployé. Une installation qui sert seulement `dist/idee/browser` avec un fallback statique ne fournit pas le SSR.

## Développement et validation

`npm start` active le SSR du serveur de développement Angular, avec `PUBLIC_ORIGIN=http://localhost:4401` par défaut et le proxy API existant. Une session Angular démarrée avant cette modification doit être relancée pour prendre en compte la nouvelle configuration et les nouvelles dépendances. `start-front.sh` reste le lanceur de l’ensemble API et frontend.

Pour examiner le build autonome : `npm run build`, puis `npm run serve:ssr` dans `idee-front`. Le serveur écoute alors sur `http://localhost:4000`, sert les fichiers statiques et rend les pages ; ses appels API SSR utilisent `API_ORIGIN`. Pour les interactions navigateur qui appellent `/api/`, utiliser Apache ou le serveur Angular avec son proxy, ou prévoir un proxy équivalent devant cet aperçu autonome.

L’installation locale de `node_modules` est maintenant propre à Idées ; l’ancien lien vers Wik a été retiré sans modifier les dépendances de Wik. Le fichier de verrouillage inclut Angular SSR, platform-server et les types Node 22.

Contrôles reproductibles :

```bash
cd idee-front
npm ci
npm run build
cd ..
node tests/test_ssr.mjs
node tests/test_ssr_browser.cjs
node tests/test_public_urls.cjs
python3 -m unittest discover -s tests -p 'test_deploy.py' -v
./deploy.sh --check
```

Les tests SSR appellent directement le bundle serveur compilé avec une API simulée ; aucun serveur applicatif ni base de données n’est démarré. Ils vérifient contenu HTML, métadonnées, statuts, compatibilité et origine publique. `IDEE_SSR_BUNDLE` peut désigner une copie isolée du bundle pour vérifier l’absence de dépendance au `node_modules` du projet.

Les tests navigateur servent le HTML réellement produit par SSR via interception Playwright. Ils couvrent l’affichage sans JavaScript, la réutilisation du DOM et des réponses API pendant l’hydratation, les liens, les filtres, l’unicité de la canonique et le mobile. `IDEE_PLAYWRIGHT_MODULE` et `IDEE_CHROMIUM_PATH` permettent d’utiliser une installation existante.
