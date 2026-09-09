#!/usr/bin/env bash

# Runs a Gradle test task with its output redirected to a log, so that a caller
# whose context is worth protecting gets the exit status and the log path rather
# than several megabytes of Gradle output.
#
# Run from the project root:
#
#   dev/test-run.sh :test --tests 'xtdb.api_test*'
#
# Then reconcile what it wrote with dev/test-summary.sh.

# no `set -e`: a test failure exits Gradle non-zero, and the caller still needs the
# log path and the status printed below
set -uo pipefail

if [[ $# -eq 0 ]]; then
  echo "usage: $0 <gradle-args>..." >&2
  exit 2
fi

mkdir -p build/test-runs
log=build/test-runs/$(date +%Y%m%dT%H%M%S).log

# touched before Gradle starts, so that dev/test-summary.sh can tell this run's
# result files from those of the run before it
touch build/test-runs/.start

./gradlew "$@" > "$log" 2>&1
status=$?

echo "gradle exit: $status"
echo "log: $log"

exit $status
