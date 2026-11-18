# Score Backend (musical mode, phase 2)

Phase 2 of musical mode on the server: a snippet stores a **score** (format v1, see
[score_format.md](./score_format.md) and the normative [`spec/score_format.md`](../../spec/score_format.md))
and a **view** flag, every content write runs one authoritative save flow that keeps the score valid
for the grid and the grid's rests (and the text's `_`) in step with the score, and the agent CLI `lcx`
reads and edits scores.

## Scope

- Migration 012: `snippets.score JSONB NULL`, `snippets.score_view BOOLEAN NOT NULL DEFAULT false`,
  `CHECK (score IS NOT NULL OR NOT score_view)`; `Snippet.score` / `Snippet.score_view` columns.
- `score` and `score_view` on every snippet response (canvas detail, create, update, merge, voice
  takes, version-conflict `current`) and on the sync snapshot's snippets.
- `POST /api/canvases/{id}/snippets` and `PUT /api/snippets/{id}` accept them; typed errors
  `invalid_score` (422), `stored_score_invalid` (500).
- The save flow (`app/services/score_save.py`) on create, update and the voice-take append; phonetics,
  card size and every other value derived from text or grid computed from the final text and grid.
- Versions: a change of score or view bumps `version` and `content_updated_at`; `base_version` checks
  apply to score-only PUTs; client-id create retries compare the stored score and view.
- Merge gives the merged snippet no score; fork copies score and view; transfer / also show share the
  content (nothing to do); text / markdown / PDF export unchanged.
- Agent CLI: compact cadence in `lcx canvas`, `lcx dump`, new `lcx score <snippet>`; plan op
  `set_score` checked locally with the vendored Python reference; SKILL.md.

## Non-scope

- Score drafts from a grid or a recording, tempo estimation, MIDI / MusicXML export
  (feat/score-draft-export-backend); rhythm fit in rhyme-suggest (feat/rhyme-rhythm-fit-backend).
- Editors, playback, the TS and Kotlin ports (client branches).
- Merging scores (a merged snippet has none), pitch, a score history.
- Rebuilding `raw_text` from a sent grid: as before, clients send the text with a grid edit; the
  server only splices lines the rest link itself rewrote.

## API

### Snippet fields

Responses (`SnippetResponse`, `SyncSnippet`), always present:

```json
"score": null | {"v": 1, "ppq": 48, "pickup": 0, "meters": [{"bar": 0, "num": 4, "den": 4}],
                 "tempos": [{"bar": 0, "bpm": 90}], "snap": "1/16",
                 "lines": [{"notes": [{"s": 0, "t": 0, "d": 12, "a": 1}]}]},
"score_view": false
```

The stored score is canonical (`score_to_json`: key order `v, ppq, pickup, meters, tempos, snap,
lines`, note `s, t, d, a` then extra keys; integers without fraction, `12.0` stored as `12`, `bpm`
as given) and always valid for the response's `grid_data`. In `SnippetResponse` the two fields come
right after `raw_text`. `score_view` is false whenever `score` is null.

Requests:

| Body | `score` | `score_view` |
|---|---|---|
| `POST /api/canvases/{id}/snippets` | omitted / null = no score; an object = validated against the new grid (sent `grid_data`, else the syllabified text) | bool, default false; stored false without a score |
| `PUT /api/snippets/{id}?canvas_id=` | omitted = keep the stored score (carried over a text or grid change, below); `null` = **remove**; an object = replace, validated against the resulting grid | omitted or null = keep; stored false without a score |

The request score is raw JSON (OpenAPI: `ScoreModel | null`), validated by `check_score` so the
reason codes and their order are the spec's (pydantic does not see inside it).

### Errors

| Status | `detail` | When |
|---|---|---|
| 422 | `{"error": "invalid_score", "reason", "line"?, "note"?}` (`ScoreInvalid.as_dict()`, reasons in spec section 4) | a sent score is not valid for the resulting grid; or a rest-only grid edit would push notes past `MAX_TICKS` (`exceeds_max_ticks`, the edit is refused) |
| 500 | `{"error": "stored_score_invalid", "message"}` | the stored score no longer fits the stored grid (a write bypassed the flow; logged as `score.stored_invalid`) |

Nothing is written on any of them (layout fields of the same PUT included). 422 for a PUT or create
documents `PlacementErrorResponse | ScoreInvalidResponse` in OpenAPI.

