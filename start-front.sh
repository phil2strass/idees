#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [[ -f .env ]]; then set -a; source .env; set +a; fi
export DB_HOST="${DB_HOST:-localhost}" DB_PORT="${DB_PORT:-5432}" DB_NAME="${DB_NAME:-idee}" DB_USER="${DB_USER:-htpweb}"
: "${DB_PASSWORD:?Renseignez DB_PASSWORD dans .env (voir .env.example).}"
export DB_PASSWORD
if ! command -v node >/dev/null 2>&1; then
  for idee_node in "$HOME"/.nvm/versions/node/v22.*/bin "$HOME"/.nvm/versions/node/v24.*/bin; do
    if [[ -x "$idee_node/node" ]]; then export PATH="$idee_node:$PATH"; break; fi
  done
fi
if ! PGPASSWORD="$DB_PASSWORD" psql -X -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -Atc 'SELECT 1' >/dev/null; then
  echo 'La base doit être accessible. Pour la créer en local : sudo -u postgres createdb -O htpweb idee' >&2
  exit 1
fi
python3 - <<'PORTCHECK'
import socket
for port in (8087, 4401):
    with socket.socket() as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            probe.bind(('127.0.0.1', port))
        except OSError:
            raise SystemExit(f'Le port {port} est déjà utilisé. Arrêtez l’aperçu existant avant de relancer.')
PORTCHECK
if [[ ! -d idee-front/node_modules ]]; then (cd idee-front && npm ci); fi
mvn -q -f idee-service/pom.xml package -DskipTests
idee_runtime_dir="$(mktemp -d "${TMPDIR:-/tmp}/idee-service-runtime.XXXXXXXX")"
idee_api_pid=''
idee_front_pid=''
cleanup() {
  if [[ -n "$idee_api_pid" ]]; then
    kill "$idee_api_pid" 2>/dev/null || true
    wait "$idee_api_pid" 2>/dev/null || true
  fi
  if [[ -n "$idee_front_pid" ]]; then kill -- "-$idee_front_pid" 2>/dev/null || true; fi
  rm -rf -- "$idee_runtime_dir"
}
trap cleanup EXIT INT TERM
# Spring loads some classes lazily: Maven must never replace the running JAR.
cp idee-service/target/idee-service-0.0.1-SNAPSHOT.jar "$idee_runtime_dir/service.jar"
java -jar "$idee_runtime_dir/service.jar" > /tmp/idee-service-dev.log 2>&1 &
idee_api_pid=$!
idee_ready=false
for ((idee_attempt=0; idee_attempt<60; idee_attempt++)); do
  if ! kill -0 "$idee_api_pid" 2>/dev/null; then cat /tmp/idee-service-dev.log; exit 1; fi
  if curl --fail --silent http://127.0.0.1:8087/api/calendar >/dev/null; then idee_ready=true; break; fi
  sleep 1
done
if [[ "$idee_ready" != true ]]; then echo 'Le service ne répond pas : /tmp/idee-service-dev.log' >&2; exit 1; fi
(cd idee-front && exec setsid npm start) &
idee_front_pid=$!
echo 'Idées de sorties en Alsace : http://localhost:4401'
wait -n "$idee_api_pid" "$idee_front_pid"
