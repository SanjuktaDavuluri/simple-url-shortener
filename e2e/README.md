# Browser checks and Lighthouse

These run in **real Chrome** against a running app, covering what the JVM tests can't see (plan 0001, P4):

- `browser-checks.js` (Playwright): HTMX swaps without a reload, the copy button and "Copied ✓", the clipboard contents, screen-reader announcements, inline 422 with focus returned to the field, no JavaScript errors and no 404s, the favicon, and the whole flow with JavaScript disabled.
- `lighthouse.js`: mobile and desktop Lighthouse; every category must score **≥ 90** (ADR 0006). HTML and JSON reports for every run, plus `lighthouse-summary.json`, are written to `e2e/reports/`.

### Why the Lighthouse gate uses a warm-up and the median of 3 runs

One mobile audit on a shared CI runner once scored Performance 73, then 100 on a re-run of the same code (#41). Mobile audits simulate a slow CPU, so a single sample from a cold JVM on a noisy machine can cross the threshold on its own. So the gate:

1. **Warms up** the app first: five rounds of requests to `/` and every asset the page references.
2. **Audits each form factor 3 times** (`RUNS=3`; override with the `RUNS` environment variable).
3. **Gates each category on the median** of those runs, still at ≥ 90. Every run's scores are printed, so a noisy sample is visible rather than hidden.

A real regression still fails: if most runs are below 90, so is the median. The rule lives in `scoring.js`, and `npm test` checks it, including the CI flake (73, 100, 100 passes) and a genuine regression (71, 95, 74 fails).

```bash
./mvnw -DskipTests package
PORT=8000 BASE_URL=http://localhost:8000 java -jar target/simple-url-shortener-*.jar &
cd e2e && npm ci
npm test                                  # the scoring rule (no browser needed)
BASE_URL=http://localhost:8000 npm run all
```

Needs Node 22+ and Google Chrome installed (Playwright uses the system Chrome via `channel: 'chrome'`, so there is no browser download). CI runs these in the **Browser checks** job and uploads the reports.
