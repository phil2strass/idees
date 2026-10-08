#!/usr/bin/env bash
# Deploy to OVH; replace database only when explicitly requested.
set -Eeuo pipefail

idee_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
idee_host="${IDEE_DEPLOY_HOST:-ovh}"
idee_remote_dir="${IDEE_DEPLOY_DIR:-/home/debian/idee}"
idee_url="${IDEE_DEPLOY_URL:-https://ideesdesorties.eu}"
idee_check=false
idee_doctor=false
idee_mcp=false
idee_replace_db=false
idee_temp=''
idee_remote_temp=''
idee_remote_started=false

usage() {
  cat <<'HELP'
Usage : ./deploy.sh [--check | --doctor] [--with-mcp] [--replace-db]

  --check     Préparer et vérifier l’archive localement, sans connexion au serveur.
  --doctor    Vérifier les prérequis OVH en lecture seule, sans export ni déploiement.
  --with-mcp  Mettre aussi à jour les dépendances MCP et redémarrer idee-tunnel.
  --replace-db Remplacer les données OVH par celles de la base locale (.env).
               Sauvegarde distante préalable ; interruption du site pendant la restauration.
  --help      Afficher cette aide.

Cible par défaut : ovh:/home/debian/idee
Site : https://ideesdesorties.eu
Variables facultatives : IDEE_DEPLOY_HOST, IDEE_DEPLOY_DIR, IDEE_DEPLOY_URL.

L’installation distante doit déjà exister avec les services systemd idee-api/idee-ssr, PostgreSQL et deploy/.env.
Sans --replace-db, la base distante est conservée. Les secrets ne sont jamais transférés.
--replace-db remplace le schéma applicatif public ; les images locales ne sont pas transférées.
Les migrations Liquibase restent appliquées au démarrage de l’API.
HELP
}
for idee_arg in "$@"; do
  case "$idee_arg" in
    --check) idee_check=true ;;
    --doctor) idee_doctor=true ;;
    --with-mcp) idee_mcp=true ;;
    --replace-db) idee_replace_db=true ;;
    --help|-h) usage; exit 0 ;;
    *) printf 'Option inconnue : %s\n' "$idee_arg" >&2; usage >&2; exit 2 ;;
  esac
done
if [[ "$idee_check" == true && "$idee_doctor" == true ]]; then
  echo '--check est hors ligne ; --doctor vérifie le serveur. Choisir une seule option.' >&2
  exit 2
