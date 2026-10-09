# Issue #118: Startup Configuration Validation

## What operators see

When starting the service with invalid configuration, startup fails immediately with an error message that **names the variable and its invalid value**.

### Validated settings

**`BASE_URL`** must be an absolute HTTP/HTTPS URL with no query string or fragment:
- ✓ `http://sho.rt`, `https://sho.rt/` (trailing slash allowed)
- ✗ `/relative` (relative paths rejected)
- ✗ `ftp://...` (non-HTTP schemes rejected)
- ✗ `http://sho.rt?x=1` (query strings rejected)
- ✗ `http://sho.rt#frag` (fragments rejected)

**Click settings** must satisfy:
- `CLICK_BATCH_SIZE` > 0
- `CLICK_QUEUE_CAPACITY` > 0
- `CLICK_FLUSH_INTERVAL` > 0 seconds (e.g. `PT1S`, not `PT0S`)
- `CLICK_SHUTDOWN_TIMEOUT` > 0 seconds
- `CLICK_BATCH_SIZE` ≤ `CLICK_QUEUE_CAPACITY`

### Startup behavior

The application **stops and reports the problem** with enough detail to fix it, using this pattern:

```
... variable="BASE_URL", invalid_value="/relative", problem="..."
```

No silent degradation, no starting with invalid configuration. Operators see exactly what failed and why.
