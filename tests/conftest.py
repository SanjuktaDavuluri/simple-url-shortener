from collections.abc import Callable, Iterable

import pytest
from fastapi.testclient import TestClient

from shortener.app import create_app

BASE_URL = "http://sho.rt"


def scripted_codes(codes: Iterable[str]) -> Callable[[], str]:
    """A Short Code generator that returns the given codes in order."""
    remaining = iter(codes)
    return lambda: next(remaining)


@pytest.fixture
def db_path(tmp_path):
    return tmp_path / "links.db"


@pytest.fixture
def make_client(db_path):
    """Build the app through its factory, with a scripted Short Code generator."""

    def _make(codes: Iterable[str] = ("Ab3xK9q",), base_url: str = BASE_URL) -> TestClient:
        app = create_app(base_url=base_url, db_path=db_path, generate_code=scripted_codes(codes))
        return TestClient(app, follow_redirects=False)

    return _make
