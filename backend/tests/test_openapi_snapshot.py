"""The committed docs/api/openapi.json must match the live schema."""

from scripts.export_openapi import SNAPSHOT_PATH, render_openapi

REGENERATE = "re-run `uv run python -m scripts.export_openapi` from backend/ and commit docs/api/openapi.json"


def test_snapshot_exists():
    assert SNAPSHOT_PATH.is_file(), f"{SNAPSHOT_PATH} is missing; {REGENERATE}"


def test_snapshot_matches_live_schema():
    committed = SNAPSHOT_PATH.read_text(encoding="utf-8")
    assert committed == render_openapi(), f"OpenAPI snapshot is out of date; {REGENERATE}"


def test_merge_endpoint_is_in_the_snapshot():
    import json

    spec = json.loads(SNAPSHOT_PATH.read_text(encoding="utf-8"))
    op = spec["paths"]["/api/canvases/{canvas_id}/merge"]["post"]
    assert op["responses"]["200"]["content"]["application/json"]["schema"] == {"$ref": "#/components/schemas/MergeResponse"}
