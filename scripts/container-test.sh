#!/usr/bin/env bash
# Build the image and test the container on a scratch port with a scratch volume. Never touches
# port 8000 or .local/ and never pushes the image.
#
# Usage: scripts/container-test.sh      Settings: PORT (default 8765), MANAGEMENT_PORT (8766)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PORT="${PORT:-8765}"
MANAGEMENT_PORT="${MANAGEMENT_PORT:-8766}"
SUFFIX="$$"
IMAGE="shortener-container-test:$SUFFIX"
NAME="shortener-ct-$SUFFIX"
VOLUME="shortener-ct-data-$SUFFIX"
SHUTDOWN_TIMEOUT=5s
CLICK_SHUTDOWN_TIMEOUT=5s
STOP_BOUND=20 # SHUTDOWN_TIMEOUT + CLICK_SHUTDOWN_TIMEOUT plus margin
TARGET="http://localhost:9/container-test"
TMP="$(mktemp -d)"

cleanup() {
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  docker rm -f "$NAME-bad" >/dev/null 2>&1 || true
  docker volume rm "$VOLUME" >/dev/null 2>&1 || true
  docker rmi "$IMAGE" >/dev/null 2>&1 || true
  rm -rf "$TMP"
}
trap cleanup EXIT
fail() {
  echo "FAIL: $*" >&2
  docker logs "$NAME" 2>&1 | tail -20 >&2 || true
  exit 1
}

run() {
  docker run -d --name "$NAME" -v "$VOLUME:/data" -p "127.0.0.1:$PORT:8000" \
    -p "127.0.0.1:$MANAGEMENT_PORT:8081" -e BASE_URL="http://localhost:$PORT" \
    -e SHUTDOWN_TIMEOUT=$SHUTDOWN_TIMEOUT -e CLICK_SHUTDOWN_TIMEOUT=$CLICK_SHUTDOWN_TIMEOUT \
    "$IMAGE" >/dev/null
}
wait_healthy() {
  for _ in $(seq 1 90); do
    [[ "$(docker inspect -f '{{.State.Health.Status}}' "$NAME")" == healthy ]] && return 0
    sleep 2
  done
  fail "container never became healthy"
}
follow_status() { curl -s -o /dev/null -w '%{http_code} %{redirect_url}' "http://localhost:$PORT/$1"; }

echo "== build"
docker build -t "$IMAGE" "$ROOT"

echo "== start, healthy"
run
wait_healthy

echo "== non-root"
uid="$(docker exec "$NAME" id -u)"
[[ "$uid" != 0 ]] || fail "container runs as root"

echo "== create and follow a Link"
body="$(curl -s -X POST "http://localhost:$PORT/links" -H 'Content-Type: application/json' \
  -d "{\"url\":\"$TARGET\"}")"
code="$(printf '%s' "$body" | sed -E 's/.*"short_code" *: *"([A-Za-z0-9]{7})".*/\1/')"
[[ ${#code} -eq 7 ]] || fail "no short code in response: $body"
[[ "$(follow_status "$code")" == "302 $TARGET" ]] || fail "link not followed"

echo "== restart on the same volume"
docker stop "$NAME" >/dev/null
docker rm "$NAME" >/dev/null
run
wait_healthy
[[ "$(follow_status "$code")" == "302 $TARGET" ]] || fail "link lost after restart"

echo "== graceful stop with queued Clicks"
for _ in $(seq 1 20); do follow_status "$code" >/dev/null; done
start=$SECONDS
docker stop -t 60 "$NAME" >/dev/null
elapsed=$((SECONDS - start))
exit_code="$(docker inspect -f '{{.State.ExitCode}}' "$NAME")"
[[ "$exit_code" == 0 ]] || fail "docker stop exit code $exit_code, expected 0"
((elapsed <= STOP_BOUND)) || fail "stop took ${elapsed}s, bound ${STOP_BOUND}s"
docker cp "$NAME:/data/links.db" "$TMP/links.db"
docker cp "$NAME:/data/links.db-wal" "$TMP/links.db-wal" 2>/dev/null || true
clicks="$(sqlite3 "$TMP/links.db" 'select count(*) from clicks')"
((clicks >= 20)) || fail "only $clicks Clicks persisted, expected at least 20"

echo "== invalid BASE_URL"
set +e
out="$(docker run --name "$NAME-bad" -e BASE_URL=not-a-url "$IMAGE" 2>&1)"
rc=$?
set -e
((rc != 0)) || fail "invalid BASE_URL exited 0"
grep -q BASE_URL <<<"$out" || fail "error message does not name BASE_URL"

echo "PASS: container test (stop took ${elapsed}s, $clicks Clicks persisted)"
