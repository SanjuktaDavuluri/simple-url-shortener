# Issue #119: Structured JSON logs with Request IDs

## What operators and users see

### Console logs are now structured JSON
- Every log line is valid JSON with timestamp, level, logger name and message
- Each line can be parsed and filtered by field, instead of parsing plain text patterns
- Pass `LOG_FORMAT=text` to revert to Spring Boot's plain text format for local debugging

### Every response carries a Request ID
- All HTTP responses (success, error, redirects, rejections) now include an `X-Request-Id` header
- API clients can use this ID to trace their request through the logs
- Clients can provide their own `X-Request-Id` header (up to 64 characters of letters, digits, `.`, `_`, `-`); otherwise one is generated

### One access log line per request
- Each request logs exactly one `INFO` line with:
  - `http_method`: GET, POST, HEAD
  - `route`: the matched handler (e.g., `/{short_code}`, `/links`)
  - `status`: HTTP status code
  - `duration_ms`: request processing time in milliseconds
  - `request_id`: the `X-Request-Id` value
- No raw paths, query strings, or request bodies are logged

### Privacy: sensitive data never reaches the log
- Long URLs, referrer URLs, user agents, authorization headers, query strings, IP addresses and Manage Tokens are never logged
- This holds across every operation: creating Links, following redirects, rejections, errors, and graceful shutdown

### Configuration
- `LOG_FORMAT=json` (default): structured JSON logs
- `LOG_FORMAT=text`: plain text logs (for local development)
