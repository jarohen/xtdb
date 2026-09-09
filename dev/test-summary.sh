#!/usr/bin/env bash

# Reconciles the result files a Gradle test run wrote: which task directories it
# touched, the totals across every <testsuite>, and which files hold failures.
#
# Run from the project root, after a run started by dev/test-run.sh:
#
#   dev/test-summary.sh [marker]
#
# `marker` defaults to build/test-runs/.start, which dev/test-run.sh touches
# immediately before invoking Gradle. Only result files newer than the marker are
# counted, so an earlier run's XML can't be mistaken for this one's.

set -uo pipefail

marker=${1:-build/test-runs/.start}

if [[ ! -e $marker ]]; then
  echo "no marker at $marker — was the run started by dev/test-run.sh?" >&2
  exit 2
fi

mapfile -t xmls < <(
  # worktrees live under .claude/worktrees, inside the main checkout, each with its
  # own build/ — without the prune, a run from the main checkout counts every
  # sibling worktree's result files as its own
  find . -path ./.claude/worktrees -prune -o \
       -path '*/build/test-results/*' -name '*.xml' -newer "$marker" -print | sort
)

if [[ ${#xmls[@]} -eq 0 ]]; then
  echo "no result files newer than $marker — no tests ran"
  exit 1
fi

sum_attr() {
  grep -ohE "<testsuite [^>]*$1=\"[0-9]+\"" "${xmls[@]}" \
    | grep -oE "$1=\"[0-9]+\"" | grep -oE '[0-9]+' \
    | awk '{ s += $1 } END { print s + 0 }'
}

echo "result files: ${#xmls[@]}, newer than $marker"
printf '%s\n' "${xmls[@]}" | xargs -n1 dirname | sort | uniq -c \
  | awk '{ printf "  %s (%d file%s)\n", $2, $1, ($1 == 1 ? "" : "s") }'

echo "totals: tests=$(sum_attr tests) failures=$(sum_attr failures) errors=$(sum_attr errors) skipped=$(sum_attr skipped)"

mapfile -t failing < <(grep -lE '<failure|<error' "${xmls[@]}")

if [[ ${#failing[@]} -eq 0 ]]; then
  echo "files with failures: none"
  exit 0
fi

echo "files with failures: ${#failing[@]}"
printf '  %s\n' "${failing[@]}"
