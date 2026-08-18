#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_root"

ruby scripts/verify-yaml.rb
ruby scripts/verify-markdown-links.rb

legacy_matches="$(rg --line-number --ignore-case \
  '(\boci\b|openai-compatible|oca_[a-z0-9_-]+|hybrid-[a-z0-9_-]+)' \
  README.md docs council-user.example.yml src/main src/test || true)"

if [[ -n "$legacy_matches" ]]; then
  echo "Legacy provider references remain in user-facing or executable files:" >&2
  echo "$legacy_matches" >&2
  exit 1
fi

echo "Legacy provider validation passed."
echo "Repository verification passed."
