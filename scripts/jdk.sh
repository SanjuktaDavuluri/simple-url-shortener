#!/usr/bin/env bash
# Find JDK 25, for the scripts that build or run the service. Sourced, not run.
#
# find_java_home prints the first of these that is Java 25: $JAVA_HOME, the one macOS knows about
# (/usr/libexec/java_home), Homebrew's openjdk@25. Without one it explains and returns 1.

find_java_home() {
  local candidate
  for candidate in \
    "${JAVA_HOME:-}" \
    "$(/usr/libexec/java_home -v 25 2>/dev/null || true)" \
    "$(brew --prefix openjdk@25 2>/dev/null || true)/libexec/openjdk.jdk/Contents/Home"; do
    if [[ -n "$candidate" && -x "$candidate/bin/java" ]] &&
      "$candidate/bin/java" -version 2>&1 | grep -q 'version "25'; then
      echo "$candidate"
      return
    fi
  done
  echo "JDK 25 not found. Install one (e.g. brew install openjdk@25) or set JAVA_HOME." >&2
  return 1
}
