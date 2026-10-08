# Aide-mémoire du projet

Les directives de référence sont dans [AGENTS.md](AGENTS.md).

Application de sorties en Alsace : Angular SSR, Java 21, PostgreSQL et calendrier Python. Déploiement natif documenté dans [deploy/README.md](deploy/README.md).

- Développement : `./start-front.sh` (lancement par l’utilisateur).
- Images : `data/images/`, fichiers locaux persistants exclus de Git ; `./download-images.sh --watch` pour le suivi.
- Imports : `./import-outings.sh --status` et `--history`.
- Mistral et traductions OpenAI : files persistantes, contenu à jour réutilisé, traductions après génération française ; titre seul traité indépendamment.
- Provenance : mention discrète en bas des fiches, producteur, DATAtourisme, licence et date de mise à jour ; textes signalés comme adaptés/traduits.
- Langues : français, anglais, allemand, italien, néerlandais, espagnol ; slugs traduits persistants et anciens chemins conservés en alias.
- Calendrier des fiches : au-delà de quatre séances, semaine en cours et calendrier des autres dates ; clic sur un jour pour ses horaires.

Ne pas confondre documentation du dépôt et état du serveur. Vérifier l’état réel avant toute intervention autorisée.
