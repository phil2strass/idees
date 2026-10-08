#!/usr/bin/env bash
set -euo pipefail

cd -- "$(dirname -- "${BASH_SOURCE[0]}")"

git add .

if git diff --cached --quiet; then
  echo "Aucune modification à committer."
else
  git commit -m "update"
fi

git push
