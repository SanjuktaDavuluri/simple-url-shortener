# Ticket #172: Multi-stage Dockerfile with non-root user, /data volume, Readiness health check, and container smoke script

## What users and operators now see or do

- **Reviewers and operators** can build the service image with one `docker build` command without needing Java or Maven installed locally:
  ```bash
  docker build -t shortener .
  docker run -v shortener-data:/data -p 8000:8000 -p 127.0.0.1:8081:8081 shortener
  ```

- **The built image** is minimal: it contains only a JRE 25 and the application jar, with no JDK, Maven cache, build artifacts, or source code.

- **The service process** runs as an unprivileged user (`shortener`, UID 10001) instead of root, reducing the blast radius of any vulnerability.

- **The SQLite database** (`links.db` and its WAL files) lives on a named volume (`/data`), survering container replacement and independent from the container's lifecycle.

- **Container health checks** poll the Readiness endpoint on the management port (`/actuator/health/readiness`, port 8081 by default) over a plain TCP/IP socket—no extra tooling needed—and mark the container as `healthy` only when the database is reachable and the Click queue is not saturated.

- **Graceful shutdown** is guaranteed: `docker stop` sends `SIGTERM` to the Java process (PID 1 in the container), triggering graceful shutdown (Readiness `OUT_OF_SERVICE`, in-flight requests finish, queued Clicks are flushed, connection pool closes), and the container exits with code 0 within a configured bound (by default, `SHUTDOWN_TIMEOUT` + `CLICK_SHUTDOWN_TIMEOUT` plus margin).

- **Configuration** is passed through environment variables with the same defaults as the runbook: `BASE_URL`, `PORT`, `MANAGEMENT_PORT`, `SHUTDOWN_TIMEOUT`, `CLICK_SHUTDOWN_TIMEOUT`, and others, so one image runs anywhere without rebuilding.

- **Misconfigured containers** exit non-zero on startup with an error message naming the bad variable (e.g., a relative or invalid `BASE_URL`), failing deployments loudly.

- **CI builds and smoke-tests the image** on every pull request, using `scripts/container-test.sh`, which proves that the image becomes healthy, serves Links, persists data across container restart, runs as non-root, and stops gracefully within the documented bound while saving queued Clicks.

## Files changed

- `Dockerfile`: multi-stage image (build → runtime), non-root user, `/data` volume, Readiness health check
- `.dockerignore`: excludes local state, git, build artifacts, orchestrator, delivery
- `scripts/container-test.sh`: smoke test that builds the image and exercises it
- `src/main/java/.../SimpleUrlShortenerApplication.java`: sets `MANAGEMENT_PORT` default in container context
