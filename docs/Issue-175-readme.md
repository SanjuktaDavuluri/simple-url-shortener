# Ticket #175: Documentation for Spec 0007 (Containerization)

Operators and developers can now build and run the shortener as a Docker container. This ticket documents that capability across runbook, onboarding, architecture and roadmap.

## What operators see

- **Build:** `docker build -t shortener .` produces a multi-stage image with JRE 25 and only the jar, no build artifacts.
- **Run:** `docker compose up` (or a plain `docker run`) starts the container on a configurable port with `/data` for the SQLite database. Environment variables pass through with runbook defaults.
- **Health:** The container includes a `HEALTHCHECK` that polls Readiness on the management port; operators see `healthy` when the database is reachable.
- **Graceful stop:** `docker stop` sends `SIGTERM` to the Java process (PID 1), triggering the shutdown sequence (Readiness → `OUT_OF_SERVICE`, in-flight requests complete, Clicks saved, pool closed, exit 0).
- **Security:** The process runs as non-root user `shortener` (UID 10001); the `/data` volume is owned by that user and is the only writable location needed.
- **Persistence:** A `/data` volume survives container restarts; reusing it on a new instance finds the existing SQLite database and schema intact.
- **Configuration:** Every setting is an environment variable (`BASE_URL`, `PORT`, `MANAGEMENT_TIMEOUT`, `SHUTDOWN_TIMEOUT`, Click settings, log format), with documented defaults from the runbook. Misconfigured containers exit non-zero on startup with a message naming the variable.
- **Observability:** Logs are JSON on stdout; Liveness, Readiness, and Prometheus metrics are on the management port (default `8081`, published to loopback only by Compose).

## What developers see

- **Local run:** `PORT=8765 docker compose up` with environment overrides starts a scratch instance.
- **Testing:** `scripts/container-test.sh` builds the image and runs an automated smoke test (health, create Link, redirect, restart persistence, non-root assertion, graceful stop with code 0 and duration bounded).
- **CI:** Every pull request runs the container job without renaming the three required checks; the job is separate and non-required initially.

## Documents updated

- `docs/runbook.md`: Container section with build, run, health and stop instructions; configuration reference marks container-applicable settings.
- `docs/onboarding.md`: Container build and run steps; local smoke-test run (`scripts/container-test.sh`); CI container job overview.
- `docs/architecture.md`: Mermaid diagrams for creation and redirect flows updated to show container deployment (informational).
- `docs/roadmap.md`: R11 (`#171`) now shows spec 0007 dockerize as in-progress; R12 expected for follow-up (readiness feedback, metrics, structured logs in container).
- `docs/specs/README.md`: Spec 0007 added to the index.
- `docs/plans/0001-integration-testing.md`: Phase P5 (Container smoke) with matrix rows for #172 (Dockerfile, non-root, health, graceful stop, smoke test), #173 (Compose, volume, drift test), #174 (CI job); P5 status moved to in-progress.
