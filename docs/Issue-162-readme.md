# Ticket #162: Document Stats

This ticket documents the Stats feature (spec 0005) across architecture, glossary, onboarding, README, roadmap, and the integration-testing plan.

## What users see and do

- **When creating a Short Link:** The response now shows a **Manage Token** — a unique secret shown once, with a copy button and a "Keep this safe" note. This token is the only way to see the link's usage statistics.
- **On the Stats page (`/stats`):** Enter the Short Code (or Short URL) and the Manage Token in a form. Both fields are required; the token is masked as a password field and never appears in the URL.
- **Reading Stats:** The Stats show:
  - **Click count** (people only, bots excluded) and **bot click count** (separate)
  - **Last click time** (when the link was most recently followed)
  - **Clicks per day** for the past 30 UTC calendar days, including days with no clicks
  - **Device split:** how many clicks came from desktop vs. mobile
  - **Top referrer hosts:** which websites sent traffic (top 10)
  - **Direct clicks:** how many clicks came with no referrer information
  - **Creation time:** when the link was created
  - **Lag note:** "Counts may lag by about 1 second"
- **Privacy:** Only the link creator (who has the token) can see these stats. Anyone without it gets the same "No stats found" message, which never reveals whether the link exists.
- **Token security:** The token is shown only once in the create response. It is never sent in URLs, and the shortener stores only a one-way hash of it so even a database leak won't expose tokens.
- **Expired links:** Links that have expired (if a lifetime was set) still show their stats with the token.

## How operators configure it

Stats are enabled by default with no additional configuration. The stats API and web page are ready to use immediately after creating a link.
