#!/usr/bin/env bash
# Compile the current source tree into a NEW immutable runtime directory.
set -euo pipefail
umask 022
idee_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
idee_output="${1:?Usage : build-native.sh /chemin/vers/nouvelle-version}"
[[ "$idee_output" == /* && ! -e "$idee_output" ]] || { echo 'La destination doit être un nouveau chemin absolu.' >&2; exit 1; }
cd -- "$idee_root"
for idee_command in java mvn node npm python3; do
  command -v "$idee_command" >/dev/null || { echo "Commande requise : $idee_command" >&2; exit 1; }
done
mvn -q -f idee-service/pom.xml package -DskipTests
(cd idee-front && npm ci && npm run build)
mkdir -p "$idee_output/scripts"
cp idee-service/target/idee-service-0.0.1-SNAPSHOT.jar "$idee_output/service.jar"
cp -R idee-front/dist/idee "$idee_output/frontend"
cp scripts/project_calendar.py scripts/expand_calendar_json.py "$idee_output/scripts/"
printf 'Version construite : %s\n' "$idee_output"
