#!/usr/bin/env bash
# Uploaded and executed as a file over SSH by ../deploy.sh. Do not run with a production .env locally.
set -Eeuo pipefail
umask 077
(( $# == 4 )) || { echo "Quatre arguments requis : repertoire, staging, MCP et version. Aucun import de base accepte." >&2; exit 2; }
idee_dir="${1:?Répertoire de production requis}"
idee_stage="${2:?Répertoire temporaire requis}"
idee_mcp="${3:-false}"
idee_version="${4:?Identifiant de version requis}"
[[ "$idee_version" =~ ^[a-f0-9]{64}$ ]] || { echo 'Identifiant de version invalide.' >&2; exit 1; }
idee_backup=''
idee_phase='vérification des prérequis'
[[ "$idee_stage" =~ ^/tmp/idee-deploy\.[a-zA-Z0-9]{8}$ ]] || { echo 'Répertoire temporaire invalide.' >&2; exit 1; }
finish() {
  local idee_status=$?
  trap - EXIT
  if (( idee_status != 0 )); then
    printf '\nÉchec pendant : %s.\n' "$idee_phase" >&2
    [[ -z "$idee_backup" ]] || printf 'Sauvegardes conservées : %s\n' "$idee_backup" >&2
    echo 'Aucune restauration automatique : une migration de base peut avoir été appliquée.' >&2
  fi
  rm -rf -- "$idee_stage"
  exit "$idee_status"
}
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
for idee_command in docker rsync flock tar curl python3; do
  command -v "$idee_command" >/dev/null || { printf 'Commande requise sur le serveur : %s\n' "$idee_command" >&2; exit 1; }
done
[[ -d "$idee_dir" && -f "$idee_dir/compose.yaml" && -s "$idee_dir/deploy/.env" ]] || { echo 'Installation existante et deploy/.env requis ; aucun secret ne sera créé ou remplacé.' >&2; exit 1; }
cd -- "$idee_dir"
exec 9> .deploy.lock
flock -n 9 || { echo 'Un déploiement est déjà en cours.' >&2; exit 1; }
idee_docker=(docker)
if ! docker info >/dev/null 2>&1; then
  command -v sudo >/dev/null && sudo -n docker info >/dev/null 2>&1 || { echo 'Accès Docker requis (direct ou sudo sans mot de passe).' >&2; exit 1; }
  idee_docker=(sudo -n docker)
fi
"${idee_docker[@]}" compose version >/dev/null
"${idee_docker[@]}" compose up --help | grep -- '--wait-timeout' >/dev/null || { echo 'Docker Compose avec --wait-timeout est requis.' >&2; exit 1; }
idee_compose=("${idee_docker[@]}" compose --project-name idee --project-directory "$idee_dir" --env-file "$idee_dir/deploy/.env" -f "$idee_dir/compose.yaml")
# Refuse to deploy over a stopped or missing database: do not initialize an
# accidental empty installation or mistake it for the production database.
"${idee_compose[@]}" exec -T --interactive=false db pg_isready -U idee -d idee >/dev/null
if [[ "$idee_mcp" == true ]]; then
  [[ -x "$idee_dir/idee-mcp/.venv/bin/python" ]] || { echo 'Environnement MCP distant absent.' >&2; exit 1; }
  sudo -n -l /usr/bin/systemctl restart idee-tunnel.service >/dev/null 2>&1 || { echo 'Le redémarrage MCP exige sudo sans mot de passe pour systemctl restart idee-tunnel.service.' >&2; exit 1; }
  systemctl is-active --quiet idee-tunnel.service || { echo 'Le tunnel doit déjà être actif pour --with-mcp.' >&2; exit 1; }
fi
mkdir "$idee_stage/source"
tar -xzf "$idee_stage/source.tar.gz" -C "$idee_stage/source" --no-same-owner --same-permissions
mkdir -p "$idee_stage/source/idee-front/src/assets"
printf '{"version":"%s"}\n' "$idee_version" > "$idee_stage/source/idee-front/src/assets/deploy-version.json"
chmod 644 "$idee_stage/source/idee-front/src/assets/deploy-version.json"
idee_phase='construction des images (le site actuel reste actif)'
printf '\nConstruction du frontend et de l’API…\n'
"${idee_docker[@]}" compose --project-name idee --project-directory "$idee_stage/source" --env-file "$idee_dir/deploy/.env" -f "$idee_stage/source/compose.yaml" build api ssr web
idee_phase='vérification du moteur de calendrier dans la nouvelle image'
# Exercise the runtime user and Python imports before touching production.
printf '{"from":"2026-01-01","until":"2026-01-02","schedules":[]}\n' | \
  "${idee_docker[@]}" run --rm -i --network none --entrypoint python3 idee-api /app/scripts/expand_calendar_json.py > "$idee_stage/calendar-check.json"
python3 - "$idee_stage/calendar-check.json" <<'PYCALENDAR'
import json, sys
with open(sys.argv[1]) as result:
    if json.load(result) != []:
        raise SystemExit('Le contrôle du moteur de calendrier a échoué.')
PYCALENDAR
idee_phase='sauvegarde des sources et de PostgreSQL' 
mkdir -p backups
chmod 700 backups
idee_backup="$(mktemp -d "$idee_dir/backups/deploy-$(date -u +%Y%m%dT%H%M%SZ)-XXXXXX")"
# Snapshot sources without secrets; environment and tunnel credentials stay in place.
bash deploy/package.sh "$idee_backup/sources.tar.gz"
"${idee_compose[@]}" exec -T --interactive=false db pg_dump -U idee -d idee -Fc > "$idee_backup/database.dump.partial"
[[ -s "$idee_backup/database.dump.partial" ]]
"${idee_compose[@]}" exec -T db pg_restore --list < "$idee_backup/database.dump.partial" > /dev/null
mv "$idee_backup/database.dump.partial" "$idee_backup/database.dump"
"${idee_compose[@]}" images -q > "$idee_backup/image-ids.txt"
printf 'Sauvegardes : %s\n' "$idee_backup"
idee_phase='mise à jour des sources'
# Delete obsolete source files only inside the managed project directories.
# Protect server-owned credentials, virtualenvs, caches and runtime data.
idee_excludes=(--exclude=.env --exclude='.env.*' --exclude=.venv --exclude=.tunnel --exclude=backups --exclude=audits --exclude=node_modules --exclude=target --exclude=dist --exclude=.angular --exclude=__pycache__ --exclude='*.log')
for idee_part in idee-front idee-service idee-mcp scripts tests docs deploy; do
  rsync -rltc --delete --include=.env.example "${idee_excludes[@]}" "$idee_stage/source/$idee_part/" "$idee_dir/$idee_part/"
done
for idee_file in compose.yaml .dockerignore .gitignore .env.example README.md requirements.txt start-front.sh deploy.sh; do
  cp -- "$idee_stage/source/$idee_file" "$idee_dir/$idee_file"
done
chmod +x deploy.sh start-front.sh deploy/package.sh deploy/remote-update.sh
idee_phase='démarrage de l’API et du frontend, migrations Liquibase'
# Recreate SSR and web too: Nginx must resolve the new service addresses. Never stop
# PostgreSQL, remove volumes, or modify the shared Apache/TLS configuration.
"${idee_compose[@]}" up -d --no-deps --force-recreate --wait --wait-timeout 180 api ssr web
idee_phase='contrôle HTTP local'
idee_address="$("${idee_compose[@]}" port web 80)"
[[ "$idee_address" =~ ^127\.0\.0\.1:[0-9]+$ ]] || { echo 'Le frontend doit écouter uniquement sur 127.0.0.1.' >&2; exit 1; }
for idee_endpoint in / /api/health /api/categories; do
  curl --fail --silent --show-error --retry 5 --retry-delay 2 --retry-all-errors --connect-timeout 5 --max-time 15 --output /dev/null "http://$idee_address$idee_endpoint"
done
# Check the actual document, not merely a healthy Node process or a static SPA shell.
curl --fail --silent --show-error --connect-timeout 5 --max-time 15 \
  --output "$idee_stage/home-ssr.html" "http://$idee_address/"
python3 - "$idee_stage/home-ssr.html" <<'PYSSR'
from html.parser import HTMLParser
from pathlib import Path
import sys
class SSR(HTMLParser):
    found = False
    def handle_starttag(self, tag, attrs):
        if tag == 'idee-root' and 'ssr' in dict(attrs).get('ng-server-context', '').split('|'):
            self.found = True
page = SSR()
page.feed(Path(sys.argv[1]).read_text())
if not page.found:
    raise SystemExit('Le frontend ne fournit pas le rendu serveur attendu.')
PYSSR
curl --fail --silent --show-error --connect-timeout 5 --max-time 15 \
  --output "$idee_stage/local-version.json" "http://$idee_address/assets/deploy-version.json"
python3 - "$idee_stage/local-version.json" "$idee_version" <<'PYVERSION'
import json, sys
with open(sys.argv[1]) as response:
    if json.load(response).get('version') != sys.argv[2]:
        raise SystemExit('Le frontend local ne sert pas la nouvelle version.')
PYVERSION
idee_phase='mise à jour de l’image du prochain import quotidien'
"${idee_docker[@]}" image tag idee-api idee-datatourisme:cron
if [[ "$idee_mcp" == true ]]; then
  idee_phase='mise à jour du MCP et redémarrage du tunnel'
  "$idee_dir/idee-mcp/.venv/bin/python" -m pip install -r "$idee_dir/idee-mcp/requirements.txt"
  sudo -n /usr/bin/systemctl restart idee-tunnel.service
  systemctl is-active --quiet idee-tunnel.service
fi
printf '\nFrontend et API opérationnels. Secrets conservés.\n'
