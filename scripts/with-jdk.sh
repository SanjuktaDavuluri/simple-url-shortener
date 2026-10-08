#!/usr/bin/env bash
# Run a command with JAVA_HOME set to JDK 25, found the same way as scripts/local.sh.
# The delivery orchestrator's verify gate uses it, so `./mvnw` works without JAVA_HOME exported.
#
# Usage: scripts/with-jdk.sh <command> [args...]    e.g. scripts/with-jdk.sh ./mvnw -B -q verify
#
# Exits 127 when no JDK 25 is found: the environment can't run the command, so there is nothing for
# the code to fix.
set -euo pipefail

# shellcheck source=scripts/jdk.sh
source "$(dirname "$0")/jdk.sh"
JAVA_HOME="$(find_java_home)" || exit 127
export JAVA_HOME
exec "$@"
