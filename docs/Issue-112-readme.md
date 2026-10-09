# Issue #112: Link stats web page

**What Link creators now see and do:**

- Navigate to `/stats` to access a form for viewing a Link's statistics.
- Enter the Short Code (or Short URL starting with the Base URL) and the Manage Token saved from link creation.
- Receive the Stats on `POST /stats`: headline non-bot Click count, bot Click count, last Click time, per-day breakdown for the last 30 UTC days (as a table with accessible formatting and CSS-only bars), device class split, top 10 Referrer Hosts, and note that counts may lag by about one flush interval.
- See a unified `404` error message ("No stats found for that Short Code and manage token.") for unknown Short Code, missing token, wrong token, or pre-R2 Link with no token—no hint which case it is.
- Use HTMX (JavaScript enabled): form updates in place with a fragment-only response; plain form post loads the full page.
- The token field is `type=password` with `autocomplete=off` and never exposed in the page URL or browser history.

**What operators now see:**

- Stats endpoint requests on the public port and management healthchecks unaffected; no new errors logged (token and Bearer authorization never appear in logs).
- `Cache-Control: no-store` on all Stats responses (`GET /stats` form, `POST /stats` success and error) prevents caching by browsers and proxies.
- No new configuration settings or environment variables.
