# Search Across Canvases

`GET /api/search` finds the caller's canvases by title and snippets by text, across every canvas they
own, so they can find something to work on from the canvas list. The per-canvas search
(`GET /api/canvases/{id}/search`) uses the same matching rule. Client UI: the web and Android feature
docs (`web_rests_and_search.md`, `android_rests_and_search.md`).

## Scope

- `GET /api/search?q=&limit=`: validation, the typed error, the two result lists, ordering, excerpts.
- The rest-aware matching of `GET /api/canvases/{id}/search` (unchanged response shape).

## Non-scope

- Ranking or full-text indexes (`tsvector`, trigram): matches are unranked substring matches.
- The client in-canvas filter (client only, same rule) and the canvas list UI.
- Shared (public) canvases of other users.

## Matching

Defined in [rest_marks.md](./rest_marks.md#search-matching) and pinned by the `match` vectors: NFC, trim,
split on whitespace into terms, lowercase; every term must be a substring of the snippet's display text
(rest marks removed), NFC and lowercased. Canvas titles are matched the same way against the title
itself (titles have no rest marks, so `_` in a title is literal).

### Implementation

1. **SQL prefilter** (`app/services/search/sql.py`): one `normalize(col, NFC) ILIKE '%term%' ESCAPE '\'`
   per term, `\`, `%` and `_` escaped with `\`. A term has no whitespace, so if it is in the display
   text it lies inside one word token, which appears verbatim in `raw_text`: the prefilter never drops a
   real match. It can let through rows the display rule rejects (a term containing `_`).
2. **Confirm in Python** (`app/services/search/matching.py`) on display text, in the query's order,
   reading candidates in chunks of 200 until `limit` confirmed hits; so `limit` counts confirmed hits.

## API

```
GET /api/search?q=<text>&limit=<n>    (Bearer)
```

- `q`: required; after NFC and trimming 1-200 code points, else
  `422 {"detail": {"error": "invalid_search_query", "message": "..."}}` (missing, blank, too long).
- `limit`: default 50, 1-200 (FastAPI's standard 422 outside that); applies to each list separately.

Response 200 (`SearchResponse`):

```json
{
  "canvases": [{"id": "uuid", "title": "Summer Song", "updated_at": "2026-11-11 10:00:00.123456+00:00"}],
  "snippets": [{
    "snippet_id": "uuid",
    "snippet_type": "verse",
    "content_updated_at": "2026-11-11 10:00:00.123456+00:00",
    "excerpt": "the black cat",
    "canvases": [{"id": "uuid", "title": "Alpha"}, {"id": "uuid", "title": "zed"}]
  }]
}
```

- `canvases`: the caller's canvases (`canvases.owner_id`) whose title matches every term, by
  `updated_at` desc, then id.
- `snippets`: the caller's snippets (`snippets.owner_id`) whose display text matches, by
  `content_updated_at` desc, then id. `canvases` lists every canvas holding the snippet, each once, sorted
  by lower-cased title then id (as `SnippetResponse.canvases`). `snippet_type` is one of verse, chorus,
  bridge, hook, freestyle.
- `excerpt`: display text (no `_`), the first display line containing the first term (NFC,
  case-insensitive), else the first non-empty display line, else `""`. Lines over 160 code points are
  cut to 160 around the match: from the start (`…` at the end) when the match is within the first 40,
  otherwise starting 40 before the match with `…` on the cut side(s).
- Datetimes are Python `str(datetime)` strings, as elsewhere in the API.

## Per-canvas search

`GET /api/canvases/{id}/search?q=` (q at least 1 character) returns
`[{snippet_id, raw_text, score: 1.0, snippet_type}]` for the snippets placed on the caller's canvas that
match, oldest placement first, at most 20. A whitespace-only `q` has no terms and matches every snippet.
Another user's canvas (or a malformed id) yields `[]`.

## Data flow

```
GET /api/search ─► api/search.py: parse_search_query(q) ─(InvalidSearchQueryError)─► 422 typed body
                   └► service.search_everything(db, owner, query, limit)
                        ├ matching_canvases: SELECT id,title,updated_at WHERE owner AND ILIKE terms
                        │                    ORDER BY updated_at DESC, id ─► title_matches ─► limit
                        └ matching_snippets: SELECT id,type,content_updated_at,raw_text WHERE owner_id
                                             AND ILIKE terms ORDER BY content_updated_at DESC, id
                                             ─► matches_terms (display text) ─► limit
                                             ─► placements.queries.canvas_refs ─► excerpt
```

## Files

| File | Role | Key exports |
|------|------|-------------|
| `backend/app/api/search.py` | Route, error mapping | `router`, `search_http_error`, `DEFAULT_LIMIT`, `MAX_LIMIT` |
| `backend/app/schemas/search.py` | Wire models | `SearchResponse`, `SearchCanvasHit`, `SearchSnippetHit`, `SearchErrorBody`, `SearchErrorResponse` |
| `backend/app/services/search/errors.py` | Typed errors | `SearchErrorCode`, `SearchError`, `InvalidSearchQueryError` |
| `backend/app/services/search/query.py` | Validated query | `SearchQuery`, `parse_search_query`, `MAX_QUERY_LENGTH` |
| `backend/app/services/search/matching.py` | Matching rule | `normalize_terms`, `normalize_text`, `matches_terms`, `matches_query`, `title_matches` |
| `backend/app/services/search/sql.py` | ILIKE prefilter | `like_pattern`, `terms_filter`, `nfc` |
| `backend/app/services/search/excerpt.py` | Excerpts | `excerpt`, `EXCERPT_MAX` |
| `backend/app/services/search/service.py` | Queries | `search_everything`, `matching_canvases`, `matching_snippets`, `search_canvas`, `SearchResults`, `CanvasHit`, `SnippetHit` |
| `backend/app/services/nlp/search_service.py` | Per-canvas search | `search_snippets` |
| `backend/tests/search/test_query.py` | Pure tests | |
| `backend/tests/search/test_search_db.py` | HTTP against Postgres (`TAG_TEST_DATABASE_URL`) | |

## Invariants

- Only the caller's rows: canvases by `canvases.owner_id`, snippets by `snippets.owner_id`.
- Every returned snippet's display text matches every term; the SQL prefilter never decides alone.
- A `SearchQuery` always has at least one term.
- `excerpt` never contains `_` and is at most 160 code points.
