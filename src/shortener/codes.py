"""Short Code generation (ADR 0003): random, independent of the Long URL."""

import secrets
import string

ALPHABET = string.ascii_letters + string.digits
LENGTH = 7


def generate_code() -> str:
    """Return a new random Short Code. Takes no input: it never sees the Long URL."""
    return "".join(secrets.choice(ALPHABET) for _ in range(LENGTH))
