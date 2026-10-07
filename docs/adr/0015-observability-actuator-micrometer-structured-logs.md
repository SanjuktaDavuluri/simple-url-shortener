---
status: accepted
date: 2026-10-07
---

# Observability: Actuator health checks, Micrometer metrics and structured JSON logs, on a separate management port

To run the shortener like a production system, an operator must be able to answer three questions without reading code: *is it up and ready?*, *how is it behaving?*, and *what happened to this request?* We use Spring Boot's standard tooling and keep operational endpoints **off the public surface**.

## Decision

- **Health:** Spring Boot Actuator health groups:
  - **liveness:** the process is alive and not deadlocked
  - **readiness:** it can take traffic. The SQLite database is reachable and migrated, and the Click queue (ADR 0012) is not saturated.
- **Metrics:** Micrometer, exposed in Prometheus format:
  - HTTP request rate, errors and latency per route (standard)
  - domain metrics: Links created, Redirects, rejections by Rule, Collisions and retries, Clicks recorded, dropped and pending, queue depth
- **Logs:** Spring Boot's built-in **structured JSON logging**. Every line carries a **request ID**, which is generated if the client didn't send one and returned as a response header, so a user's report can be traced to its log lines. Long URLs, manage tokens and raw user agents are **never logged** (ADR 0013, ADR 0014).
- **Separate management port (8081).** Actuator endpoints (health, metrics, Prometheus) are served only on the management port, never on the public application port. Only the liveness and readiness checks are meant to be reachable by infrastructure (load balancer, Docker health check). Metrics are for the operator's network only.

## Options considered and the trade-offs

| | Option | What we'd gain | What it would cost | Verdict |
|---|---|---|---|---|
| A | **Actuator + Micrometer (Prometheus) + built-in structured logging; management on a separate port** | Standard, well understood, no extra infrastructure; readable immediately with `curl` and in container logs; internals kept off the public surface | Dashboards and alerting need a Prometheus server, which is outside this project | **Chosen** |
| B | OpenTelemetry Java agent (traces, metrics, logs) to a collector over OTLP | The vendor-neutral standard; distributed tracing | Needs a collector and backend to be useful; infrastructure we don't run | Rejected for now. Micrometer can add an OTLP exporter later with no change to our metric code |
| C | Hand-written `/health` and counters | No dependencies | Re-implements standard tools, worse | Rejected |
| D | Actuator on the public port | One port | Exposes operational internals (metrics, environment details) to the internet | Rejected |

**In short:** standard tooling with no new infrastructure, and a firm line between the public product surface and operational endpoints.

## Consequences

- **Do not expose actuator endpoints on the public port** without a new ADR.
- Every new feature adds its domain metrics and documents them in the runbook (R12).
- The container image (R11) and the e2e/CI harness use the readiness check, not "the port answers", to decide the app is up.
