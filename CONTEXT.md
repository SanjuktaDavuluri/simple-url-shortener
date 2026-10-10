# Simple URL Shortener

Turns long web addresses into short ones, and sends anyone who follows a short one on to the original. This glossary is the shared language for the spec, tickets, code and conversations.

## Language

### Links

**Link**:
The pairing of one Short Code with one Long URL, created by a single shortening request. Two requests for the same Long URL create two distinct Links.
_Avoid_: Mapping, entry, record, URL (on its own)

**Long URL**:
The original web address a Link points to, as submitted by the person shortening it.
_Avoid_: Original URL, target, destination, full URL

**Short Code**:
The 7-character identifier that names exactly one Link (e.g. `Ab3xK9q`). It is chosen independently of the Long URL.
_Avoid_: Hash, slug, ID, key, token

**Short URL**:
The complete shareable address for a Link: the shortener's Base URL followed by the Short Code (e.g. `http://localhost:8000/Ab3xK9q`).
_Avoid_: Shortened link, tiny URL; never use it to mean the Short Code alone

**Base URL**:
The shortener's own public address, which every Short URL begins with.
_Avoid_: Host, domain, origin

**Collision**:
A newly drawn Short Code that already names an existing Link, so another must be drawn.
_Avoid_: Clash, duplicate code

**Manage Token**:
The secret returned once, when a Link is created, which authorises reading its Stats and later editing and deleting it. It is a high-entropy random value, never shown again; the shortener keeps only a one-way hash of it (ADR 0014, ADR 0023). Links created before Manage Tokens existed have none.
_Avoid_: API key, password, access code

**Lifetime**:
The whole number of days, 1 to 365, that the person shortening a URL may give a Link when creating it.
_Avoid_: TTL, duration, validity

**Expiry**:
The fixed UTC time a Link stops Redirecting: its creation time plus its Lifetime. A Link created without a Lifetime has none and never expires.
_Avoid_: Expiration date, deadline, TTL

**Expired Link**:
A Link whose Expiry has passed. It stays stored, its Short Code is never reused, and its Short URL answers `410 Gone` instead of Redirecting.
_Avoid_: Dead link, deleted link, inactive link

### Rules

**Rule**:
A single, independent check that a Long URL must pass before a Link can be created. It either passes or gives a Rejection Reason.
_Avoid_: Validator, filter, policy, check (on its own)

**Rule Set**:
The collection of Rules currently in force. A Long URL is accepted only if it passes every Rule in it.
_Avoid_: Ruleset, validation pipeline, rule engine

**Rejection Reason**:
The human-readable explanation a Rule gives when a Long URL fails it (e.g. "Links to this shortener aren't allowed").
_Avoid_: Error, validation message

**Self-link**:
A Long URL that points back at the shortener's own Base URL. It is always rejected so that Links can't loop into each other.
_Avoid_: Recursive link, loop URL

### Following links

**Redirect**:
Sending someone who requests a Short URL on to its Link's Long URL. The shortener performs it on every request, never leaving it to be remembered by the browser.
_Avoid_: Forward, resolve, expand

**Click**:
One successful Redirect of one Link. It is recorded with its Short Code, the UTC time the Redirect was served, its Referrer Host, Agent Category and Device Class, and nothing that could identify a person. It is the unit that counting and auditing are built on.
_Avoid_: Hit, visit, view, event (on its own)

**Click Recorder**:
What takes each Click off the Redirect path and saves it later: the Redirect hands the Click over and returns at once, and Clicks wait in a bounded queue until a single background writer saves them in batches. A Click it can't keep (the queue is full, or its batch fails to save) is dropped and counted, never waited for.
_Avoid_: Tracker, logger, event bus, analytics pipeline

**Referrer Host**:
The host name, in lower case and without port, user info, path or query, of the page a Click came from, taken from the `Referer` header. A Click has none when the header is missing or isn't a valid `http`/`https` URL.
_Avoid_: Referrer, referer URL, source, origin

