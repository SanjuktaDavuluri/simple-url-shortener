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
One successful Redirect of one Link. It is the unit that future counting and auditing are built on.
_Avoid_: Hit, visit, view
