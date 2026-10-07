"""Entry point for running the app.

Run locally with: uv run fastapi dev src/shortener/main.py
"""

import os

from shortener.app import app_from_environment

app = app_from_environment(os.environ)
