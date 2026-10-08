#!/usr/bin/env bash
# Compatibility entry point for a manual import or an existing daily scheduler.
set -euo pipefail
idee_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd -- "$idee_dir"
exec 9> .datatourisme-cron.lock
flock -n 9 || exit 0
exec python3 deploy/run-service.py import
