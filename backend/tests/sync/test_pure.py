"""Pure parts of the sync contract (docs/features/sync_versions.md): the typed errors and their
HTTP bodies, the version checks that need no database, the request models, and the OpenAPI
surface the Android client is generated against."""

from __future__ import annotations

import json
import uuid
from types import SimpleNamespace

import pytest
from pydantic import ValidationError

from app.api.sync_errors import id_conflict_http_error, version_conflict_http_error
from app.schemas.canvas import CanvasCreate, CanvasUpdate
from app.schemas.merge import MergeRequest
from app.schemas.snippet import SnippetCreate, SnippetUpdate
from app.schemas.tag import TagCreate, TagUpdate
from app.schemas.word_bank import BankWordNew, BankWordsCreate, BankWordsDelete, BankWordTagsUpdate, BankWordUpdate
from app.services.sync.errors import IdConflictError, SyncErrorCode, VersionConflictError
from app.services.sync.versions import check_originals, conflicts, has_content
from scripts.export_openapi import SNAPSHOT_PATH


def test_error_codes():
    assert {c.value for c in SyncErrorCode} == {"version_conflict", "id_conflict"}
    assert VersionConflictError.status == 409 and IdConflictError.status == 409


def test_version_conflict_body_carries_the_rendered_current():
    exc = VersionConflictError("snippet changed", current={"n": 2})
    http = version_conflict_http_error(exc, lambda c: SimpleNamespace(model_dump=lambda mode: {"version": c["n"]}))
    assert http.status_code == 409
    assert http.detail == {"error": "version_conflict", "message": "snippet changed", "current": {"version": 2}}


def test_version_conflict_body_renders_lists():
    exc = VersionConflictError("words changed", current=[1, 2])
    http = version_conflict_http_error(exc, lambda c: SimpleNamespace(model_dump=lambda mode: {"v": c}))
    assert http.detail["current"] == [{"v": 1}, {"v": 2}]


def test_id_conflict_body():
    http = id_conflict_http_error(IdConflictError("id taken"))
    assert http.status_code == 409 and http.detail == {"error": "id_conflict", "message": "id taken"}


@pytest.mark.parametrize(("current", "base", "expected"), [(3, None, False), (3, 3, False), (3, 2, True), (1, 4, True)])
def test_conflicts(current, base, expected):
    assert conflicts(current, base) is expected


def test_has_content():
    assert has_content({"raw_text": "a"}) and has_content({"grid_data": {"lines": []}, "position_x": 1})
    assert has_content({"beats_per_line": 4})
    assert not has_content({"position_x": 1.0, "z_index": 2, "group_id": None})


def test_check_originals_lists_the_stale_ones_in_order():
    a, b, c = (SimpleNamespace(id=uuid.uuid4(), version=v) for v in (1, 2, 3))
    check_originals([a, b, c], {})
    check_originals([a, b, c], {a.id: 1, c.id: 3, uuid.uuid4(): 9})
    with pytest.raises(VersionConflictError) as caught:
        check_originals([a, b, c], {a.id: 1, b.id: 1, c.id: 1})
    assert caught.value.current == [b, c]


def test_requests_accept_the_optional_sync_fields():
    cid = uuid.uuid4()
    assert CanvasCreate(id=cid).id == cid and CanvasCreate().id is None
    assert CanvasUpdate(base_version=2).base_version == 2
    assert SnippetCreate(id=cid).id == cid
    assert SnippetUpdate(base_version=1).base_version == 1
    assert TagCreate(name="x", id=cid).id == cid and TagUpdate(base_version=3).base_version == 3
    assert BankWordUpdate(text="x", base_version=2).base_version == 2
    assert BankWordsDelete(ids=["a"], base_versions={cid: 1}).base_versions == {cid: 1}
    assert BankWordTagsUpdate(word_ids=["a"], tag_id="t", op="link", base_versions={cid: 2}).base_versions == {cid: 2}
    assert MergeRequest(snippet_ids=[uuid.uuid4(), uuid.uuid4()], mode="vertical", merged_id=cid, base_versions={cid: 1}).merged_id == cid
    words = BankWordsCreate(words=["moon", {"text": "june", "id": str(cid)}]).words
    assert words[0] == "moon" and words[1] == BankWordNew(text="june", id=cid)


@pytest.mark.parametrize("bad", [0, -1])
def test_versions_are_positive(bad):
    with pytest.raises(ValidationError):
        SnippetUpdate(base_version=bad)
    with pytest.raises(ValidationError):
        BankWordsDelete(ids=["a"], base_versions={uuid.uuid4(): bad})


def test_bad_client_ids_are_rejected():
    with pytest.raises(ValidationError):
        CanvasCreate(id="nope")
    with pytest.raises(ValidationError):
        BankWordsCreate(words=[{"text": "x", "id": "nope"}])


def spec() -> dict:
    return json.loads(SNAPSHOT_PATH.read_text(encoding="utf-8"))


@pytest.mark.parametrize("schema", ["SnippetResponse", "CanvasResponse", "CanvasDetailResponse", "TagResponse", "BankWordResponse", "TagLinkResponse", "SyncSnippet"])
def test_openapi_responses_carry_version(schema):
    component = spec()["components"]["schemas"][schema]
    assert component["properties"]["version"]["type"] == "integer"
    assert "version" in component["required"]


def test_openapi_has_the_snapshot():
    op = spec()["paths"]["/api/sync/snapshot"]["get"]
    assert op["responses"]["200"]["content"]["application/json"]["schema"] == {"$ref": "#/components/schemas/SyncSnapshotResponse"}
    snapshot = spec()["components"]["schemas"]["SyncSnapshotResponse"]
    # the exported document sorts keys; the wire order is checked in test_snapshot_db.py
    assert set(snapshot["properties"]) == {"server_time", "canvases", "placements", "snippets", "tags", "tag_presets", "word_bank"}
    assert set(snapshot["required"]) == set(snapshot["properties"])


@pytest.mark.parametrize(
    ("path", "method"),
    [
        ("/api/snippets/{snippet_id}", "delete"),
        ("/api/canvases/{canvas_id}/snippets/{snippet_id}", "delete"),
        ("/api/canvases/{canvas_id}", "delete"),
        ("/api/snippets/{snippet_id}/tags/{tag_id}", "delete"),
        ("/api/tags/{tag_id}", "delete"),
    ],
)
def test_openapi_deletes_take_base_version(path, method):
    params = {p["name"] for p in spec()["paths"][path][method].get("parameters", [])}
    assert "base_version" in params


@pytest.mark.parametrize(
    ("path", "method"),
    [
        ("/api/snippets/{snippet_id}", "put"),
        ("/api/snippets/{snippet_id}", "delete"),
        ("/api/canvases/{canvas_id}", "put"),
        ("/api/tags/{tag_id}", "put"),
        ("/api/snippets/{snippet_id}/tags", "post"),
        ("/api/word-bank/{word_id}", "put"),
        ("/api/word-bank/delete", "post"),
        ("/api/word-bank/tags", "post"),
        ("/api/canvases/{canvas_id}/merge", "post"),
        ("/api/canvases", "post"),
        ("/api/canvases/{canvas_id}/snippets", "post"),
        ("/api/tags", "post"),
        ("/api/word-bank", "post"),
    ],
)
def test_openapi_documents_409(path, method):
    assert "409" in spec()["paths"][path][method]["responses"]
