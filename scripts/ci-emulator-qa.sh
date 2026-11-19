#!/usr/bin/env bash
# Release gate (qa-e2e.yml): only the tests against the deployed QA, on the emulator.
# Studio's QA flow needs QA_SEED_PASSWORD; without it the gate fails instead of skipping, so a release
# never passes a gate that did not run.
set -uo pipefail
source "$(dirname "$0")/qa-deployed-tests.sh"
[ -n "${QA_SEED_PASSWORD:-}" ] || { echo "::error::QA_SEED_PASSWORD is not set: the QA release gate cannot run"; exit 1; }
rc=0
for pair in "${QA_DEPLOYED[@]}"; do
  ./gradlew ":${pair%%=*}:connectedQaDebugAndroidTest" -Pandroid.testInstrumentationRunnerArguments.class="${pair#*=}" || rc=1
done
exit $rc
