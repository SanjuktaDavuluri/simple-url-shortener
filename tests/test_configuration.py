import string

from fastapi.testclient import TestClient

from shortener.app import app_from_environment

BASE62 = set(string.ascii_letters + string.digits)


def test_short_urls_use_the_configured_base_url(tmp_path):
    app = app_from_environment(
        {"BASE_URL": "https://sho.rt", "DATABASE_PATH": str(tmp_path / "links.db")}
    )

    body = TestClient(app).post("/links", json={"url": "https://example.com"}).json()

    assert body["short_url"] == f"https://sho.rt/{body['short_code']}"
    assert len(body["short_code"]) == 7 and set(body["short_code"]) <= BASE62


def test_base_url_defaults_to_localhost(tmp_path):
    app = app_from_environment({"DATABASE_PATH": str(tmp_path / "links.db")})

    body = TestClient(app).post("/links", json={"url": "https://example.com"}).json()

    assert body["short_url"].startswith("http://localhost:8000/")
