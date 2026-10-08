#!/usr/bin/env bash
# Run the shortener locally in the background, so it can be tried in a browser while other work
# goes on.
#
# Usage: scripts/local.sh start [--no-build] | stop | restart | status | logs | url
#
#   start      build the jar (unless --no-build) and start the app in the background
#   stop       stop it
#   restart    stop, then start (rebuilding)
#   status     is it running, where, and with which database
#   logs       follow the log (Ctrl-C to stop following; the app keeps running)
#   url        print the app's address
#
# Settings (environment variables, all optional):
#   PORT      HTTP port                         default 8000
#   BASE_URL  public address used in Short URLs default http://localhost:$PORT
#   DATA_DIR  where the database, log and PID live (kept between runs; gitignored)
#             default .local/
#
# Needs JDK 25: $JAVA_HOME if it points at Java 25, otherwise the one macOS knows about
# (/usr/libexec/java_home), otherwise Homebrew's openjdk@25.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PORT="${PORT:-8000}"
BASE_URL="${BASE_URL:-http://localhost:$PORT}"
DATA_DIR="${DATA_DIR:-$ROOT/.local}"
PID_FILE="$DATA_DIR/app.pid"
LOG_FILE="$DATA_DIR/app.log"
DATABASE="$DATA_DIR/links.db"

# shellcheck source=scripts/jdk.sh
source "$ROOT/scripts/jdk.sh"

running_pid() {
  if [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    cat "$PID_FILE"
  fi
}

start() {
  if [[ -n "$(running_pid)" ]]; then
    echo "Already running (PID $(running_pid)) at $BASE_URL"
    return
  fi
  if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "Port $PORT is already in use. Choose another: PORT=8001 $0 start" >&2
    exit 1
  fi

  export JAVA_HOME
  JAVA_HOME="$(find_java_home)"
  if [[ "${1:-}" != "--no-build" ]]; then
    echo "Building..."
    (cd "$ROOT" && ./mvnw -B -q -DskipTests package)
  fi
  local jar
  jar="$(ls "$ROOT"/target/simple-url-shortener-*.jar 2>/dev/null | grep -v '\.original$' | head -1)"
  if [[ -z "$jar" ]]; then
    echo "No jar in target/. Run without --no-build." >&2
    exit 1
  fi

  mkdir -p "$DATA_DIR"
  PORT="$PORT" BASE_URL="$BASE_URL" DATABASE_PATH="$DATABASE" \
    nohup "$JAVA_HOME/bin/java" -jar "$jar" >>"$LOG_FILE" 2>&1 &
  echo $! >"$PID_FILE"

  for _ in $(seq 1 60); do
    if curl -sf -o /dev/null "http://localhost:$PORT/"; then
      echo "Running (PID $(cat "$PID_FILE")) at $BASE_URL"
      echo "  database: $DATABASE"
      echo "  log:      $LOG_FILE   (scripts/local.sh logs)"
      return
    fi
    if [[ -z "$(running_pid)" ]]; then
      break
    fi
    sleep 1
  done
  echo "The app did not come up. Last log lines:" >&2
  tail -20 "$LOG_FILE" >&2
  exit 1
}

stop() {
  local pid
  pid="$(running_pid)"
  if [[ -z "$pid" ]]; then
    echo "Not running"
    rm -f "$PID_FILE"
    return
  fi
  kill "$pid"
  for _ in $(seq 1 20); do
    kill -0 "$pid" 2>/dev/null || break
    sleep 0.5
  done
  rm -f "$PID_FILE"
  echo "Stopped (PID $pid)"
}

status() {
  local pid
  pid="$(running_pid)"
  if [[ -n "$pid" ]]; then
    echo "Running (PID $pid) at $BASE_URL"
    echo "  database: $DATABASE"
    echo "  log:      $LOG_FILE"
  else
    echo "Not running"
  fi
}

case "${1:-}" in
  start) start "${2:-}" ;;
  stop) stop ;;
  restart) stop && start ;;
  status) status ;;
  logs) tail -f "$LOG_FILE" ;;
  url) echo "$BASE_URL" ;;
  *)
    sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac
