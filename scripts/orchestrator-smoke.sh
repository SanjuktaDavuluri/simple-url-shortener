#!/usr/bin/env bash
# Live smoke run of the delivery orchestrator (spec 0002, story 57): one real Run against the real
# Claude API (Claude Agent SDK) and GitHub (`gh`), on a small throwaway Issue. It proves the real
# adapters end to end. Run it on demand, never in CI: it spends API credit and creates a real
# Issue, a ticket, branches and pull requests.
#
# Usage: scripts/orchestrator-smoke.sh start | next | status
#
#   start   check the prerequisites, open the throwaway Issue and start a Run for it
#   next    resume the Run (after you answered, approved or merged), then show what it waits for
#   status  show the Run
#
# The Run stops at every human checkpoint, as any Run does: answer its questions on the Issue,
# approve with `orchestrate approve <run> <checkpoint>`, merge its PRs on GitHub, then `next`.
# The evidence is kept: close-out commits delivery/runs/R-NNNN/report.md and events.jsonl in its
# own PR, and regenerates delivery/metrics.md.
#
# Needs: gh logged in (`gh auth login`), uv, and ANTHROPIC_API_KEY in the environment (or a Claude
# login). The key is read from the environment by the Agent SDK and never written anywhere.
# Set SMOKE_YES=1 to skip the confirmation.
set -euo pipefail

ROOT=$(git rev-parse --show-toplevel)
STATE="$ROOT/.orchestrator/smoke"
cd "$ROOT"

orchestrate() { uv run --quiet --project "$ROOT/orchestrator" orchestrate "$@"; }

die() { echo "orchestrator-smoke: $*" >&2; exit 1; }

run_id() {
  [ -f "$STATE/run" ] || die "no smoke run yet; start one with: $0 start"
  cat "$STATE/run"
}

start() {
  command -v gh >/dev/null || die "the GitHub CLI (gh) is required"
  command -v uv >/dev/null || die "uv is required (https://docs.astral.sh/uv/)"
  gh auth status >/dev/null 2>&1 || die "log in to GitHub first: gh auth login"
  if [ -z "${ANTHROPIC_API_KEY:-}" ]; then
    echo "ANTHROPIC_API_KEY is not set: the agent will use your Claude login, if you have one."
  fi
  [ ! -f "$STATE/run" ] || die "a smoke run already exists ($(cat "$STATE/run")); use: $0 next"
  if [ "${SMOKE_YES:-}" != "1" ]; then
    read -r -p "This spends Claude API credit and creates a real Issue and PRs. Continue? [y/N] " ok
    [ "$ok" = "y" ] || [ "$ok" = "Y" ] || die "cancelled"
  fi
  uv sync --quiet --project "$ROOT/orchestrator"
  url=$(gh issue create \
    --title "Smoke run: say in the onboarding guide how the orchestrator is proven live (R18)" \
    --body "A throwaway Issue for the orchestrator's live smoke run (\`scripts/orchestrator-smoke.sh\`, roadmap R18).

Add one sentence to the delivery orchestrator section of \`docs/onboarding.md\`: the real adapters are proven by an on-demand live smoke run, \`scripts/orchestrator-smoke.sh\`, whose report is kept under \`delivery/runs/\`. No code changes.")
  issue=${url##*/}
  mkdir -p "$STATE"
  echo "$issue" > "$STATE/issue"
  out=$(orchestrate start "$issue")
  echo "$out"
  echo "$out" | head -1 | cut -d' ' -f1 > "$STATE/run"
  echo
  echo "Smoke run $(cat "$STATE/run") started for $url. After each checkpoint: $0 next"
}

case "${1:-}" in
  start) start ;;
  next) orchestrate resume "$(run_id)" ;;
  status) orchestrate status "$(run_id)" ;;
  *) sed -n '2,20p' "$0"; exit 2 ;;
esac
