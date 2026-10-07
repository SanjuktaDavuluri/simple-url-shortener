#!/bin/sh
# Move an issue on the delivery board (GitHub Project "Simple URL Shortener: Delivery").
# Usage: scripts/board-status.sh <issue-number> "Todo" | "In Progress" | "In Review" | "Done"
# Needs: gh with the "project" scope (gh auth refresh -s project), jq.
set -eu

OWNER=SanjuktaDavuluri
PROJECT=1
ISSUE=$1
STATUS=$2

PROJECT_ID=$(gh project view "$PROJECT" --owner "$OWNER" --format json --jq .id)
FIELDS=$(gh project field-list "$PROJECT" --owner "$OWNER" --format json)
FIELD_ID=$(echo "$FIELDS" | jq -r '.fields[] | select(.name=="Status") | .id')
OPTION_ID=$(echo "$FIELDS" | jq -r --arg s "$STATUS" \
  '.fields[] | select(.name=="Status") | .options[] | select(.name==$s) | .id')
ITEM_ID=$(gh api graphql -f owner="$OWNER" -F project="$PROJECT" -f query='
  query($owner: String!, $project: Int!) {
    user(login: $owner) { projectV2(number: $project) {
      items(first: 100) { nodes { id content { ... on Issue { number } } } } } } }' \
  | jq -r --argjson n "$ISSUE" '.data.user.projectV2.items.nodes[] | select(.content.number==$n) | .id')

if [ -z "$OPTION_ID" ] || [ -z "$ITEM_ID" ]; then
  echo "Unknown status '$STATUS' or issue #$ISSUE is not on the board" >&2
  exit 1
fi

gh project item-edit --id "$ITEM_ID" --project-id "$PROJECT_ID" \
  --field-id "$FIELD_ID" --single-select-option-id "$OPTION_ID" > /dev/null
echo "#$ISSUE -> $STATUS"