**Text and grid disagree** (the rest link rewrote a grid line the text does not have, i.e. the sent
grid has more non-blank lines than the text, `ScoreTextMismatch` from `splice_line_texts`): not an
error. The text is stored as sent, the grid is still linked, and a warning
`score.text_mismatch grid_line=<i> text_lines=<n> action=keep_text` is logged; the web client does the
same locally. Pydantic's
own 422 (`{"detail": [...]}`) still covers malformed grids and other fields.

## Save flow

`app/services/score_save.py`, pure:

```
stored = ScoredContent(raw_text, grid, score, score_view)         # what the row holds
edit   = score_edit(fields_set, raw)  -> KeepScore | RemoveScore | SetScore(raw)

saved_content(stored, raw_text=T, grid=G, edit, view):            # T, G: the edit's text and grid
  SetScore(raw)  : score = check_score(raw, G)                     # ScoreInvalid -> 422
  RemoveScore    : score = None
  KeepScore      : no stored score -> None
                   old = check_score(stored.score, stored.grid)    # StoredScoreInvalid -> 500
                   G == stored.grid           -> score = old
                   is_rest_only_edit(old, G)  -> (score, G) = apply_grid_rest_edit(old, stored.grid, G)
                                                 # moves notes; exceeds_max_ticks -> 422
                   otherwise                  -> score = reconcile(old, stored.grid, G)
  score != None  : G' = apply_rest_link(score, G)
                   T' = splice_line_texts(T, changed_line_texts(G, G'))   # mismatch: T' = T, warn
  view' = (view if sent else stored.score_view) and score != None
  -> ScoredContent(T', G', score_to_json(score) | None, view')

new_content(raw_text, grid, raw_score, view)                       # create: SetScore or none
```

