# Ticket #174: CI Container Tests

## What users and operators see

**In pull requests:**
- A new CI job **"Container (image build + container tests)"** runs on every PR, alongside the three existing required checks
- The job builds the Docker image and validates the container

**Container smoke test executed in CI:**
- Builds the image using the `Dockerfile`
- Starts the container on its own port with a temporary volume
- Verifies the container becomes ready (Readiness endpoint on the management port answers `UP`)
- Creates and follows a Short Link over HTTP to confirm the service works in the container
- Restarts the container on the same volume and verifies the Link persists
- Asserts the process runs as a non-root user
- Verifies `docker stop` exits gracefully within the documented shutdown timeout

**Compose validation in CI:**
- Validates `compose.yaml` is syntactically correct with `docker compose config`

**Image artifact:**
- The image is built and stored as a CI artifact for reviewers to run locally
- The image is **never pushed** to a registry
