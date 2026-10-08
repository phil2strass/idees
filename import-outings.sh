#!/usr/bin/env bash
set +x
set -euo pipefail

idee_action="${1:-start}"
if [[ $# -gt 1 ]]; then
  echo 'Usage : ./import-outings.sh [--status|--history|--watch|--help]' >&2
  exit 1
fi
case "$idee_action" in
  --help|-h)
    echo 'Sans option : demander un import.'
    echo '--status : consulter l’import, Mistral et les traductions.'
    echo '--history : consulter les 10 derniers imports et leurs compteurs.'
    echo '--watch : afficher le suivi toutes les 10 secondes (Ctrl+C pour arrêter).'
    exit 0 ;;
  start|--status|--history|--watch) ;;
  *) echo "Option inconnue : $idee_action" >&2; exit 1 ;;
esac

idee_project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
if [[ -f "$idee_project_dir/.env" ]]; then
  source "$idee_project_dir/.env"
fi

if [[ -z "${IDEE_IMPORT_TOKEN:-}" ]]; then
  echo 'Renseignez IDEE_IMPORT_TOKEN dans le fichier .env à la racine du projet.' >&2
  exit 1
fi
if [[ "$IDEE_IMPORT_TOKEN" == *$'\n'* || "$IDEE_IMPORT_TOKEN" == *$'\r'* ]]; then
  echo 'IDEE_IMPORT_TOKEN ne doit pas contenir de retour à la ligne.' >&2
  exit 1
fi

idee_api_url="${IDEE_API_BASE_URL:-http://127.0.0.1:8087}"

idee_request() {
  # Keep the private key out of process arguments.
  printf 'Authorization: Bearer %s\n' "$IDEE_IMPORT_TOKEN" |
    curl --silent --show-error --fail-with-body --max-time 30 \
      --request "$1" "${idee_api_url%/}$2" --header @-
}

idee_show() {
  local idee_payload
  echo "$1"
  if ! idee_payload="$(idee_request GET "$2")"; then
    printf '%s\n' "$idee_payload" >&2
    return 1
  fi
  if command -v python3 >/dev/null 2>&1; then
    printf '%s\n' "$idee_payload" | python3 -m json.tool --no-ensure-ascii
  else
    printf '%s\n' "$idee_payload"
  fi
}

idee_status() {
  date '+Suivi au %d/%m/%Y à %H:%M:%S %Z'
  # Try every provider even if one status route fails.
  local idee_failed=0
  idee_show 'Import DATAtourisme' '/api/admin/datatourisme/status' || idee_failed=1
  idee_show 'Descriptions Mistral' '/api/admin/descriptions/status' || idee_failed=1
  idee_show 'Traductions OpenAI' '/api/admin/translations/status' || idee_failed=1
  return "$idee_failed"
}

case "$idee_action" in
  --status) idee_status; exit $? ;;
  --history) idee_show 'Historique des imports' '/api/admin/datatourisme/imports?limit=10'; exit $? ;;
  --watch)
    trap 'exit 0' INT TERM
    echo 'Suivi toutes les 10 secondes. Ctrl+C pour arrêter.'
    while true; do
      idee_status || true
      sleep 10
    done ;;
esac

echo "Demande d’import des sorties des départements 67 et 68 sur ${idee_api_url%/}…"
idee_request POST '/api/admin/datatourisme/sync'
printf '\n'
echo 'Demande acceptée. L’import et les traitements Mistral/traductions se poursuivent en arrière-plan avec les fournisseurs activés.'
echo 'Suivi : ./import-outings.sh --status ; suivi continu : ./import-outings.sh --watch'
echo 'Historique et compteurs : ./import-outings.sh --history'
