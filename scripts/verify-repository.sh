#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_root"

ruby scripts/verify-yaml.rb
ruby scripts/verify-markdown-links.rb

# ── Legacy provider-identifier scan
#
# OCI can legitimately be documented as an OpenAI-format endpoint. What must
# not return are the removed provider identifiers: user configuration supports
# `provider: openai`, not `provider: oci` or `provider: openai-compatible`.
# grep rather than rg: grep is present on every POSIX system, and an absent
# search tool used to be indistinguishable from a clean tree.
legacy_pattern='(provider:[[:space:]]*(oci|openai-compatible)([[:space:]]|$)|oca_[a-z0-9_-]+|profileId:[[:space:]]*hybrid-[a-z0-9_-]+)'
legacy_paths=(README.md docs council-user*.yml src/main src/test)

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
# fixture carries one line per alternative in the pattern plus ordinary OCI
# documentation that must not match.
control_dir="$(mktemp -d)"
trap 'rm -rf "$control_dir"' EXIT
printf '%s\n' \
  'provider: oci' \
  'provider: openai-compatible' \
  'credential: oca_tenancy_ocid' \
  'profileId: hybrid-balanced' \
  'note: OCI is allowed in documentation when it is not a provider key' \
  > "$control_dir/legacy-fixture.yml"

run_legacy_scan "$control_dir"
if [[ $legacy_scan_status -ne 0 ]]; then
  echo "Legacy scanner self-check FAILED: the scanner did not flag a fixture full of legacy tokens (grep exit ${legacy_scan_status})." >&2
  echo "The search tool is missing or the pattern is broken, so a clean result from it proves nothing." >&2
  exit 1
fi
# Plain OCI documentation is allowed; only deprecated configuration tokens are
# forbidden.
if [[ "$legacy_scan_output" == *"OCI is allowed"* ]]; then
  echo "Legacy scanner self-check FAILED: ordinary OCI documentation was reported as a legacy provider identifier." >&2
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
echo "Legacy scanner self-check passed (4 deprecated identifiers flagged, ordinary OCI documentation allowed)."

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
