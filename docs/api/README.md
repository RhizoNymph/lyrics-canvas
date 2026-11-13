# API snapshot

`openapi.json` is the OpenAPI document of the FastAPI backend (`app.main.app.openapi()`), committed as
the API contract. The Android client's `:core:model` checks its DTOs against it, and reviewers see API
changes as a diff of this file.

- Written by `backend/scripts/export_openapi.py`: sorted keys, 2-space indent, trailing newline.
- `backend/tests/test_openapi_snapshot.py` fails when the committed file differs from the live schema.

## Regenerate

After changing a route, a request/response model or anything else that shows up in the schema, from
`backend/`:

```
uv run python -m scripts.export_openapi
```

then commit `docs/api/openapi.json` with the change.

## Notes on the schema

- Response models mark every field they always emit as `required` (fields that may be null are
  `anyOf [..., null]`).
- Grid models (`GridDataModel`, `GridLineModel`, `GridCellModel`) allow extra keys and emit only the keys
  that are present, so optional cell fields are absent rather than null (a rest is `{"rest": true}`).
- Datetimes are strings in Python `str(datetime)` form (`2026-09-30 12:00:00.123456+00:00`), not ISO `T`.
- `RhymeGridCellModel` / `RhymeGridLineModel` are the rhyme-suggest variants: they always emit every
  field, unset ones as null.
- Offline sync ([sync_versions.md](../features/sync_versions.md)): `version` is required on the
  snippet, canvas, tag, word and tag-link responses; a route whose 409 can be more than one body
  (e.g. `tag_name_taken` or `version_conflict`) declares them as `anyOf`; `*VersionConflictResponse`
  models type `current` per entity (a list for the bulk word endpoints and merge).
