# Issue #160: Browser checks and Lighthouse for create-then-stats

## What users and operators see

- **Stats page is live:** creators can now follow a "See its stats" link from the create result, which navigates to `/stats?short_code=<code>`
- **Stats form:** the Stats page offers fields for Short Code (pre-filled from the link) and Manage Token (password-type field, never in the URL)
- **Stats display:** entering the correct Manage Token shows click statistics including headline count (non-bot), bot clicks, last Click time, 30-day breakdown, device class distribution, and top 10 referrer hosts
- **Wrong token:** a missing or incorrect token returns a 404-like message with no confirmation of whether the Short Code exists
- **No JavaScript required:** the full Stats journey (create, copy token, navigate to stats, submit form, view results) works without JavaScript using standard form posts and full page reloads
- **Browser support verified:** Playwright browser checks in Chrome verify the entire flow:
  - Copy button and clipboard functionality for the Manage Token
  - HTMX-based updates when JavaScript is enabled (no full page reload)
  - Token never exposed in page URLs or HTTP requests
  - All UI elements render with no JavaScript errors
  - Lighthouse scores ≥ 90 on both desktop and mobile for the Stats page
