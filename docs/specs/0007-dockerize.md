---
status: implemented
date: 2026-10-09
release: 2
roadmap: R11
issue: 171
triage: ready-for-agent
---

# Spec 0007: Dockerize: a multi-stage image, a Compose file for a local run, and an image built and tested in CI

Glossary: `CONTEXT.md` · Decisions: ADR 0002, ADR 0007, ADR 0012, ADR 0015, ADR 0021 · Builds on: spec 0006 (operability basics) · Flows: `docs/architecture.md` · Roadmap: **R11** in `docs/roadmap.md` · Issue: [#171](https://github.com/SanjuktaDavuluri/simple-url-shortener/issues/171)

## Problem Statement

The shortener runs only as `java -jar` (or `scripts/local.sh`), so running it needs a matching JDK and a Maven build on the machine. Nothing proves the service works packaged the way it would be run elsewhere:

- **No portable artifact.** A reviewer or operator can't start the service with one command and no toolchain.
- **Untested packaging.** CI tests the jar and the source, never the thing that would actually be run. Problems that only appear in a container (a read-only or missing data directory, a root-owned database file, a wrong working directory, a missing environment variable) would be found late.
- **No container health check.** Spec 0006 delivered Readiness, the management port, graceful shutdown and validated configuration so that a container has something real to check, but nothing uses them yet.
- **Data in the wrong place.** The SQLite database (ADR 0002) lives in the working directory by default (`DATABASE_PATH=links.db`). In a container that would vanish with the container.

## Solution

The service is delivered as a container image, built and tested in CI. This is a delivery of the product, **never a deployment** (ADR 0007): nothing here pushes an image to a registry, deploys it, or connects to or restarts the maintainer's local service on port 8000.

- **A multi-stage `Dockerfile`:** a build stage that runs the Maven Wrapper to produce the jar, and a runtime stage on a JRE 25 base that holds only the jar. No JDK, Maven cache or source in the final image.
- **Non-root:** the process runs as a dedicated unprivileged user.
- **A `/data` volume** for the SQLite database. The image sets `DATABASE_PATH` to a file under `/data`, so the database survives the container being replaced and the volume is the only writable location the service needs (WAL files included, ADR 0021).
- **A container health check** that polls Readiness on the management port (`/actuator/health/readiness`, spec 0006), never "the port answers" (ADR 0015). The image needs no extra tooling beyond what the JRE base provides, or a small documented one.
- **Graceful stop:** `docker stop` sends `SIGTERM` to the Java process (not a wrapper shell), so spec 0006's shutdown order applies unchanged. The stop grace period is longer than the documented longest shutdown.
- **`compose.yaml` for a local run:** one service, the `/data` volume, the public port published, the management port published to the host's loopback only (it is private, ADR 0015), and `BASE_URL` plus the other settings (`CLICK_*`, `SHUTDOWN_TIMEOUT`, `LOG_FORMAT`, …) passed through from the environment with the same defaults as the runbook's configuration reference. A different host port still produces correct Short URLs when `BASE_URL` matches.
- **CI builds the image and tests the container**, in the existing jobs or a new job whose name is not one of the three required checks. The required checks ("Verify (format, compile, analysis, unit + integration tests)", "Browser checks (Playwright + Lighthouse)", "Orchestrator (lint, types, tests)") keep their names. The container test starts the built image on its own port with a scratch volume and checks that it becomes ready, that a Link can be created and followed, that the data survives a container restart on the same volume, that it runs as non-root, and that `docker stop` finishes within the graceful-shutdown bound with queued Clicks saved.

## User Stories

### Build

1. As a reviewer, I want to build the image with one `docker build` command and no Java or Maven installed, so that I can run the service without a toolchain.
2. As an operator, I want the runtime image to contain only a JRE 25 and the application jar, so that it is small and has a small attack surface.
3. As a maintainer, I want the build to use the Maven Wrapper and the project's pinned versions, so that the image is built from the same inputs as CI's `./mvnw verify`.
4. As a maintainer, I want dependency downloads cached between builds where the layer order allows, so that a code-only change doesn't re-download everything.
5. As a maintainer, I want a `.dockerignore`, so that `.local/`, `target/`, `.git`, `e2e/node_modules` and other local state never enter the build context or the image.

### Run

6. As an operator, I want the process to run as a non-root user, so that a flaw in the service doesn't give root in the container.
7. As an operator, I want the SQLite database, and its WAL files, on a `/data` volume owned by that user, so that data survives replacing the container and the service never needs to write anywhere else.
8. As an operator, I want an empty `/data` volume to be initialised by Flyway on first start, and an existing one to be used as is, so that the first run and a restart both just work.
9. As an operator, I want every setting to be an environment variable with the runbook's defaults, so that one image runs anywhere without rebuilding (spec 0006).
10. As an operator, I want a misconfigured container (for example a malformed `BASE_URL`) to exit non-zero with the message naming the variable, so that a bad deploy fails loudly (spec 0006).
11. As an operator, I want the logs on the container's standard output as JSON, so that the platform's log collection works with no extra setup (ADR 0015).

### Health and stopping

12. As an operator, I want the container to report `healthy` only when Readiness is `UP`, and `unhealthy` when the database can't be reached or the Click queue is full, so that the platform acts on the same signal the runbook describes.
13. As an operator, I want the health check to allow for start-up time, so that a slow first start isn't marked unhealthy.
14. As an operator, I want `docker stop` to start the graceful shutdown (Readiness `OUT_OF_SERVICE`, in-flight requests finish, queued Clicks are flushed, then the pool closes) and the container to exit with code 0, so that a deploy doesn't lose Clicks or fail requests being served.
15. As an operator, I want the container's stop grace period to exceed the longest documented shutdown (`SHUTDOWN_TIMEOUT` + `CLICK_SHUTDOWN_TIMEOUT`), so that Docker doesn't `SIGKILL` a shutdown still in progress.

### Local run with Compose

16. As a developer, I want `docker compose up` to start the service with working defaults, and `BASE_URL=http://localhost:9000 PORT=9000 docker compose up` to run it on another port with matching Short URLs, so that a scratch run never needs file edits.
17. As a developer, I want Compose's published host ports to be configurable, so that a container run never collides with the maintainer's service on `8000`/`8081`.
18. As an operator, I want the management port published only on the host's loopback, so that health and metrics are not exposed to the network by default (ADR 0015).
19. As a developer, I want `docker compose down` to keep the data volume and `docker compose down -v` to remove it, and the runbook to say so, so that I know how to keep or reset data.

### CI

20. As a maintainer, I want CI to build the image on every pull request, so that a change that breaks the Dockerfile is caught before merge.
21. As a maintainer, I want CI to run a smoke test against the running container: ready, create a Link, follow it, restart on the same volume and follow it again, so that packaging and persistence are proven, not assumed.
22. As a maintainer, I want CI to assert that the container's user is not root and that `docker stop` exits `0` within the documented bound, so that the non-root and graceful-shutdown properties can't silently regress.
23. As a maintainer, I want the three required check names to be unchanged and the new container check, if it is a separate job, to be added to branch protection in the same PR only if the maintainer wants it required (see Further Notes), so that renaming never breaks `main`'s protection.
24. As a maintainer, I want the CI container test to use its own port and a temporary volume, so that it can't interfere with any other instance (CLAUDE.md, ADR 0007).
25. As a maintainer, I want the image never to be pushed anywhere by this work, so that building stays separate from deploying.

### Documentation

26. As an operator, I want the runbook to explain running the container: build, `docker compose up`, the volume, the health check, stopping, and the configuration reference marked for container use, so that I can run and diagnose it without the developers.
27. As a new developer, I want the onboarding guide to cover building and running the container and the CI container test, so that I can reproduce CI locally.
28. As a reviewer, I want the integration-testing plan's matrix (`docs/plans/0001-integration-testing.md`) to include the container tests, so that the test inventory stays complete.
29. As a maintainer, I want a test that fails when `compose.yaml` passes through a setting the runbook's configuration reference doesn't document (or vice versa for the settings it claims to pass), so that Compose can't drift from the reference, as spec 0006 does for `application.properties`.

## Implementation Decisions

- **Approach already decided (2026-10-07, no ADR, `docs/roadmap.md` R11):** an explicit multi-stage Dockerfile, a non-root user, a `/data` volume, a Readiness health check, `compose.yaml`, and CI tests run against the container. Nothing in this spec reverses that. A new ADR would be needed only if a decision below turns out hard to reverse; none does.
- **Base images:** a Maven/JDK 25 image for the build stage (or the wrapper on a JDK 25 image) and a JRE 25 image for runtime. The exact distribution (Eclipse Temurin is what CI uses) and whether to pin by digest are for the implementation, as long as the result is reproducible enough to review.
- **Health check:** run inside the container against `localhost` on the management port. The tool used (for example `curl`, `wget`, or a tiny Java call) is for the implementation, provided the image stays minimal.
- **PID 1:** `exec` form so the JVM receives `SIGTERM` directly; the JVM's container awareness sets memory from the container limit.
- **The `/data` default:** the image sets `DATABASE_PATH=/data/links.db`. Running `java -jar` outside the container keeps its current default (`links.db`).
- **Where the tests run:** a script (for example `scripts/container-test.sh`) drives the container checks so that developers and CI use the same steps. CI needs Docker, which GitHub-hosted runners provide. The tests exercise the real image over HTTP; they do not replace the existing `*IT` tests.
- **No change to service code** is expected, other than what the image configuration needs. If the container tests find a defect (for example a file-permission problem), it gets its own bug-fix ticket.

## Testing Decisions

- A good test here is **black-box against the built image**: it asserts on observable behaviour (HTTP responses, health status, container user, exit code, data after restart), never on the Dockerfile's text.
- **Container smoke test (CI and local):** build the image; start it on a scratch port with a scratch volume; wait for the container to report `healthy`; create a Link and follow it; restart the container on the same volume and follow it again; check `id -u` is not `0`; `docker stop` and assert exit code `0` within the bound.
- **Misconfiguration test:** start the image with an invalid `BASE_URL` and assert a non-zero exit and a message naming `BASE_URL`.
- **Compose test:** `docker compose config` validates `compose.yaml`, and the drift test (story 29) compares its environment names with the runbook's configuration reference.
- **Prior art:** `IntegrationTest`/`TestApps` for the service, spec 0006's configuration-drift test, and the CI browser-check job's start/ready/stop steps in `.github/workflows/ci.yml`.
- The existing required jobs keep running unchanged. The integration-testing plan's matrix gains the container rows.

## Out of Scope

- **Deployment** of any kind, pushing the image to a registry, image signing, and Kubernetes or other orchestrator manifests (ADR 0007: delivery never deploys).
- Connecting to, stopping, restarting or resetting the maintainer's local service on port 8000.
- Multi-instance setups, load balancers and a shared database (ADR 0019, R15); SQLite stays single-instance.
- Failure and resilience testing of the container (a killed process under load, a read-only or full volume): R14.
- Load and performance testing of the container: R13.
- Image vulnerability scanning and dependency-update automation (could be later items; not in the Issue).
- Multi-architecture images unless the chosen base images give them for free.
- Changing the SQLite pool, WAL mode or busy timeout (ADR 0021), or the management port's lack of authentication (ADR 0015).
- The OpenAPI definition (R21).

## Further Notes

- **Decided while writing this spec, all easy to change:**
  - the image sets `DATABASE_PATH=/data/links.db`
  - Compose publishes the management port to `127.0.0.1` only
  - the smoke test is a script shared by developers and CI
  - the container test is run on every pull request
  - the stop grace period is at least `SHUTDOWN_TIMEOUT` + `CLICK_SHUTDOWN_TIMEOUT` plus a margin
- **Required check:** the Issue says CI must not rename the required checks. Whether the container test becomes a *new required* check is the maintainer's call at merge time (changing branch protection is theirs). The default here is to add it as a separate, non-required job at first, and to add it to the protection in the same PR if the maintainer asks.
- **Volume ownership:** a named volume is initialised from the image's `/data`, so creating `/data` owned by the non-root user in the image avoids permission errors. A bind mount owned by another user would not get that treatment. The runbook should say so.
- **Possible ticket slices** for decomposition (the decompose Stage decides):
  1. `Dockerfile`, `.dockerignore`, non-root user, `/data` volume and health check, with the container smoke script.
  2. `compose.yaml` with environment pass-through, the loopback management port, and the Compose drift test.
  3. CI: build the image and run the container tests without renaming the required checks.
  4. Documentation: runbook (container section), onboarding guide, integration-testing plan matrix, architecture diagram, roadmap R11 and the spec index.
- **Status lifecycle:** `draft → accepted → in-progress → implemented`. Set to `in-progress` when the first ticket starts and to `implemented` when the last ticket's work is merged. Add the spec to the index in `docs/specs/README.md` when it is accepted.
