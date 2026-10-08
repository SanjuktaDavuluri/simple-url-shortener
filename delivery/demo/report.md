# Run R-0001: #42 Expiring links

Started 2026-10-08T14:39:22.236+00:00 · finished 2026-10-08T14:39:25.980+00:00 · 94 events
· verify with `orchestrate verify R-0001`

## Summary

| Agent calls | Cost | Failed gates (retried or paused) |
|---|---|---|
| 11 | $4.70 | 4 |

## Timeline

| Time | Stage | |
|---|---|---|
| 2026-10-08T14:39:22.246+00:00 | intake | started |
| 2026-10-08T14:39:22.246+00:00 | intake | passed |
| 2026-10-08T14:39:22.247+00:00 | requirements | started |
| 2026-10-08T14:39:22.460+00:00 | requirements | passed |
| 2026-10-08T14:39:22.486+00:00 | design | started |
| 2026-10-08T14:39:22.554+00:00 | design | passed |
| 2026-10-08T14:39:22.590+00:00 | decompose | started |
| 2026-10-08T14:39:22.757+00:00 | decompose | passed |
| 2026-10-08T14:39:22.902+00:00 | lanes | started |
| 2026-10-08T14:39:23.199+00:00 | implement | started |
| 2026-10-08T14:39:23.249+00:00 | implement | started |
| 2026-10-08T14:39:23.651+00:00 | implement | passed |
| 2026-10-08T14:39:23.651+00:00 | document | started |
| 2026-10-08T14:39:23.791+00:00 | implement | failed |
| 2026-10-08T14:39:23.808+00:00 | document | passed |
| 2026-10-08T14:39:24.642+00:00 | decompose | passed |
| 2026-10-08T14:39:24.793+00:00 | lanes | started |
| 2026-10-08T14:39:25.180+00:00 | pr | passed |
| 2026-10-08T14:39:25.227+00:00 | implement | started |
| 2026-10-08T14:39:25.369+00:00 | implement | passed |
| 2026-10-08T14:39:25.369+00:00 | document | started |
| 2026-10-08T14:39:25.451+00:00 | document | passed |
| 2026-10-08T14:39:25.815+00:00 | pr | passed |
| 2026-10-08T14:39:25.817+00:00 | lanes | passed |
| 2026-10-08T14:39:25.873+00:00 | release_readiness | started |
| 2026-10-08T14:39:25.874+00:00 | release_readiness | passed |
| 2026-10-08T14:39:25.932+00:00 | close_out | started |
| 2026-10-08T14:39:25.980+00:00 | close_out | passed |

## Exit Gates

| Stage | Passed | Failed |
|---|---|---|
| intake | 1 | 0 |
| requirements | 1 | 0 |
| design | 1 | 0 |
| decompose | 2 | 0 |
| implement | 2 | 4 |
| pr | 2 | 0 |
| release_readiness | 1 | 0 |

## Approvals

| Checkpoint | Decision | By | Channel | Reason |
|---|---|---|---|---|
| spec | approved | @maintainer | cli |  |
| tickets | approved | @maintainer | cli |  |
| merge:103 | approved | @maintainer | github |  |
| tickets | approved | @maintainer | cli |  |
| merge:104 | approved | @maintainer | github |  |
| merge:105 | approved | @maintainer | github |  |

## Pull requests

| Lane | PR |
|---|---|
| docs | #103 |
| T1 | #104 |
| T3 | #105 |

## Rolled back and skipped Lanes

| Lane | Ticket | What happened |
|---|---|---|
| T2 | #101 | Rolled back: Out of scope for this Run; rethink the page. |
