#!/usr/bin/env bash
set -euo pipefail
idee_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd -- "$idee_dir"
exec 9>"$idee_dir/.datatourisme-cron.lock"
flock -n 9 || exit 0

idee_container=idee-datatourisme-cron
if docker container inspect "$idee_container" >/dev/null 2>&1; then
  echo 'Un conteneur import existe deja ; verifier son etat avant de relancer.' >&2
  exit 1
fi

echo "$(date --iso-8601=seconds) Demarrage import DATAtourisme"
# --env-file is read by Docker; credentials never appear in command arguments.
# The website's DATATOURISME_ENABLED remains false: cron owns the daily schedule.
docker run --rm --no-healthcheck --name "$idee_container" \
  --network idee_default --memory 768m \
  --env-file "$idee_dir/deploy/.env" \
  --env-file "$idee_dir/deploy/.env.datatourisme" \
  -e DATATOURISME_ENABLED=true -e DATATOURISME_BATCH=true \
  -e DB_HOST=db -e DB_PORT=5432 -e DB_NAME=idee -e DB_USER=idee \
  -e DB_CONTEXTS=production -e JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=70 \
  idee-datatourisme:cron
echo "$(date --iso-8601=seconds) Import DATAtourisme termine"
