# Ticket #121: Graceful shutdown and startup logging

## What operators now see

- **Startup confirmation:** One `Shutdown started` line in logs at INFO level when the service shuts down, confirming the shutdown sequence has begun.
- **Shutdown summary:** One `Shutdown complete` line listing the number of Clicks flushed and dropped during the shutdown, so operators can verify a clean stop.
- **Readiness state:** The readiness endpoint (`/actuator/health/readiness` on the management port) immediately returns `503` with `readinessState: OUT_OF_SERVICE` once the shutdown begins, signalling load balancers to stop sending new requests.
- **In-flight request handling:** Requests already in flight when shutdown starts complete normally (up to `SHUTDOWN_TIMEOUT`); new connections are refused immediately.
- **Ordered shutdown:** The service shuts down in a documented order: readiness goes OUT_OF_SERVICE, the web server stops accepting new connections, in-flight requests finish, queued Clicks are flushed, then the database pool closes. The entire process is bounded by `SHUTDOWN_TIMEOUT + CLICK_SHUTDOWN_TIMEOUT` (~20 seconds with defaults).
- **Startup line:** One startup line logged at INFO level listing the effective settings: ports, Base URL, database path, Click settings, shutdown timeouts and log format, so operators can confirm the instance is running with the expected configuration.

## Configuration

No new settings; this leverages existing ones:
- `SHUTDOWN_TIMEOUT` (default `10s`): how long to wait for in-flight requests to finish
- `CLICK_SHUTDOWN_TIMEOUT` (default `10s`): how long to wait for queued Clicks to flush

These are already set in `application.properties` and can be overridden via environment variables.
