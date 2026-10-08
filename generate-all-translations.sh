#!/usr/bin/env bash
set +x
set -euo pipefail

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
echo "Mise en file de toutes les traductions manquantes sur ${idee_api_url%/}…"
# Pass the private key through stdin rather than exposing it in process arguments.
printf 'Authorization: Bearer %s\n' "$IDEE_IMPORT_TOKEN" |
  curl --silent --show-error --fail-with-body --max-time 120 \
    --request POST "${idee_api_url%/}/api/admin/translations/generate-all-missing" \
    --header @-
printf '\n'
