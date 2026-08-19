#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_root"

ruby scripts/verify-yaml.rb
ruby scripts/verify-markdown-links.rb

# ── Legacy provider token scan
#
# grep rather than rg: grep is present on every POSIX system, rg is not, and an
# absent search tool used to be indistinguishable from a clean tree. `\b` is the
# one word-boundary syntax BSD grep, GNU grep and ugrep all honour; `[[:<:]]` is
# BSD-only and matches nothing under GNU grep or ugrep.
legacy_pattern='(\boci\b|openai-compatible|oca_[a-z0-9_-]+|hybrid-[a-z0-9_-]+)'
legacy_paths=(README.md docs council-user.example.yml src/main src/test)

# Prints "file:line:text" for every legacy token found under the given paths.
# Exit status: 0 tokens found, 1 tree clean, >=2 the search tool itself failed.
scan_legacy_tokens() {
  grep -rIniE -e "$legacy_pattern" -- "$@"
}

# Runs scan_legacy_tokens and records both halves of its answer, because the
# three outcomes above must stay distinguishable. The `if` suspends `set -e` for
# the call so a non-zero status is data rather than a fatal; grep's own stderr
# is left flowing to ours so a "command not found" is still visible.
legacy_scan_output=""
legacy_scan_status=0
run_legacy_scan() {
  if legacy_scan_output="$(scan_legacy_tokens "$@")"; then
    legacy_scan_status=0
  else
    legacy_scan_status=$?
  fi
}

# ── Positive control
#
# An absence assertion over a detector that never fires passes for the wrong
# reason, so prove the scanner fires before trusting it to report a pass. The
# fixture carries one line per alternative in the pattern plus a line that must
# NOT match: a grep that treats `\b` as a literal reports too few lines, and one
# that drops it reports the "associated ... velocity" line.
control_dir="$(mktemp -d)"
trap 'rm -rf "$control_dir"' EXIT
printf '%s\n' \
  'provider: oci' \
  'provider: openai-compatible' \
  'credential: oca_tenancy_ocid' \
  'profileId: hybrid-balanced' \
  'note: associated social velocity must not match' \
  > "$control_dir/legacy-fixture.yml"

run_legacy_scan "$control_dir"
if [[ $legacy_scan_status -ne 0 ]]; then
  echo "Legacy scanner self-check FAILED: the scanner did not flag a fixture full of legacy tokens (grep exit ${legacy_scan_status})." >&2
  echo "The search tool is missing or the pattern is broken, so a clean result from it proves nothing." >&2
  exit 1
fi
# The boundary check goes first: dropping `\b` also changes the match count, and
# "word boundaries are not honoured" is the diagnosis, where "expected 4, got 5"
# is only the symptom.
if [[ "$legacy_scan_output" == *associated* ]]; then
  echo "Legacy scanner self-check FAILED: word boundaries are not honoured; 'associated' was reported as a match." >&2
  echo "$legacy_scan_output" >&2
  exit 1
fi
control_hits=()
while IFS= read -r control_line; do control_hits+=("$control_line"); done <<< "$legacy_scan_output"
if [[ ${#control_hits[@]} -ne 4 ]]; then
  echo "Legacy scanner self-check FAILED: expected 4 fixture matches, got ${#control_hits[@]}." >&2
  echo "$legacy_scan_output" >&2
  exit 1
fi
echo "Legacy scanner self-check passed (4 fixture tokens flagged, word boundaries honoured)."

# ── The real scan
run_legacy_scan "${legacy_paths[@]}"
case $legacy_scan_status in
  0)
    echo "Legacy provider references remain in user-facing or executable files:" >&2
    echo "$legacy_scan_output" >&2
    exit 1
    ;;
  1)
    echo "Legacy provider validation passed."
    ;;
  *)
    echo "Legacy provider scan could not run (grep exit ${legacy_scan_status}); repository state is UNVERIFIED." >&2
    exit 1
    ;;
esac

echo "Repository verification passed."
