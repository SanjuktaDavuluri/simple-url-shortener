---
status: accepted
date: 2026-10-08
---

# The Manage Token is stored as a plain SHA-256 hash, not a password hash

ADR 0014 decided that each Link gets a Manage Token, a high-entropy random secret returned once at creation, and that the shortener stores it only as a one-way hash, "like a password". It didn't say which hash. Spec 0005 (R2, Stats per Link) has to choose one, because `V3__add_manage_token_hash.sql` adds the `links.manage_token_hash` column and every new Link writes to it.

This is hard to undo. Once Links are created, their hashes are in the database and the tokens are gone: nobody can re-hash a stored hash with a different function. Switching later means adding an algorithm marker to every hash and supporting both functions, or reissuing every token (R4). It may also surprise someone who reads "hashed like a password" in ADR 0014 and expects bcrypt or Argon2, so the reasoning is written down here.

## Decision

- The token is **32 bytes from `SecureRandom`** (256 bits), encoded as unpadded base64url (43 characters), as spec 0005 sets out.
- The shortener stores **SHA-256 of the token's UTF-8 bytes as lower-case hex** (64 characters) in `links.manage_token_hash`. There is **no salt and no server-side key**.
- A presented token is hashed the same way and compared with **`MessageDigest.isEqual`** (constant time). When the Short Code is unknown or the Link has no hash, the presented token is still hashed and compared against a **fixed dummy hash**, so the time a request takes doesn't reveal whether the Short Code exists (ADR 0014's `404` for every failure).
- Hashing and comparison live in one place (`ManageTokens`), so the choice is made once and tested once.

## Options considered and the trade-offs

| | Option | What we'd gain | What it would cost | Verdict |
|---|---|---|---|---|
| A | **Plain SHA-256 of the token** | Microseconds per check, so Stats reads and the `404` path stay fast and their timing stays even. In the JDK, with no new dependency and no tuning parameter. Deterministic, so the stored value is easy to check in tests against a known digest. A leaked database still gives nothing away: inverting SHA-256 of a uniformly random 256-bit value is infeasible, whatever the attacker's hardware | No work factor, so it would be the wrong choice if tokens were ever guessable (short, human-chosen or from a weak random source). No salt, which only matters for guessable secrets. Doesn't match the "password hash" picture a reader may expect from ADR 0014 | **Chosen** |
| B | Slow password hash (bcrypt or Argon2) with a per-token salt | The familiar "how passwords are stored" answer. Would still protect weak secrets if tokens ever became low-entropy | Tens to hundreds of milliseconds of CPU and (for Argon2) memory on every Stats read, and also on every failed one, because the dummy-hash path must cost the same. That makes each unauthenticated request expensive and turns the not-yet-rate-limited Stats endpoint (R19) into a cheap way to load the service. A new dependency and a cost parameter to tune and migrate. The work factor protects against dictionary and brute-force guessing, which a 256-bit random secret already defeats by a margin no work factor adds to | Rejected: real cost on every request for no security gain on random tokens |
| C | HMAC-SHA-256 with a server-side secret key (a "pepper") | A database leak on its own reveals nothing, even for weaker secrets: the attacker also needs the key | A new secret to generate, configure, keep out of logs and the repository, back up and rotate. Losing or changing it locks every creator out of their Stats, and there's no reissue path until R4. Adds a configuration setting where spec 0005 promises none. For 256-bit random tokens the key adds no protection that A lacks | Rejected: operational risk and a new secret, for no gain while tokens are random |
| D | Store the token itself (no hash) | Simplest; tokens could be shown again | A database leak or backup copy hands over every creator's Stats, and later their edit and delete rights (R4). Contradicts ADR 0014 | Rejected: ruled out by ADR 0014 |

**In short:** a password hash's slowness exists to make guessing a weak secret expensive. The Manage Token isn't weak: it's 256 bits from `SecureRandom`. So the slow hash would cost every request something and protect nothing, and SHA-256 with a constant-time compare is the right tool.

## Consequences

- **The token must stay high-entropy.** SHA-256 is only safe here because the token is 256 random bits. Any change that makes tokens shorter, human-chosen or drawn from a weaker random source (including a "custom token" feature) needs a new ADR, and likely option B or C. A unit test pins the generator to 43 base64url characters.
- **The stored format is fixed.** `manage_token_hash` holds 64 lower-case hex characters with no algorithm prefix. If the hash ever changes, the migration must add a marker (for example a prefix or a separate column) and check both formats until old tokens are reissued (R4).
- **Timing stays even.** Every Stats request, matching or not and for known or unknown Short Codes, does one SHA-256 and one constant-time compare. Tests cover the dummy-hash path (spec 0005, Testing Decisions, Seam 4).
- **No new configuration or secret.** Backups of the data directory (ADR 0021) contain only hashes, which can't be turned back into tokens.
- **Rate limiting is still needed** (R19, ADR 0014), to protect the service's resources, not the tokens: guessing a 256-bit token is infeasible at any request rate.
- **The secret still has to be guarded in transit and in logs.** Hashing protects the stored value only. The token must never appear in a URL or log line, and deployments must use HTTPS (spec 0005; the runbook, R12).
