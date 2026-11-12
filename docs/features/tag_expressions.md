# Tag Expressions

User-wide tags, a small boolean language over tags and snippet types, saved expression presets, and
a `tag_filter` on rhyme suggestions. This page is the canonical spec for the backend and for both
clients (web and Android implement the same language and follow the API shapes below).

## Scope

- **User-wide tags.** One tag list per user, shared by all of that user's canvases. Names are unique
  per user ignoring case. Migration 005 merges the old per-canvas tags.
- **Expression language** (section 1): lexer, parser, normalizer (formatter), evaluator and rename,
  with code point positions and typed errors. Python implementation in
  `backend/app/services/tag_expr/`; web (TypeScript) and Android (`:core:logic`, Kotlin) implement the
  same spec.
- **Conformance vectors**: `spec/tag_expression_vectors.json` at the repo root (version 2 since the
  word bank added typeless eval subjects). It is byte-identical in every branch, and every
  implementation's tests read that exact file.
- **Presets**: named, server-stored, normalized expressions per user (`/api/tag-presets`).
- **Rhyme filter**: `RhymeSuggestRequest.tag_filter` restricts the **notes** source to matching
  snippets.
- **HTTP API** for tags, snippet links and presets, with typed error codes.

## Non-scope

- Server-side filtering of canvas/list views. Clients load all snippets, `GET /api/tags` and
  `GET /api/tag-presets`, and evaluate expressions locally.
- The dictionary rhyme source. It is never filtered.
- Sharing tags or presets between users. Shared canvases still embed the owner's tags on each
  snippet, but no tag list comes with them.
- Copying tags on fork (`POST /api/canvases/{id}/fork` copies the words only, as before).
- Splitting merged tags on downgrade back into the original per-canvas names (see Data model).

## 1. Expression language

### Lexing

Lexing covers the whole input before parsing starts, so lexer errors win over parse errors.

- Input longer than **500 code points**: `too_long` at position 500.
- Whitespace (Unicode `isspace`) separates tokens.
- Operator characters: `(` `)` `&` `|` `!`.
- `"…"` is a quoted tag name. The only escapes are `\"` and `\\`; any other backslash is
  `invalid_escape` at the backslash. A missing closing quote is `unterminated_string` at the opening
  quote. An empty or whitespace-only name is `empty_name` at the opening quote. Quoted names are taken
  verbatim: no trimming, and no keyword or type check.
