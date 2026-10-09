# Issue #123: Operability Documentation

Ticket T8 delivers the first runbook and configuration documentation for the shortener service.

## What operators now see and do

- **New `docs/runbook.md`:** A complete guide to running the service locally and in production, covering:
  - How to start the service (via `java -jar`, `scripts/local.sh`, or in a container)
  - How to check its health (Liveness and Readiness endpoints on the management port)
  - How to diagnose problems (finding requests by Request ID, reading structured logs, common symptoms)
  - How to stop the service gracefully (documented stop timeouts)
  - Where data is stored (SQLite database file names and backup guidance)
  - Logging and exposure rules (what logs contain and never contain, network placement of the management port)

- **Configuration reference in the runbook:** Every environment variable (`PORT`, `BASE_URL`, `MANAGEMENT_PORT`, `DATABASE_PATH`, Click settings, log format, shutdown timeouts) listed in one table with its default, type and meaning. No more searching through code and `application.properties`.

- **Configuration validation at startup:** Invalid settings now stop the service immediately with a clear message naming the variable:
  - A relative or malformed `BASE_URL` (not `http`/`https`, with query or fragment)
  - Non-positive Click settings or a batch size larger than the queue capacity
  - Invalid log format (not `json` or `text`)
  
- **Startup line logged:** Once ready, the app logs one line showing the effective settings (ports, Base URL, Click settings, shutdown timeouts). Operators can confirm the instance is running as intended.

- **Documentation updates:** 
  - `docs/architecture.md` updated with the operability flows (Request ID, management port, shutdown sequence)
  - `docs/onboarding.md` updated with configuration guidance
  - `CONTEXT.md` updated with operability glossary terms (Request ID, Liveness, Readiness)

- **Test that catches configuration drift:** A test in the integration suite confirms that every environment variable used in `application.properties` is documented in the runbook's configuration table. If a new setting is added, the build fails until it is documented.
