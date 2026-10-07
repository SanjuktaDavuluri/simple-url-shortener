---
status: accepted
date: 2026-10-07
---

# Random 7-character base62 short codes, retried on collision

Each short code is 7 characters drawn at random from `[a-zA-Z0-9]`, using a cryptographically secure source (`java.security.SecureRandom`). If a generated code is already taken, we generate another and retry, up to a small fixed limit. This makes codes impossible to guess, and the space of about 3.5 × 10¹² codes keeps collisions rare at any scale this project will reach.

## The generator is independent of the long URL

Code generation is a standalone function that takes **no input**. It never sees the long URL. The URL is only paired with a code when the two are stored together:

```
rules.check(url) → code = generator.next() → linkStore.save(code, url)  ── code taken? → generate again (bounded)
```

See the sequence diagram in [`docs/architecture.md`](../architecture.md).

So nothing in the design makes the same long URL map to the same code. Shortening a URL twice produces two independent links, unless we deliberately add a lookup by URL. Whether duplicates are reused is therefore a **separate product decision**, not a side effect of how codes are generated. A hash-based scheme (option 2 below) would have made that decision implicitly.

## Considered Options

Each option was judged against three requirements: codes must be **short**, must **not be guessable** so the full set of links can't be listed, and the scheme must be **simple enough for a single-node v1**.

1. **Sequential ID in base62** (row 1000 → `g8`). *Inadequate.* The codes are the shortest possible and never collide, but anyone can walk `/1`, `/2`, `/3`… and read every link anyone has shortened. The code also leaks how many links exist. Obfuscating the sequence (XOR, Hashids) only hides the pattern; it is not real unpredictability.
2. **Hash of the long URL, truncated to 7 characters.** *Inadequate.* Truncated hashes can still collide, so we would need collision handling anyway. It also ties code generation to the policy question "does the same URL always get the same code?" That is a product decision and should stay separate. Anyone who knows a URL can also work out its code.
3. **UUID or full hash.** *Inadequate.* 22–36 characters defeats the purpose of a *short* URL.
4. **Pre-generated key pool, Snowflake-style distributed IDs, or a separate key-generation service.** *Overkill.* These solve coordinating writes across many nodes at high throughput. v1 is a single process on SQLite (ADR 0002), so they would add moving parts with nothing to gain. If we ever scale out, we will revisit this and write a new ADR that supersedes this one.

## Consequences

- Creating a link may take more than one database write when codes collide. The retry limit turns a collision streak, which is practically impossible but possible in principle, into an explicit error rather than an infinite loop. Tests must cover the collision path.
- The `short_code` column needs a uniqueness constraint. The database, not the application code, is the final guarantee against duplicate codes.
