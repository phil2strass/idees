#!/usr/bin/env bash
# Read-only checks, executed over SSH before packaging/exporting any database.
set -euo pipefail
export LC_ALL=C LANG=C
idee_dir="${1:?Usage : preflight-native.sh /chemin/du/projet}"
export PATH="$idee_dir/.tools/bin:$PATH"
idee_missing=false

missing() {
  printf 'Manquant ou incompatible : %s\n' "$1" >&2
  idee_missing=true
}

[[ -s "$idee_dir/deploy/.env" ]] || missing 'configuration privée deploy/.env'
[[ -x "$idee_dir/.venv/bin/python" ]] || missing '.venv/bin/python (environnement Python natif)'
[[ -f "$idee_dir/.runtime/current/service.jar" ]] || missing '.runtime/current/service.jar (première version native)'
for idee_command in java mvn node npm python3 psql pg_dump pg_restore rsync flock tar curl systemctl sudo; do
  command -v "$idee_command" >/dev/null || missing "commande $idee_command"
done
if command -v node >/dev/null && command -v python3 >/dev/null; then
  idee_node="$(node --version)"
  if ! python3 - "$idee_node" <<'PY'
import re, sys
match = re.fullmatch(r'v?(\d+)\.(\d+)\.(\d+)', sys.argv[1])
version = tuple(map(int, match.groups())) if match else (0, 0, 0)
supported = (version[0] == 22 and version >= (22, 22, 3)
             or version[0] == 24 and version >= (24, 15, 0) or version[0] >= 26)
sys.exit(0 if supported else 1)
PY
  then
    missing "Node $idee_node ; requis : 22.22.3+, 24.15+ ou 26+ selon package.json"
  fi
fi
for idee_command in psql pg_dump pg_restore; do
  if command -v "$idee_command" >/dev/null; then
    idee_pg_version="$("$idee_command" --version)"
    if [[ ! "$idee_pg_version" =~ PostgreSQL\)\ (1[6-9]|[2-9][0-9])\. ]]; then
      missing "$idee_pg_version ; PostgreSQL 16 minimum requis pour cette installation"
    fi
  fi
done
if command -v systemctl >/dev/null; then
  for idee_service in idee-api.service idee-ssr.service; do
    systemctl is-active --quiet "$idee_service" || missing "service natif actif $idee_service"
  done
fi

if [[ "$idee_missing" == true ]]; then
  if [[ -f "$idee_dir/compose.yaml" ]]; then
    echo 'Ancien déploiement détecté : compose.yaml est encore présent sur le serveur.' >&2
  fi
  cat >&2 <<'HELP'
Le serveur n'est pas prêt pour une mise à jour native.
Effectuer d'abord la migration initiale décrite dans deploy/README.md :
installer les versions requises, sauvegarder la production, préparer PostgreSQL
natif et la configuration, construire une première version, installer les services
et basculer uniquement le VirtualHost du projet en préservant les certificats.
Ne pas démarrer un ancien cluster PostgreSQL ni modifier les autres applications.
Aucun fichier, service ou contenu de base n'a été modifié par cette vérification.
HELP
  exit 1
fi
echo 'Prérequis natifs OVH vérifiés (sans modification distante).'
