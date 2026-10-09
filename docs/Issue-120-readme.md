# Issue #120: Domain and Click metrics in Prometheus

## What operators now see and do

### Query the metrics endpoint
- **GET `/actuator/prometheus` on the management port** (default `8081`) returns all metrics in Prometheus text format.
- Metrics can be scraped by a Prometheus server, used in dashboards, and alerted on (queries in the runbook).

### Domain metrics: product usage and abuse detection
- **`shortener_links_total`** – cumulative Links created.
- **`shortener_redirects_total`** with tag `outcome="found"` – successful Redirects (the hot path).
- **`shortener_redirects_total`** with tag `outcome="not_found"` – 404s on unknown Short Codes.
- **`shortener_rejections_total`** with tag `rule` (values: `self_link`, `well_formed`, `http_scheme`, `host_present`, `max_length`) – Link creations rejected by each Rule. Spikes in a rule suggest abuse or misconfiguration.
- **`shortener_collisions_total`** – Short Code collisions encountered (rare; a spike signals the Short Code space is filling up).

### Click metrics: verify Click recording is working
- **`shortener_clicks_recorded_total`** – Clicks successfully saved.
- **`shortener_clicks_dropped_total`** – Clicks lost (queue full or batch write failed). **Should stay zero or very low.**
- **`shortener_clicks_pending`** – Clicks currently queued, not yet written. **Spikes when the database can't keep up.**
- **`shortener_clicks_queue_capacity`** – the configured queue size (context from `CLICK_QUEUE_CAPACITY`).

### Standard metrics (from Spring and JVM)
- **`http_server_requests_seconds`** – histogram of request latency per route (tagged by `uri` pattern like `/{shortCode}`). Observe Redirect latency separately from Link creation.
- **`jvm_memory_used_bytes`**, JVM thread counts, garbage collection times.
- **`hikaricp_connections`** (active, idle, pending) – the SQLite connection pool (bounded by `JdbcClient` configuration, see ADR 0021).

### Privacy: no sensitive data in metrics
- No Short Code, Long URL, referrer host or Rejection Reason appears in any metric tag — counts only.
- Metric cardinality stays bounded and safe for long-term monitoring.
