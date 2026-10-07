import string

from shortener.codes import generate_code

BASE62 = set(string.ascii_letters + string.digits)


def test_short_codes_are_seven_base62_characters():
    codes = [generate_code() for _ in range(1_000)]

    assert all(len(code) == 7 for code in codes)
    assert all(set(code) <= BASE62 for code in codes)


def test_short_codes_vary_between_draws():
    assert len({generate_code() for _ in range(1_000)}) == 1_000
