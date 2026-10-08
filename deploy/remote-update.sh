#!/usr/bin/env bash
# Native update over SSH. Existing PostgreSQL and application data are never replaced.
set -Eeuo pipefail
umask 077
(( $# == 4 )) || { echo 'Quatre arguments requis : repertoire, staging, MCP et version. Aucun import de base accepte.' >&2; exit 2; }
idee_dir="$1"
idee_stage="$2"
idee_mcp="$3"
idee_version="$4"
[[ "$idee_dir" =~ ^/[a-zA-Z0-9_./-]+$ && "$idee_dir" != / && "$idee_dir" != *'/../'* && "$idee_dir" != */.. ]] || exit 2
[[ "$idee_stage" =~ ^/tmp/idee-deploy\.[a-zA-Z0-9]{8}$ && "$idee_version" =~ ^[a-f0-9]{64}$ ]] || exit 2
[[ "$idee_mcp" == true || "$idee_mcp" == false ]] || exit 2
idee_phase='vérification des prérequis'
idee_backup=''
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
for idee_command in java mvn node npm rsync flock tar curl python3 psql pg_dump pg_restore systemctl sudo; do
  command -v "$idee_command" >/dev/null || { echo "Commande requise : $idee_command" >&2; exit 1; }
done
[[ -s "$idee_dir/deploy/.env" && -f "$idee_dir/.runtime/current/service.jar" && -x "$idee_dir/.venv/bin/python" ]] || {
  echo 'Installation native existante requise : deploy/.env, .venv et .runtime/current. Voir deploy/README.md.' >&2; exit 1;
}
cd -- "$idee_dir"
exec 9> .deploy.lock
flock -n 9 || { echo 'Un déploiement est déjà en cours.' >&2; exit 1; }
python3 deploy/database.py check
"$idee_dir/.venv/bin/python" -c 'import dateutil'
for idee_service in idee-api.service idee-ssr.service; do
  systemctl is-active --quiet "$idee_service" || { echo "Service natif inactif : $idee_service" >&2; exit 1; }
done
# Verify privileges before any change to sources or the current release.
sudo -n -l /usr/bin/systemctl restart idee-api.service idee-ssr.service >/dev/null
if [[ "$idee_mcp" == true ]]; then
  [[ -x "$idee_dir/idee-mcp/.venv/bin/python" ]] || exit 1
  sudo -n -l /usr/bin/systemctl restart idee-tunnel.service >/dev/null
fi
mkdir "$idee_stage/source"
tar -xzf "$idee_stage/source.tar.gz" -C "$idee_stage/source" --no-same-owner --same-permissions
mkdir -p "$idee_stage/source/idee-front/src/assets"
printf '{"version":"%s"}\n' "$idee_version" > "$idee_stage/source/idee-front/src/assets/deploy-version.json"
idee_phase='construction native (le site actuel reste actif)'
idee_release="$idee_dir/.runtime/releases/$(date -u +%Y%m%dT%H%M%SZ)-${idee_version:0:12}"
bash "$idee_stage/source/deploy/build-native.sh" "$idee_release"
printf '{"from":"2026-01-01","until":"2026-01-02","schedules":[]}\n' | \
  "$idee_dir/.venv/bin/python" "$idee_release/scripts/expand_calendar_json.py" > "$idee_stage/calendar-check.json"
python3 - "$idee_stage/calendar-check.json" <<'PY'
import json,sys
assert json.load(open(sys.argv[1])) == [], 'Moteur de calendrier invalide'
PY
idee_phase='sauvegarde des sources et de PostgreSQL'
mkdir -p backups
chmod 700 backups
idee_backup="$(mktemp -d "$idee_dir/backups/deploy-$(date -u +%Y%m%dT%H%M%SZ)-XXXXXX")"
bash deploy/package.sh "$idee_backup/sources.tar.gz"
python3 deploy/database.py backup "$idee_backup/database.dump"
readlink -f .runtime/current > "$idee_backup/previous-release.txt"
# Images are runtime data: preserve and back them up separately from source archives.
if [[ -d data/images ]]; then
  tar -czf "$idee_backup/images.tar.gz" -C "$idee_dir" data/images
fi
printf 'Sauvegardes : %s\n' "$idee_backup"
idee_phase='mise à jour des sources'
idee_excludes=(--exclude=.env --exclude='.env.*' --exclude=.venv --exclude=.tunnel --exclude=.runtime --exclude=data --exclude=logs --exclude=backups --exclude=audits --exclude=node_modules --exclude=target --exclude=dist --exclude=.angular --exclude=__pycache__ --exclude='*.log')
for idee_part in idee-front idee-service idee-mcp scripts tests docs deploy; do
  rsync -rltc --delete --include=.env.example "${idee_excludes[@]}" "$idee_stage/source/$idee_part/" "$idee_dir/$idee_part/"
done
for idee_file in .gitignore .env.example README.md AGENTS.md CODEX.md requirements.txt start-front.sh deploy.sh download-images.sh import-outings.sh generate-all-translations.sh; do
  cp -- "$idee_stage/source/$idee_file" "$idee_dir/$idee_file"
done
chmod +x deploy.sh start-front.sh download-images.sh import-outings.sh generate-all-translations.sh deploy/*.sh
mkdir -p data/images logs
# Refresh only these application's units; installation does not start them.
python3 deploy/install-services.py
idee_phase='bascule vers la nouvelle version, migrations Liquibase'
ln -s "$idee_release" .runtime/current.next
mv -Tf .runtime/current.next .runtime/current
sudo -n /usr/bin/systemctl restart idee-api.service idee-ssr.service
idee_phase='contrôle HTTP local'
curl --fail --silent --show-error --retry 30 --retry-delay 2 --retry-all-errors --max-time 5 --output /dev/null http://127.0.0.1:8087/api/health
curl --fail --silent --show-error --retry 10 --retry-delay 2 --retry-all-errors --max-time 5 --output /dev/null http://127.0.0.1:4000/_health
curl --fail --silent --show-error --max-time 30 --output "$idee_stage/home-ssr.html" http://127.0.0.1:4000/
python3 - "$idee_stage/home-ssr.html" <<'PY'
from html.parser import HTMLParser
from pathlib import Path
import sys
class SSR(HTMLParser):
    found=False
    def handle_starttag(self,tag,attrs):
        if tag=='idee-root' and 'ssr' in dict(attrs).get('ng-server-context','').split('|'): self.found=True
page=SSR();page.feed(Path(sys.argv[1]).read_text())
if not page.found: raise SystemExit('Le frontend ne fournit pas le rendu serveur attendu.')
PY
curl --fail --silent --show-error --max-time 15 --output "$idee_stage/local-version.json" http://127.0.0.1:4000/assets/deploy-version.json
python3 - "$idee_stage/local-version.json" "$idee_version" <<'PY'
import json,sys
if json.load(open(sys.argv[1])).get('version')!=sys.argv[2]: raise SystemExit('Version locale inattendue.')
PY
if [[ "$idee_mcp" == true ]]; then
  idee_phase='mise à jour du MCP et redémarrage du tunnel'
  "$idee_dir/idee-mcp/.venv/bin/python" -m pip install -r "$idee_dir/idee-mcp/requirements.txt"
  sudo -n /usr/bin/systemctl restart idee-tunnel.service
  systemctl is-active --quiet idee-tunnel.service
fi
printf '\nFrontend et API opérationnels. Secrets et données conservés.\n'
