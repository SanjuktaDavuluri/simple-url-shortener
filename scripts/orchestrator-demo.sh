#!/usr/bin/env bash
# Orchestrator demo (#84): one complete, scripted Run through the real `orchestrate` command, graph,
# gates, policy check, git worktrees and Event Log. The agent and GitHub are the test stand-ins, so
# it needs no API key and no network, and it runs in seconds. It shows parallel Lanes and the join,
# a retried gate, a rollback, a re-plan after a human edit, release readiness and close-out.
#
# Usage: scripts/orchestrator-demo.sh [output dir]   (default: delivery/demo)
#
# The output dir gets events.jsonl (the Event Log), report.md (the close-out report) and
# timeline.txt (the main events, one per line). The scenario is tests/test_demo_run.py, so CI
# keeps it working.
set -euo pipefail

root=$(git rev-parse --show-toplevel)
out=$(cd "$root" && mkdir -p "${1:-delivery/demo}" && cd "${1:-delivery/demo}" && pwd)

cd "$root/orchestrator"
ORCHESTRATOR_DEMO_OUT="$out" uv run pytest -q -p no:cacheprovider tests/test_demo_run.py

echo
echo "Timeline (seq, event, stage, details):"
cat "$out/timeline.txt"
echo
echo "Saved: $out/events.jsonl, $out/report.md, $out/timeline.txt"
