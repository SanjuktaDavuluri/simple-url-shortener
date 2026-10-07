# Browser checks and Lighthouse

These run in **real Chrome** against a running app, covering what the JVM tests can't see (plan 0001, P4):

- `browser-checks.js` (Playwright): HTMX swaps without a reload, the copy button and "Copied ✓", the clipboard contents, screen-reader announcements, inline 422 with focus returned to the field, no JavaScript errors and no 404s, the favicon, and the whole flow with JavaScript disabled.
- `lighthouse.js`: mobile and desktop Lighthouse; every category must score **≥ 90** (ADR 0006). HTML and JSON reports are written to `e2e/reports/`.

```bash
./mvnw -DskipTests package
PORT=8000 BASE_URL=http://localhost:8000 java -jar target/simple-url-shortener-*.jar &
cd e2e && npm ci
BASE_URL=http://localhost:8000 npm run all
```

Needs Node 22+ and Google Chrome installed (Playwright uses the system Chrome via `channel: 'chrome'`, so there is no browser download). CI runs these in the **Browser checks** job and uploads the reports.
