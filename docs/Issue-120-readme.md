# Ticket #120: Domain and Click metrics in Prometheus

## What operators now see and do

### Metrics endpoint
- **New endpoint:** `GET /actuator/prometheus` on the management port (default `8081`).
- Prometheus-format metrics are scraped here.

### Domain metrics
Operators can now monitor:
- **`shortener_links_total`** – count of Links created (counter).
- **`shortener_redirects_total`** (tagged by `outcome`: `found` or `not_found`) – Redirects served.
- **`shortener_rejections_total`** (tagged by `rule`: e.g., `self_link`, `http_scheme`) – Link creations rejected by each Rule.
- **`shortener_collisions_total`** – Short Code collisions retried.

### Click metrics
- **`shortener_clicks.recorded`** – Clicks saved to the database.
- **`shortener_clicks.dropped`** – Clicks lost because the queue was full or a batch failed.
- **`shortener_clicks.pending`** – Clicks queued, not yet saved (shows when the queue is building up).
- **`shortener_clicks.queue.capacity`** – configured queue size, helps detect saturation.

### Standard metrics
- HTTP request rate, latency and error counts per route (`http.server.requests` with `uri`, `method`, `status`).
- JVM metrics (memory, threads, garbage collection).
- Hikari connection pool metrics (active, idle, pending connections).

### Integration with logs
- Every log line written while handling a request carries a `request_id` field linking it to the response's `X-Request-Id` header.
- Operators can correlate metrics spikes with specific requests by matching Request IDs.

### No sensitive data in tags
- No Short Code, Long URL, host or Rejection Reason appears as a metric tag, keeping cardinality bounded.
- The Click queue capacity is published so operators can see when Click loss is imminent (when `pending` approaches `capacity`).