fi
# Restrict values embedded in SSH commands to unambiguous shell-safe characters.
[[ "$idee_host" =~ ^[a-zA-Z0-9_][a-zA-Z0-9_.@-]*$ ]] || { echo 'Alias SSH invalide.' >&2; exit 2; }
[[ "$idee_remote_dir" =~ ^/[a-zA-Z0-9_./-]+$ && "$idee_remote_dir" != / && "$idee_remote_dir" != *'/../'* && "$idee_remote_dir" != */.. ]] || { echo 'IDEE_DEPLOY_DIR doit être un chemin absolu sans espaces ni ..' >&2; exit 2; }
[[ "$idee_url" =~ ^https://[a-zA-Z0-9.-]+(:[0-9]+)?/?$ ]] || { echo 'IDEE_DEPLOY_URL doit être une origine HTTPS, sans chemin.' >&2; exit 2; }
idee_url="${idee_url%/}"
for idee_command in tar python3 mktemp; do
  command -v "$idee_command" >/dev/null || { printf 'Commande requise : %s\n' "$idee_command" >&2; exit 1; }
done
if [[ "$idee_check" == false ]]; then
  for idee_command in ssh scp curl; do
    command -v "$idee_command" >/dev/null || { printf 'Commande requise : %s\n' "$idee_command" >&2; exit 1; }
  done
fi
export LC_ALL=C LANG=C
idee_ssh=(ssh -o BatchMode=yes -o ConnectTimeout=15 -o ServerAliveInterval=15 -o ServerAliveCountMax=3)
# A deployment is an update of an existing native installation. Detect an
# unconverted server before exporting local data or uploading staging files.
if [[ "$idee_check" == false ]]; then
  "${idee_ssh[@]}" "$idee_host" "env LC_ALL=C LANG=C bash -s -- '$idee_remote_dir'" < "$idee_root/deploy/preflight-native.sh"
  if [[ "$idee_doctor" == true ]]; then
    exit 0
  fi
fi
cleanup() {
  local idee_status=$?
  trap - EXIT
  if [[ -n "$idee_remote_temp" && "$idee_remote_started" == false ]]; then
    # The remote update removes its own staging files when finished. This also
    # removes an incomplete upload if scp failed before the update could start.
    "${idee_ssh[@]}" "$idee_host" "rm -rf -- '$idee_remote_temp'" >/dev/null 2>&1 || true
  fi
  [[ -z "$idee_temp" ]] || rm -rf -- "$idee_temp"
  exit "$idee_status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
umask 077
idee_temp="$(mktemp -d "${TMPDIR:-/tmp}/idee-deploy.XXXXXXXX")"
"$idee_root/deploy/package.sh" "$idee_temp/source.tar.gz"
# Never trust packaging alone to keep local credentials out of an upload.
python3 - "$idee_temp/source.tar.gz" <<'PY'
import sys, tarfile
from pathlib import PurePosixPath
with tarfile.open(sys.argv[1]) as archive:
    names = set()
    for member in archive.getmembers():
        path = PurePosixPath(member.name)
        names.add(str(path))
        if path.is_absolute() or '..' in path.parts or member.issym() or member.islnk() or not (member.isfile() or member.isdir()):
            raise SystemExit('Archive refusée : chemin ou type non autorisé.')
        if any(part in {'.env', '.venv', '.tunnel', 'backups', 'audits', 'node_modules', '.git', '.runtime', '.tools', 'data', 'logs'} or (part.startswith('.env.') and part != '.env.example') for part in path.parts):
            raise SystemExit('Archive refusée : fichier privé ou dépendance locale.')
    required = {'AGENTS.md', 'deploy.sh', 'deploy/remote-update.sh', 'deploy/build-native.sh', 'deploy/run-service.py', 'deploy/install-services.py', 'deploy/preflight-native.sh'}
    if not required <= names:
        raise SystemExit('Archive incomplète.')
print('Archive vérifiée : sources uniquement, sans secrets ni base locale.')
PY
if [[ "$idee_check" == true ]]; then
  printf 'Vérification locale réussie. Cible prévue : %s:%s\nAucune connexion ni modification distante.\n' "$idee_host" "$idee_remote_dir"
  if [[ "$idee_replace_db" == true ]]; then
    echo 'Option --replace-db vérifiée ; aucun export ni remplacement de base effectué en mode --check.'
  fi
  exit 0
fi
if [[ "$idee_replace_db" == true ]]; then
  echo 'Export de la base locale (.env) pour remplacement des données OVH…'
  python3 "$idee_root/deploy/database.py" --config "$idee_root/.env" export "$idee_temp/database.dump"
fi
idee_version="$(python3 - "$idee_temp/source.tar.gz" <<'PYVERSION'
import hashlib, sys
with open(sys.argv[1], 'rb') as archive:
    print(hashlib.file_digest(archive, 'sha256').hexdigest())
PYVERSION
)"
printf 'Déploiement vers %s:%s\n' "$idee_host" "$idee_remote_dir"
idee_remote_temp="$("${idee_ssh[@]}" "$idee_host" 'umask 077; mktemp -d /tmp/idee-deploy.XXXXXXXX')"
[[ "$idee_remote_temp" =~ ^/tmp/idee-deploy\.[a-zA-Z0-9]{8}$ ]] || { idee_remote_temp=''; echo 'Répertoire temporaire distant inattendu.' >&2; exit 1; }
scp -q -o BatchMode=yes -o ConnectTimeout=15 "$idee_temp/source.tar.gz" "$idee_root/deploy/remote-update.sh" "$idee_host:$idee_remote_temp/"
if [[ "$idee_replace_db" == true ]]; then
  scp -q -o BatchMode=yes -o ConnectTimeout=15 "$idee_temp/database.dump" "$idee_host:$idee_remote_temp/database.dump"
fi
idee_remote_started=true
"${idee_ssh[@]}" -n "$idee_host" "bash '$idee_remote_temp/remote-update.sh' '$idee_remote_dir' '$idee_remote_temp' '$idee_mcp' '$idee_version' '$idee_replace_db'"
# Verify the public route as well as the local checks performed on the server.
for idee_endpoint in / /api/health /api/categories; do
  if ! curl --fail --silent --show-error --retry 4 --retry-delay 3 --retry-all-errors --connect-timeout 10 --max-time 30 --output /dev/null "$idee_url$idee_endpoint"; then
    printf 'Les services sont démarrés, mais la vérification publique a échoué : %s%s\nVérifiez Apache/HTTPS. Aucun retour arrière automatique.\n' "$idee_url" "$idee_endpoint" >&2
    exit 1
  fi
done
# A 200 response alone can come from the previous deployment (or SPA fallback).
curl --fail --silent --show-error --retry 4 --retry-delay 3 --retry-all-errors --connect-timeout 10 --max-time 30 \
  -H 'Cache-Control: no-cache' --output "$idee_temp/public-version.json" \
  "$idee_url/assets/deploy-version.json?deployment=$idee_version"
python3 - "$idee_temp/public-version.json" "$idee_version" <<'PYVERSION'
import json, sys
try:
    with open(sys.argv[1]) as response:
        actual = json.load(response).get('version')
except (ValueError, AttributeError):
    actual = None
if actual != sys.argv[2]:
    raise SystemExit('ÉCHEC : le site public ne sert pas la version attendue. Le déploiement ne peut pas être déclaré terminé.')
print('Version publique vérifiée : ' + actual[:12])
PYVERSION
printf '\nDéploiement terminé : %s\n' "$idee_url"
