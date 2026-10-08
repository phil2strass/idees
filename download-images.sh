#!/usr/bin/env bash
set +x
set -euo pipefail

idee_action="${1:-start}"
if [[ $# -gt 1 ]]; then
  echo 'Usage : ./download-images.sh [--status|--watch|--no-wait|--help]' >&2
  exit 1
fi
case "$idee_action" in
  --help|-h)
    echo 'Sans option : télécharger toutes les images manquantes et suivre l’avancement.'
    echo '--no-wait : mettre en file et rendre la main immédiatement.'
    echo '--status : afficher les compteurs et les erreurs, sans relancer.'
    echo '--watch : suivre la progression jusqu’à la fin du passage, sans relancer.'
    echo 'Ctrl+C arrête le suivi ; les téléchargements continuent dans l’API.'
    exit 0 ;;
  start|--status|--watch|--no-wait) ;;
  *) echo "Option inconnue : $idee_action" >&2; exit 1 ;;
esac

idee_project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
if [[ -f "$idee_project_dir/.env" ]]; then
  source "$idee_project_dir/.env"
elif [[ -f "$idee_project_dir/deploy/.env" ]]; then
  source "$idee_project_dir/deploy/.env"
fi
IDEE_IMPORT_TOKEN="${IDEE_IMPORT_TOKEN:-}"
if [[ ${#IDEE_IMPORT_TOKEN} -lt 32 ]]; then
  echo 'Renseignez IDEE_IMPORT_TOKEN (au moins 32 caractères) dans .env ou deploy/.env.' >&2
  exit 1
fi
if [[ "$IDEE_IMPORT_TOKEN" == *$'\n'* || "$IDEE_IMPORT_TOKEN" == *$'\r'* ]]; then
  echo 'IDEE_IMPORT_TOKEN ne doit pas contenir de retour à la ligne.' >&2
  exit 1
fi
command -v python3 >/dev/null || { echo 'python3 est nécessaire pour afficher le suivi.' >&2; exit 1; }
idee_api_url="${IDEE_API_BASE_URL:-http://127.0.0.1:8087}"
idee_poll_seconds="${IDEE_IMAGES_POLL_SECONDS:-5}"
if [[ ! "$idee_poll_seconds" =~ ^[0-9]+$ ]]; then
  echo 'IDEE_IMAGES_POLL_SECONDS doit être un entier positif ou nul.' >&2
  exit 1
fi
idee_request() {
  # Keep the key out of command arguments and logs.
  printf 'Authorization: Bearer %s\n' "$IDEE_IMPORT_TOKEN" |
    curl --silent --show-error --fail-with-body --connect-timeout 10 --max-time 120 \
      --request "$1" "${idee_api_url%/}$2" --header @-
}
if [[ "$idee_action" == start || "$idee_action" == --no-wait ]]; then
  echo "Mise en téléchargement des images manquantes sur ${idee_api_url%/}…"
  if ! idee_result="$(idee_request POST '/api/admin/images/download-all-missing')"; then
    printf '%s\n' "$idee_result" >&2
    echo 'Vérifiez que l’API a été reconstruite/redémarrée avec les routes images et IDEE_IMAGES_ENABLED=true.' >&2
    exit 1
  fi
  printf '%s\n' "$idee_result" | python3 -m json.tool --no-ensure-ascii
  if [[ "$idee_action" == --no-wait ]]; then
    echo 'Le traitement continue dans l’API. Suivi : ./download-images.sh --status'
    exit 0
  fi
fi
if [[ "$idee_action" != --status ]]; then
  echo "Suivi toutes les ${idee_poll_seconds} secondes. Ctrl+C arrête uniquement le suivi."
fi
trap 'echo "Suivi arrêté. Les téléchargements continuent dans l’API."; exit 130' INT TERM
while true; do
  if ! idee_payload="$(idee_request GET '/api/admin/images/status')"; then
    printf '%s\n' "$idee_payload" >&2
    echo 'Suivi interrompu : API inaccessible ou authentification refusée. Les demandes restent en base.' >&2
    exit 1
  fi
  idee_state=0
  printf '%s\n' "$idee_payload" | python3 -c '
import datetime,json,sys
p=json.load(sys.stdin)
c={r["state"]:int(r["count"]) for r in p["counts"]}
total=sum(c.values())
ready=c.get("ready",0)
progress=f"{ready}/{total} ({100*ready/total:.1f} %)" if total else "0/0 (file vide)"
print(datetime.datetime.now().strftime("%d/%m/%Y %H:%M:%S"),
      "| Progression :",progress,"| Téléchargées :",ready,"| En attente :",c.get("pending",0),
      "| En cours :",c.get("downloading",0),"| À réessayer :",c.get("retrying",0),flush=True)
if sys.argv[1]=="--status" or not (c.get("pending",0)+c.get("downloading",0)):
    for error in p.get("recentErrors",[]):
        print("Erreur :",error["source_url"],"—",error["last_error"],"; reprise :",error["next_attempt_at"])
if not p["enabled"]:
    print("Téléchargement désactivé : activer IDEE_IMAGES_ENABLED et redémarrer l’API.")
    sys.exit(3)
if sys.argv[1]=="--status": sys.exit(0)
if c.get("pending",0)+c.get("downloading",0): sys.exit(10)
if c.get("retrying",0):
    print("Passage terminé avec des erreurs. L’API réessaiera automatiquement ; relancer le script force une nouvelle tentative.")
    sys.exit(2)
print("Toutes les images en file sont téléchargées.")
' "$idee_action" || idee_state=$?
  case "$idee_state" in
    10) sleep "$idee_poll_seconds" ;;
    *) exit "$idee_state" ;;
  esac
done