**Agent Category**:
What kind of client followed the Link, reduced from its user agent: `browser`, `bot` (known crawlers and link-preview fetchers, checked first) or `other` (scripts, tools and a missing user agent).
_Avoid_: User agent, browser type, client type

**Device Class**:
Whether a Click came from a `mobile` or a `desktop` device, reduced from its user agent. Anything without a mobile marker, including bots and a missing user agent, is `desktop`.
_Avoid_: Device, platform, form factor

**Stats**:
The aggregated view of one Link's stored Clicks, readable only with its Manage Token. Anyone without it gets the same `404` as for an unknown Short Code (ADR 0014). They report counts, never single Clicks, and can trail the latest Redirects by about one flush of the Click Recorder (ADR 0012). An Expired Link keeps its Stats.
_Avoid_: Analytics, metrics, report, dashboard

**Headline Click count**:
A Link's non-bot Clicks (Agent Category `browser` plus `other`), all time. Bot Clicks are counted on their own and never in the headline (ADR 0013).
_Avoid_: Hits, views, visits, total clicks

### Delivery

These terms belong to the delivery plane: how changes to the shortener are made, not what the shortener does.

**Run**:
One delivery of one GitHub Issue through the Stages, from intake to close-out, identified as `R-NNNN`.
_Avoid_: Job, pipeline, workflow (on its own)

**Stage**:
One step of a Run with a fixed purpose (intake, requirements, design, decompose, implement, document, PR, release readiness, close-out). It can start only after the Stages it depends on have passed their Exit Gates.
_Avoid_: Step, phase, task

**Exit Gate**:
The deterministic check a Stage must pass before anything downstream may start.
_Avoid_: Validation, check (on its own)

**Approval Checkpoint**:
A point where a Run waits for a human decision: the spec, each ADR, the ticket breakdown, each merge, and any dependency change.
_Avoid_: Sign-off, review (on its own)

**Lane**:
The implement → document → PR Stages for one ticket, running in parallel with other Lanes whose tickets don't block it. A Lane starts once every ticket blocking it has merged, and at most `max_parallel_lanes` are in flight at once.
_Avoid_: Branch, track, worker

**Rollback**:
Undoing one failed Lane (its PR closed, its branch removed) while keeping every Lane that passed.
_Avoid_: Revert, undo

**Safe-stop**:
Halting a Run after its current action finishes, with its state saved so that it can resume.
_Avoid_: Abort, kill, cancel

**Re-plan**:
Redoing only the Stages whose recorded inputs have changed since they ran, and everything downstream of them.
_Avoid_: Restart, rerun

**Spec amendment**:
A change to an approved spec proposed by an agent that found a gap in it. It must be the whole spec, with its front matter and every heading, and differ from the approved one; the approval request shows the diff. It is approved like any artifact and reaches main through a PR; agents never edit the spec themselves.
_Avoid_: Spec edit, patch

**Waiver**:
An engineer's recorded decision, with a reason, to pass one release-readiness problem (a merged PR's commit without its ticket reference). It is written to the Event Log and listed in the close-out report; nothing else the gate checks can be waived.
_Avoid_: Override, skip

**Follow-up ticket**:
A new ticket for a change to work that has already merged, so that merged history is never rewritten.
_Avoid_: Reopen, rework

**Event Log**:
The append-only, hash-chained record of everything that happened in a Run. It is the system of record; the Issue comments are a readable mirror of it.
_Avoid_: History, audit (on its own), log (on its own)

### Operating

**Request ID**:
The identifier of one request to the shortener, returned in the `X-Request-Id` response header and carried on every log line written while handling it. The caller's own `X-Request-Id` is used when it is safe, otherwise one is generated.
_Avoid_: Trace ID, correlation ID, transaction ID

**Liveness**:
Whether the process is running and should not be restarted. It never depends on the database or the Click queue.
_Avoid_: Health (on its own), ping

**Readiness**:
Whether the instance can serve traffic now: the database is reachable, the Click queue isn't saturated, and no shutdown has begun. Both checks are on the management port.
_Avoid_: Healthy (on its own), up
