# Issue #122: Readiness in scripts/local.sh and CI

## What operators see and do

- **`scripts/local.sh start`** now waits for the service to be ready (HTTP 200 on `/actuator/health/readiness`) instead of just waiting for the port to answer. This ensures the database is reachable and the Click queue is healthy before reporting "Running".

- **Management port** (`MANAGEMENT_PORT`, default `PORT + 81`): the service publishes health checks and metrics on a separate port. On a scratch run with `PORT=8765`, the management port is automatically `8846`. The script checks that both ports are free before starting.

- **`scripts/local.sh status`** now shows readiness status: "Readiness: UP" or "Readiness: not ready (HTTP ...)" with the management port URL. This lets operators quickly verify the instance is healthy.

- **`scripts/local.sh stop`** now waits for the graceful shutdown to finish, honoring `SHUTDOWN_TIMEOUT` and `CLICK_SHUTDOWN_TIMEOUT` (each defaulting to 10 seconds). The PID file is removed only after the process exits. If the process is still running after the timeout, the script says so and keeps the PID file.

- **CI's browser-check job** now polls `http://localhost:8081/actuator/health/readiness` instead of the public port. The job name stays the same, so required status checks on `main` are unaffected. This aligns with the operability principle: readiness is the right signal for "is this instance ready for traffic?"

- **Plain-text logs** on a scratch run: set `LOG_FORMAT=text` before starting. The default is JSON-formatted logs (`LOG_FORMAT=json`).
