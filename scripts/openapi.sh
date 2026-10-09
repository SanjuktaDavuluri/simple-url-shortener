#!/usr/bin/env bash
# Regenerate docs/api/openapi.yaml from the code (spec 0008). Starts a temporary instance on random
# ports with a scratch database inside the integration test, so it never touches the local service.
#
# Usage: scripts/openapi.sh
set -euo pipefail
cd "$(dirname "$0")/.."
exec scripts/with-jdk.sh ./mvnw -B -q verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=OpenApiDefinitionIT -Dopenapi.write=true -Dspotless.check.skip=true