- A `\` outside quotes is `unexpected_character`.
- A **word** is a maximal run of characters that are not whitespace, not operator characters, not `"`
  and not `\`.
  - Case-insensitive `and` / `or` / `not` are the operators `&` / `|` / `!`.
  - A word whose case-insensitive prefix is `type:` is a type term. The rest, lowercased, must be one
    of `verse chorus bridge hook freestyle`, else `unknown_type` at the word's start (bare `type:`
    included).
  - Any other word is a bare tag name, with its case kept.
- All positions are **Unicode code point** offsets. JS and Kotlin must not use UTF-16 indices; the
  vectors include emoji cases.

### Grammar (precedence `!` > `&` > `|`)

```
or      := and ( "|" and )*
and     := unary ( "&"? unary )*        -- juxtaposition is AND: `love night` == `love & night`
unary   := "!" unary | primary
primary := "(" or ")" | TAG | TYPE
```

- Empty or whitespace-only input is valid and matches everything. Its normalized form is `""`.
- Errors:
  - `unexpected_token`: at the token.
  - `unexpected_end`: at the input length in code points.
  - `unclosed_paren`: at the `(`.
  - `empty_group`: a `(` directly followed by `)`, at the `(`.
  - `too_deep`: at the `(` that would take paren nesting past 32. `!` does not count toward depth.

### Error codes

| Code | Position |
|---|---|
| `too_long` | 500 |
| `unexpected_character` | the `\` outside quotes |
| `unterminated_string` | the opening `"` |
| `invalid_escape` | the bad `\` |
| `empty_name` | the opening `"` |
| `unknown_type` | the start of the `type:` word |
| `unexpected_token` | the token |
| `unexpected_end` | input length (code points) |
| `unclosed_paren` | the `(` |
| `empty_group` | the `(` |
| `too_deep` | the 33rd nested `(` |

An error carries `code`, `position` and a human message. The message text is not part of the contract.

### AST, normalization, evaluation, rename

- **AST**: `Or(children ≥ 2) | And(children ≥ 2) | Not(child) | Tag(name) | Type(value)`. Nested
  same-kind And/Or are flattened, and parentheses leave no node. Nothing else is simplified (`!!a`
  stays). The empty expression has no node (Python: `None`).
- **Format** (normalized text):
  - Or joins its children with `" | "`.
  - And joins with `" & "` and wraps Or children in parentheses.
  - Not is `!` + child, wrapping And/Or children in parentheses.
  - Type is `type:<value>`.
  - Tag is bare when it is a valid bare word that is not a keyword and does not start with `type:`
    (case-insensitive). Otherwise it is quoted, with `\` and `"` escaped.
  - Formatting is idempotent: `format(parse(format(x))) == format(x)`.
- **Evaluate** against `{type, tagNames}`:
  - A Tag matches when some tag name equals it under simple lowercasing (Python `str.lower`, JS
    `toLowerCase`, Kotlin `lowercase(Locale.ROOT)`).
  - A Type matches the snippet type exactly. The type may be null (a typeless subject: a word bank
    word, see [word_bank.md](./word_bank.md)); then every Type term is false, so `!type:verse` is
    true. Vectors version 2 adds eval cases with `"type": null`.
  - An unknown tag name matches nothing. It is not an error.
- **Rename**(expr, from, to): every Tag whose lowercase equals lowercase(from) becomes Tag(to), and
  the result is formatted. Type terms are untouched.

Examples: `love night` → `love & night`; `(love & night) | dark` → `love & night | dark`;
`a or b and not c` → `a | b & !c`; `"love"` → `love`; `"and"` stays quoted; `TYPE:Chorus` →
`type:chorus`. The vectors file has the full set.

## 2. Data model (migration 005)

`backend/alembic/versions/005_user_wide_tags.py`, models `backend/app/models/tag.py` and
`backend/app/models/tag_preset.py`.

```
users 1──N tags        (user_id FK users ON DELETE CASCADE, NOT NULL)
users 1──N tag_presets (user_id FK users ON DELETE CASCADE, NOT NULL)
snippets N──M tags via snippet_tags (both FKs ON DELETE CASCADE, unchanged)
```

| `tags` column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `user_id` | uuid FK users, cascade, not null | backfilled from the canvas owner |
| `name` | varchar(100) | unique per user ignoring case: index `uq_tags_user_lower_name (user_id, lower(name))` |
| `color` | varchar(7) | `#rrggbb`, default `#6366f1`; stored lowercase by the API |
| `created_at` | timestamptz, server default now(), not null | |

| `tag_presets` column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `user_id` | uuid FK users, cascade, not null | |
| `name` | varchar(100) | unique per user ignoring case: index `uq_tag_presets_user_lower_name` |
| `expression` | text | always stored **normalized**, non-empty, at most 500 code points |
| `created_at`, `updated_at` | timestamptz, server default now(), not null | `updated_at` bumped on every update and on rename rewrites |

**Upgrade.**

1. Add `user_id` (nullable for now) and `created_at` to `tags`, and backfill `user_id` from
   `canvases.owner_id`.
2. Merge duplicates per `(user_id, lower(name))`. The tag with the smallest `id::text` survives, and
   its name and color win. Every `snippet_tags` row of a loser is copied to the survivor
   (`ON CONFLICT DO NOTHING`), then the losers are deleted, and their own links cascade with them.
3. Make `user_id` NOT NULL with the cascade FK (`fk_tags_user_id`), drop `uq_tag_canvas_name` and
   `canvas_id`, and create the unique lower-name index.
4. Create `tag_presets`.

The upgrade works on an empty database and on real data.

**Downgrade.**

1. Drop `tag_presets`.
2. Re-add `canvas_id` and split each tag into one copy per canvas it is used on. The first canvas by
   `id::text` keeps the original id, the others get new ids, and links are repointed to the copy in
   the snippet's own canvas.
3. An unused tag goes to the user's oldest canvas. It is deleted when the user has no canvas.
4. Restore `uq_tag_canvas_name` and the canvas FK, and drop `user_id` / `created_at`.

Names merged on the way up stay merged.

Like the earlier migrations: startup `create_all` does not alter existing tables, so an existing
database must run `alembic upgrade head` before the new backend starts. The Helm init container
already does this.

### Validation rules (API)

- **Tag names**: trimmed, 1–100 code points, no control characters (Unicode category `Cc`), else
  `422 invalid_tag_name`. Quotes, operators, spaces, `type:` prefixes and keywords are all allowed,
  because the formatter quotes such names.
- **Colors**: `#rrggbb` with hex digits in any case, stored lowercase, else `422 invalid_color`.
- **Preset names**: same rule as tag names, else `422 invalid_preset_name`.
- **Preset expressions**: parsed and normalized. A parse error is
  `422 invalid_tag_expression` + `expression_error`. An empty expression is `422 empty_preset`. A
  normalized form longer than 500 code points is `invalid_tag_expression` with
  `expression_error.code = too_long` at 500 (normalizing can grow a text, e.g. `a b` → `a & b`).

## 3. HTTP API

Bearer auth on everything, all owner-scoped: another user's tag, snippet or preset is reported as not
found.

### Error envelope

```jsonc
// HTTPException detail
{"detail": {"error": "<code>", "message": "<human text>"}}
// invalid expressions (presets and rhyme tag_filter) add expression_error
{"detail": {
  "error": "invalid_tag_expression",
  "message": "invalid tag expression: the expression ends too early",
  "expression_error": {"code": "unexpected_end", "position": 8, "message": "the expression ends too early"}
}}
```

`expression_error` appears only on `invalid_tag_expression`. The OpenAPI models are `TagErrorResponse`
/ `TagErrorBody` / `ExpressionErrorBody`. Malformed path/query UUIDs and wrong JSON types still get
FastAPI's standard 422 (`detail` is a list).

| Code | Status | When |
|---|---|---|
| `tag_not_found` | 404 | tag unknown or not the user's |
| `snippet_not_found` | 404 | snippet unknown or not the user's (link/unlink) |
| `tag_name_taken` | 409 | another tag of the user has the name ignoring case |
| `invalid_tag_name` | 422 | see validation rules |
| `invalid_color` | 422 | not `#rrggbb` |
| `preset_not_found` | 404 | preset unknown or not the user's |
| `preset_name_taken` | 409 | another preset of the user has the name ignoring case |
| `invalid_preset_name` | 422 | see validation rules |
| `empty_preset` | 422 | the expression is empty or whitespace-only |
| `invalid_tag_expression` | 422 | the expression does not parse (or normalizes past 500 code points); carries `expression_error` |
| `preset_expression_too_long` | 409 | a tag rename would push one of the user's presets past 500 code points; nothing changed |

### Shapes

```jsonc
// TagResponse (GET /api/tags, POST /api/tags, PUT /api/tags/{id})
{"id": "uuid", "name": "late night", "color": "#6366f1", "snippet_count": 3, "word_count": 2, "created_at": "2026-11-03 10:00:00.123456+00:00", "version": 1}
// word_count (word bank words carrying the tag) was added by the word bank (word_bank.md);
// version (+1 per name or color change) by offline sync (sync_versions.md)

// TagLinkResponse (link and unlink): the snippet's version after the change
{"ok": true, "version": 4}

// SnippetTagResponse: embedded in every SnippetResponse.tags (canvas detail, snippet writes, merge, voice takes)
{"id": "uuid", "name": "late night", "color": "#6366f1"}

// TagPresetResponse
{"id": "uuid", "name": "Dark hooks", "expression": "type:hook & (dark | night)", "created_at": "…", "updated_at": "…"}
```

Datetimes use the API's existing `str(datetime)` form (`YYYY-MM-DD HH:MM:SS[.ffffff]+00:00`).

### Tags

| Method | Path | Body | Response | Errors |
|---|---|---|---|---|
| GET | `/api/tags` | | `200 TagResponse[]` sorted by `lower(name)`, then id | |
| POST | `/api/tags` | `{id?, name, color?="#6366f1"}` | `201 TagResponse` | 409 `tag_name_taken` / `id_conflict`, 422 `invalid_tag_name` / `invalid_color` |
| PUT | `/api/tags/{tag_id}` | `{name?, color?, base_version?}` (null or absent = unchanged) | `200 TagResponse` | 404 `tag_not_found`, 409 `tag_name_taken` / `preset_expression_too_long` / `version_conflict`, 422 `invalid_tag_name` / `invalid_color` |
| DELETE | `/api/tags/{tag_id}?base_version=` | | `204` | 404 `tag_not_found`, 409 `version_conflict` |
| POST | `/api/snippets/{snippet_id}/tags?tag_id=…` | optional `{base_version?}` (the snippet's) | `200 {"ok": true, "version": n}`; linking twice is a no-op | 404 `snippet_not_found` / `tag_not_found`, 409 `version_conflict` |
| DELETE | `/api/snippets/{snippet_id}/tags/{tag_id}?base_version=` | | `200 {"ok": true, "version": n}` (was 204); unlinking an absent link is a no-op | 404 `snippet_not_found` / `tag_not_found`, 409 `version_conflict` |

`id`, `base_version`, `version` and the two 409 codes are the offline-sync contract
([sync_versions.md](./sync_versions.md)): a tag's `version` goes up on a name or color change; a link
or unlink that changes a snippet's tags bumps the **snippet's** version, which the link response
returns (so unlink now answers 200 with that body instead of 204). Deleting or renaming a tag bumps
no snippet or word.

- A rename (any change of the name string, a case-only change included) rewrites every preset of the
  user through Rename(expr, old, new) in the **same transaction**. If any rewritten preset would
  exceed 500 code points, the whole update is rolled back (`409 preset_expression_too_long`).
- Deleting a tag removes its links. Presets keep the now-unknown name, which matches nothing.
- Deleting a canvas leaves the user's tags alone.
- **Removed:** `POST /api/canvases/{id}/tags`, and the `tags` list on `CanvasDetailResponse`.
  Clients load `GET /api/tags`.

### Presets

| Method | Path | Body | Response | Errors |
|---|---|---|---|---|
| GET | `/api/tag-presets` | | `200 TagPresetResponse[]` sorted by `lower(name)`, then id | |
| POST | `/api/tag-presets` | `{name, expression}` | `201 TagPresetResponse` (expression normalized) | 409 `preset_name_taken`, 422 `invalid_preset_name` / `empty_preset` / `invalid_tag_expression` |
| PUT | `/api/tag-presets/{preset_id}` | `{name?, expression?}` | `200 TagPresetResponse` | the create errors plus 404 `preset_not_found` |
| DELETE | `/api/tag-presets/{preset_id}` | | `204` | 404 `preset_not_found` |

### Rhyme suggest

`RhymeSuggestRequest.tag_filter: str | None = None`. When it is set and non-empty after the lexer
skips whitespace, the **notes** source only considers the user's snippets whose
`{snippet_type, tag names}` match. Anchors (including snippet anchors) are not filtered, and neither
is the dictionary source. The context-tag ranking bonus is unchanged. An invalid filter is
`422 invalid_tag_expression` with `expression_error`. It is checked before the canvas lookup, so it
wins over a 404.

## Data / control flow

```
POST /api/tags {name, color}
  api/tags.py: tag_name(raw) / tag_color(raw)        InvalidTagNameError / InvalidColorError -> 422
  tag_service.create_tag(db, owner, TagName, TagColor)
    lower-name lookup -> TagNameTakenError -> 409
    insert, commit (IntegrityError on the unique index -> TagNameTakenError)
    -> TagSummary(id, name, color, snippet_count, created_at, word_count) -> TagResponse

PUT /api/tags/{id} {name?, color?}
  tag_service.update_tag
    _owned_tag (Tag.user_id == owner)                 TagNotFoundError -> 404
    name changed: lower-name clash with another tag   TagNameTakenError -> 409
                  _rewrite_presets: SELECT presets FOR UPDATE,
                    rename_tag(expression, old, new) per preset (unchanged ones skipped)
                    > 500 code points -> rollback, PresetExpressionTooLongError -> 409
    one commit: tag + rewritten presets

POST /api/tag-presets {name, expression}
  preset_name(raw), preset_expression(raw) -> normalize(parse(raw))
    TagExpressionError -> InvalidTagExpressionError(expression_error) -> 422
    "" -> EmptyPresetError -> 422
  tag_preset_service.create_preset -> PresetNameTakenError -> 409

POST /api/nlp/rhyme-suggest {…, tag_filter}
  rhyme_suggest_service.suggest
    parse(tag_filter)                                 TagExpressionError -> api/tag_errors.expression_http_error -> 422
    notes: repo.user_snippets -> SnippetRecord(snippet_type, tag_names, …)
           keep matches(expr, SnippetFacts.of(type, tag_names)) -> note_units -> NotesSource
    dictionary: unchanged

Clients (web, Android)
  GET /api/tags + GET /api/tag-presets + canvas detail (snippets embed SnippetTagResponse)
  evaluate expressions locally against {snippet_type, tags[].name}
```

Merge is unaffected: `merge_service.union_tag_ids` unions the sources' tag ids, and because tags are
user-wide, the same tag on two merged snippets is one id and one link. `tests/tags/test_tags_db.py`
covers this.

## Files

| File | Role / key exports |
|---|---|
| `spec/tag_expression_vectors.json` | Shared conformance vectors (`parse_ok`, `parse_error`, `eval`, `rename`; segments with `{"repeat", "times"}`) |
| `backend/app/services/tag_expr/__init__.py` | Public API: `parse`, `normalize`, `format_expression`, `format_tag_name`, `matches`, `SnippetFacts`, `rename_in`, `rename_tag`, node types, `TagExpressionError`, `TagExprErrorCode`, `MAX_LENGTH`, `MAX_DEPTH` |
| `backend/app/services/tag_expr/errors.py` | `TagExprErrorCode` (StrEnum), `TagExpressionError(code, position, message)`, `.as_dict()` |
| `backend/app/services/tag_expr/lexer.py` | `tokenize`, `Op`, `OpToken` / `TagToken` / `TypeToken`, `is_word_char`, `KEYWORDS`, `MAX_LENGTH` |
| `backend/app/services/tag_expr/ast.py` | Frozen dataclasses `Tag`, `Type`, `Not`, `And`, `Or` (And/Or reject < 2 children, Tag rejects blank names), `Expr` |
| `backend/app/services/tag_expr/parser.py` | `parse(text) -> Expr \| None`, `MAX_DEPTH` |
| `backend/app/services/tag_expr/format.py` | `format_expression`, `format_tag_name`, `is_bare_name` |
| `backend/app/services/tag_expr/evaluate.py` | `SnippetFacts.of(type, names)`, `matches(expr, facts)` |
| `backend/app/services/tag_expr/rename.py` | `rename_in(expr, old, new)`, `rename_tag(text, old, new)` |
| `backend/app/services/tag_errors.py` | `TagErrorCode`, `TagServiceError` (code, status, message) and one subclass per code; `InvalidTagExpressionError.expression_error` |
| `backend/app/services/tag_values.py` | `tag_name`, `preset_name`, `tag_color`, `preset_expression` -> `TagName`, `PresetName`, `TagColor`, `PresetExpression` (NewTypes) |
| `backend/app/services/tag_service.py` | `TagSummary`; `list_tags`, `create_tag`, `update_tag` (rename rewrites presets), `delete_tag`, `add_tag_to_snippet`, `remove_tag_from_snippet` |
| `backend/app/services/tag_preset_service.py` | `list_presets`, `create_preset`, `update_preset`, `delete_preset` |
| `backend/app/api/tags.py` | `/api/tags` and the snippet link routes |
| `backend/app/api/tag_presets.py` | `/api/tag-presets` routes |
| `backend/app/api/tag_errors.py` | `tag_http_error`, `expression_http_error`, `tag_errors(...)` (OpenAPI responses), `owner_id` |
| `backend/app/schemas/tag.py` | `TagCreate`, `TagUpdate`, `TagResponse`, `SnippetTagResponse`, `TagLinkResponse`, `TagPresetCreate`, `TagPresetUpdate`, `TagPresetResponse`, `TagErrorResponse`, `TagErrorBody`, `ExpressionErrorBody` |
| `backend/app/models/tag.py` | `Tag` (user_id, name, color, created_at), `snippet_tags`, `DEFAULT_TAG_COLOR` |
| `backend/app/models/tag_preset.py` | `TagPreset` |
| `backend/alembic/versions/005_user_wide_tags.py` | The migration above |
| `backend/app/schemas/rhyme.py` | `RhymeSuggestRequest.tag_filter` |
| `backend/app/services/rhyme_suggest_service.py` | `SnippetRecord.snippet_type` / `.tag_names`, `matches_filter`, filtering in `suggest` |
| `backend/app/api/nlp.py` | Maps `TagExpressionError` on rhyme-suggest to the 422 envelope |
| `backend/tests/tag_expr/` | Vector tests (read `spec/` in place) and module tests |
| `backend/tests/tags/` | Value rules, routes (faked services), rhyme filter, DB tests: migration 005 and the services (`TAG_TEST_DATABASE_URL`, see `tests/tags/db.py`) |

## Invariants

- Tag names are unique per user ignoring case. The database index and the service check both enforce
  it, and a race on the index becomes `tag_name_taken`.
- Stored preset expressions are always the normalized form, non-empty and at most 500 code points.
  Parsing and re-formatting one gives the same text.
- A tag rename and its preset rewrites commit together or not at all.
- `parse` never returns an And/Or with fewer than two children, or same-kind nesting.
- Positions are code point offsets. The vectors are the conformance test for every implementation,
  and the file must stay byte-identical across branches.
- Tags, snippets and presets of another user are indistinguishable from missing ones (404).
- The dictionary rhyme source never sees `tag_filter`.
- Logs carry ids and counts only, never tag names, preset names or expressions
  (`tag.created user_id=… tag_id=…`, `tag.updated … renamed=… presets_rewritten=…`,
  `tag_preset.created|updated|deleted …`, `rhyme_suggest.done … tag_filter=True|False …`).
