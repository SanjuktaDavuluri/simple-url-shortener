from collections.abc import Callable, Mapping
from pathlib import Path

from fastapi import FastAPI, HTTPException
from fastapi.responses import RedirectResponse
from pydantic import BaseModel

from shortener.codes import generate_code as generate_random_code
from shortener.store import LinkStore

DEFAULT_BASE_URL = "http://localhost:8000"
DEFAULT_DATABASE_PATH = "links.db"


class CreateLinkRequest(BaseModel):
    url: str


def create_app(base_url: str, db_path: Path, generate_code: Callable[[], str]) -> FastAPI:
    app = FastAPI(title="Simple URL Shortener")
    store = LinkStore(db_path)

    @app.post("/links", status_code=201)
    def create_link(request: CreateLinkRequest) -> dict[str, str]:
        short_code = generate_code()
        store.save(short_code, request.url)
        return {
            "short_code": short_code,
            "short_url": f"{base_url}/{short_code}",
            "long_url": request.url,
        }

    @app.get("/{short_code}")
    def follow_link(short_code: str) -> RedirectResponse:
        long_url = store.get(short_code)
        if long_url is None:
            raise HTTPException(status_code=404, detail="No Link has this Short Code.")
        return RedirectResponse(long_url, status_code=302, headers={"Cache-Control": "no-store"})

    return app


def app_from_environment(environ: Mapping[str, str]) -> FastAPI:
    """Production wiring: configuration from the environment, real Short Code generator."""
    return create_app(
        base_url=environ.get("BASE_URL", DEFAULT_BASE_URL).rstrip("/"),
        db_path=Path(environ.get("DATABASE_PATH", DEFAULT_DATABASE_PATH)),
        generate_code=generate_random_code,
    )
