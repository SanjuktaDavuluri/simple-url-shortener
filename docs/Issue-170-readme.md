# Ticket #170: Orchestrator bugs found in Runs R-0003 and R-0004

## What users and operators now see or do

- **Amendments are checked first:** an agent's spec amendment must be the whole spec (front matter and every heading kept) and must change something. A placeholder or truncated spec goes back to the agent with the problems, and no approval is requested.
- **The approval shows the diff:** the `amendment-N` request on the Issue includes the unified diff from the approved spec and a one-line count of lines added and removed.
- **Status-only spec edits don't re-plan:** the spec's lineage hash ignores the `status:` front-matter line, so marking a spec `implemented` at close-out no longer invalidates delivered work. Any change to the body still re-plans.
- **Merged PRs leave "Approvals waiting":** a merge approval is recorded as given when a Re-plan makes the Run notice the PR already merged, and `status` hides merge approvals for merged PRs in older logs.
- **Fewer policy false positives:** only URLs a command contacts (`curl`, `wget`, package managers, interpreters, `git clone/fetch/pull/push/remote`) are checked against the host allow-list, not URLs in commit messages, `echo`, `grep`, or files written by a heredoc. Newline-separated commands are now checked one by one. A write outside the workspace is refused with a hint to use a path inside it.
- **Merged results are tested:** already covered by #76 (a PR takes in main and re-runs its checks before the merge approval); no change.
