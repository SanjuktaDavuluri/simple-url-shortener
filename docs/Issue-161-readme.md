# Issue #161: Privacy and Log-Safety Tests for Stats and the Manage Token

## What Users and Operators Now See

- **Log safety:** Logs never contain plaintext Manage Tokens, their SHA-256 hashes, or `Bearer` authorization values, even when reading Stats fails or succeeds.
- **Cache protection:** Responses containing sensitive data (Token panels, Stats read, failed Stats `404`s) carry `Cache-Control: no-store`, so browsers and intermediaries never cache them.
- **Query string ignored:** Tokens passed in the `/links/{code}/stats` query string (`?manage_token=...`) are never used; the only accepted path is the `Authorization: Bearer <token>` header. Attempts with a token in the query string produce the same `404` as no token.
- **No raw headers in output:** Stats responses never expose the raw `Referer` URL (with user info and path) or `User-Agent` string; only safe derivatives like Referrer Host and Agent Category appear.
- **Form creates don't cache:** Web page form submissions that create a Link return `no-store`, so the Manage Token shown once in the result is never cached by the browser.
