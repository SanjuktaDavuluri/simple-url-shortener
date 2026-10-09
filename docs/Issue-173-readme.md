# Issue #173: compose.yaml for local run with environment pass-through and drift test

## What operators and developers see

- **Local startup with `docker compose up`:** starts the container with the default `BASE_URL=http://localhost:9000` and public port `9000`, and passes through other settings (`CLICK_QUEUE_CAPACITY`, `SHUTDOWN_TIMEOUT`, etc.) from the environment with the same defaults as the runbook.

- **Configurable ports:** `BASE_URL=http://localhost:9001 PORT=9001 docker compose up` starts on a different port with matching Short URLs; `docker compose down` keeps the `/data` volume by default, `docker compose down -v` removes it.

- **Management port private:** the management port (`8081` by default) is published to the host's loopback only (`127.0.0.1:8081`), not exposed to the network. Health and metrics remain private to the operator's machine.

- **Compose file validates against runbook settings:** a `ComposeFileTest` runs in CI to ensure that every environment variable passed through `compose.yaml` is documented in the runbook's configuration reference, and vice versa—no drift.

## Testing

- Compose configuration syntax is validated by `docker compose config`.
- The drift test asserts that `compose.yaml`'s environment names match the runbook's documented settings exactly.
