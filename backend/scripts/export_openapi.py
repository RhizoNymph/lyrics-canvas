"""Write the OpenAPI document of the FastAPI app to docs/api/openapi.json.

Run from backend/:  uv run python -m scripts.export_openapi
The committed snapshot is the API contract the Android client is checked against;
tests/test_openapi_snapshot.py fails whenever it drifts from the live schema.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

SNAPSHOT_PATH = Path(__file__).resolve().parents[2] / "docs" / "api" / "openapi.json"


def render_openapi() -> str:
    """The live schema as sorted-key, 2-space-indented JSON with a trailing newline."""
    from app.main import app

    return json.dumps(app.openapi(), sort_keys=True, indent=2, ensure_ascii=False) + "\n"


def main() -> int:
    SNAPSHOT_PATH.parent.mkdir(parents=True, exist_ok=True)
    SNAPSHOT_PATH.write_text(render_openapi(), encoding="utf-8")
    print(f"wrote {SNAPSHOT_PATH}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