A sent score always wins over a rest-only edit of a sent grid (it is validated against that grid and
then linked). The rest link only rewrites cells between two consecutive placed syllables and rebuilds
`raw_text` only for those lines (from the cells: a line whose cells lack `word` is respelled from its
syllables, e.g. `I go now` -> `i _ go now`; the same rule the clients' `rebuildRawText` applies).

### Where it runs

- **PUT** (`app/api/snippets.py` -> `snippet_service.update_snippet`): the route dumps the body
  without `score` / `score_view`, builds the `ScoreEdit` from `model_fields_set` (explicit null =
  remove), checks `base_version` when a content field or a score field is set, re-syllabifies sent
  text without a grid and computes phonetics and size as before, then passes `score_edit` and
  `score_view` to the service only when the client sent them. The service decides
  `runs_score_flow` (text, grid or a score field sent), loads the placed snippet `SELECT ... FOR
  UPDATE` (with `populate_existing`, so the locked row is the truth) and calls `scored_changes`:
  `saved_content` on the sent / derived text and grid (else the stored ones); when the flow rewrote
  either, the changes get the final text and grid with phonetics (stored overrides unless the client
  sent text, as the route does) and card size recomputed from them. `apply_update` then sets the
  changes, the score and the view, and bumps `version` and `content_updated_at` once when what will
  be stored differs: the sent text and grid are compared **after** the flow (so a client resending
  its already linked grid, text, score and view, as web and Android do on every grid save of a
  scored snippet, bumps nothing), plus the score and view.
- **Create** (`POST /api/canvases/{id}/snippets`): `new_grid` (sent grid or syllabified text),
  `new_content`, then `content_for(final text, final grid)` for phonetics and size; the row stores the
  final text, grid, score and view. With a client id the retry comparison uses the stored (linked)
  text, the score and the view.
- **Voice-take append** (`recording_service.append_with_recording`): the appended text and grid go
  through `saved_content(..., KeepScore())` (the line count changes, so `reconcile` pairs lines by
  LCS: existing lines keep their notes, new lines get none); phonetics and size follow the final
  content; a `ScoreError` there is logged and raised as `VoicePersistError` (500, nothing changed).
- **Merge** builds a new snippet: no score (column default). **Fork** (`canvas_service.fork_copy`)
  copies `score` and `score_view` with the grid they fit. **Transfer / also show** add placements to
  the same row, so every canvas shows the same score. **Export** reads only `raw_text` (display text).
- **Snapshot** (`GET /api/sync/snapshot`) and every `SnippetResponse` builder read the columns;
  `api/canvases.score_model` turns the stored JSON into `ScoreModel`.

### Android offline client

There is no sync push endpoint: the offline outbox replays the same `POST` / `PUT` calls, so it gets
the flow as is. A queued PUT carries `base_version`; a score-only PUT (`score` and / or `score_view`)
is checked like any content PUT (409 `version_conflict` with `current`). A queued text-only PUT on a
snippet with a score is reconciled by the server, which is what the local port does too.

## Migration 012

`backend/alembic/versions/012_snippet_score.py`. Upgrade adds `score JSONB NULL`, `score_view BOOLEAN
NOT NULL DEFAULT false` and the check constraint `ck_snippets_score_view_needs_score`; existing rows
get no score and the grid view. Downgrade drops the constraint and both columns (scores are lost, rows
kept). The model's `score` column is `JSONB(none_as_null=True)`: Python `None` is SQL NULL, never the
JSON value `null`.

## Agent CLI

`lcx` is stdlib-only and installed by symlink, so it cannot import `app.services.score` (its
`__init__` imports the pydantic wire model). `tools/agent/scripts/vendor_score_ref.py` copies the
modules lcx needs verbatim into `tools/agent/lcxlib/score_ref/` (constants, errors, types, codec,
validate, grid_view, timing, rest_link, text and `rest_marks`), rewriting only the import prefixes,
plus a small `__init__`. `tools/agent/tests/test_score.py` fails when the copy is out of date and runs
the shared `validate`, `rest_link`, `text_splice` and `bar_at_tick` vectors through it. After changing
the backend reference, run `python tools/agent/scripts/vendor_score_ref.py`.

- `lcxlib/cadence.py` renders a score as one header line (`4/4, 90 bpm, pickup 0.5 beat, snap 1/16;
  bar 5: 3/4; bar 9: 120 bpm`) and one line per grid line: `syllable@beat~length` grouped by
  `bar N` (1-based; `pickup` counted as the tail of a full bar), beats and lengths in beat units of
  the meter in force (exact: `1.5`, `1+1/3`), accents `>` / `>>`, `(unplaced: ...)`, `(rests only)`,
  `(no notes)`. A score that does not fit its grid renders as one line with the reason.
- `lcx canvas` (compact) adds `cadence` (null without a score) and `score_view`; `lcx dump` adds
  `cadence` next to each snippet's raw `score`; `lcx score <snippet>` prints `{id, version, text,
  score_view, score, cadence, lines: [{text, syllables}]}` (syllables by ordinal, rests skipped).
- Plan op `set_score` `{snippet_id, score: object | null, score_view?: bool}`: one `edit_text` or
  `set_score` per snippet per plan; on a canvas at that point; `score` validated with the vendored
  `check_score` against the snapshot grid (`invalid_score` issue with reason, line, note); the
  canonical score and the linked text are computed locally; same score and view -> no-op; details
  show the cadence before (`-`) and after (`+`), the view change and the text change from the rest
  link (a text / grid mismatch keeps the text, like the server). Apply: `PUT /api/snippets/{id}?canvas_id= {score, score_view?, base_version}` with the latest
  version this apply has seen. `edit_text` of a scored snippet adds a detail line saying the server
  reconciles the score.

## Files

| File | Role | Key exports |
|---|---|---|
| `backend/alembic/versions/012_snippet_score.py` | Migration 012 | `CHECK` |
| `backend/app/models/snippet.py` | `score` (JSONB, none_as_null), `score_view`, the check constraint, `score_view=False` on construction | `Snippet` |
| `backend/app/services/score_save.py` | The pure save flow | `KeepScore`, `RemoveScore`, `SetScore`, `ScoreEdit`, `score_edit`, `ScoredContent`, `StoredScoreInvalid`, `saved_content`, `new_content` |
| `backend/app/services/snippet_service.py` | PUT wiring: lock, flow, derived values, bump | `update_snippet(..., score_edit, score_view)`, `runs_score_flow`, `scored_changes`, `apply_update(placed, content_edits, changes, scored)` |
| `backend/app/services/snippet_content.py` | Grid for a new snippet | `new_grid`, `derive_new_content`, `content_for` |
| `backend/app/services/recording_service.py` | Voice append through the flow | `append_with_recording` |
| `backend/app/services/canvas_service.py` | Fork copies score and view | `fork_copy` |
| `backend/app/services/placements/queries.py` | Locked loads refresh the row (`populate_existing`) | `placed_snippets`, `placed_snippet` |
| `backend/app/services/placements/view.py` | `PlacedSnippet.score`, `.score_view` | `PlacedSnippet` |
| `backend/app/services/sync/client_ids.py` | Create retries compare score and view | `SnippetCreateContent` |
| `backend/app/schemas/score.py` | Request type and error bodies | `ScoreInput`, `ScoreInvalidBody`, `ScoreInvalidResponse`, `ScoreErrorBody`, `ScoreErrorResponse` |
| `backend/app/schemas/snippet.py` | `score` / `score_view` on create, update, response | `SnippetCreate`, `SnippetUpdate`, `SnippetResponse` |
| `backend/app/schemas/sync.py` | Snapshot snippet fields | `SyncSnippet` |
| `backend/app/api/score_errors.py` | HTTP mapping and OpenAPI responses | `score_http_error`, `snippet_write_errors` |
| `backend/app/api/snippets.py` | Create and PUT | `create_snippet`, `update_snippet` |
| `backend/app/api/canvases.py`, `sync.py` | Response builders | `score_model`, `_snippet_response`, `_snippet` |
| `backend/tests/score_backend/` | Migration test, pure flow tests, API DB tests | |
| `docs/api/openapi.json` | Regenerated | |
| `tools/agent/scripts/vendor_score_ref.py` | Vendors the reference into lcx | `render`, `MODULES` |
| `tools/agent/lcxlib/score_ref/` | Generated copy (do not edit) | `check_score`, `validate`, `apply_rest_link`, `splice_line_texts`, `changed_line_texts`, `bar_at_tick`, `beat_unit`, `score_to_json` |
| `tools/agent/lcxlib/cadence.py` | Compact rendering | `cadence`, `header`, `line_cadence` |
| `tools/agent/lcxlib/plan.py`, `checker.py`, `cli.py`, `errors.py` | `SetScore` op, its check / runner, reads, `IssueCode.INVALID_SCORE` | |
| `tools/agent/skill/lyrics-canvas/SKILL.md` | Reading cadence, editing scores | |
| `tools/agent/tests/test_score.py`, `fake_server.py` | lcx tests; the fake applies the flow for score-only PUTs | |

## Invariants

- A stored score is canonical and valid for the stored grid (`check_score(score, grid_data)` never
  fails), and `apply_rest_link(score, grid_data) == grid_data`.
- `score_view` is false whenever `score` is NULL (database check constraint).
- Every write of text or grid on an existing snippet (PUT, voice append) runs the flow on the locked
  row; create runs `new_content`. Phonetics and card size are computed from the final text and grid.
- Score or view changes bump `version` and `content_updated_at` exactly once per request; resending
  the stored score / view (alone or with the stored grid and text), `score: null` without a score, or
  `score_view: true` without a score bump nothing. The comparison is made after the save flow.
- A refused request (422 / 500 / 409) writes nothing.
- `tools/agent/lcxlib/score_ref/` equals the vendoring script's output.

## Decisions

- **Explicit `score: null` removes; omitted keeps.** `score_view: null` is "not sent" (a bool has no
  removal meaning).
- **`score_view: true` without a score is accepted and stored false** (contract: "the server forces
  false"), not a 422.
- **Text / grid mismatch keeps the sent text** with a structured warning (no 4xx), matching the web
  client; **500 `stored_score_invalid`** for a stored score that no longer fits (never expected).
- **No-bump comparison after the flow**: clients send the full final score with grid, text and view on
  every grid save; equal final content is not a content change.
- **The flow runs in the service on the locked row**, not in the route, so the stored score, grid and
  text read for reconcile are the ones written over (concurrent PUTs serialize) and the existing
  route-level fakes keep working; the route still derives grid and phonetics first and the service
  recomputes them only when the flow rewrote text or grid.
- **Locked loads use `populate_existing`** so a snippet the session read before the lock is refreshed.
- **Grid-only PUTs never rebuild the text**: only lines the rest link rewrote are spliced (spec
  section 13); clients send their rebuilt text with grid edits, as before.
- **Voice append reconciles** (lines added: LCS pairing keeps the existing lines' notes); a failure
  there is a 500 `VoicePersistError`, not a 422, since the client sent no score.
- **lcx vendors the reference** (generated verbatim copy plus a currency test) rather than importing
  the backend package (pydantic) or hand-porting (drift); see "Agent CLI".
- **`set_score` has no `base_version` field** (contract shape); the apply sends the snapshot version
  (updated by earlier steps), so a change on the server between check and apply is a 409.
- **One `edit_text` or `set_score` per snippet per plan**: a score cannot be checked locally against a
  text edit's new grid (syllabification is server-side).
