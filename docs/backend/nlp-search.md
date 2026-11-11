# NLP: Full-Text Search

Case-insensitive text search across the snippets of one canvas, with the shared matching rule
(terms on display text; see [global_search.md](../features/global_search.md) and
[rest_marks.md](../features/rest_marks.md)). Search across all canvases is `GET /api/search`.

**File:** `app/services/nlp/search_service.py`

## API

```python
async def search_snippets(
    db: AsyncSession,
    canvas_id: str,
    query: str,
    owner_id: str,
    limit: int = 20,
) -> list[dict]
```

## Algorithm

`app/services/search/service.search_canvas`: an escaped `ILIKE` prefilter per query term over
`normalize(raw_text, NFC)`, then each candidate is confirmed in Python on its display text:

```sql
SELECT snippets.* FROM snippets
JOIN snippet_placements p ON p.snippet_id = snippets.id
JOIN canvases c ON c.id = p.canvas_id
WHERE p.canvas_id = :canvas_id AND c.owner_id = :owner_id
  AND normalize(raw_text, NFC) ILIKE '%term1%' ESCAPE '\' AND ...   -- \ % _ escaped
ORDER BY p.created_at, p.id
```

- Terms: the query NFC-normalised, trimmed, split on whitespace, lowercased; all must match
- Matched against display text (rest marks `_` removed), so `go now` finds `go_now` and `_` finds nothing
- A whitespace-only query has no terms and matches every snippet
- No ranking (all results get `score: 1.0`); at most 20 confirmed results

## Output

```python
[
  {
    "snippet_id": "uuid-...",
    "raw_text": "the full snippet text",
    "score": 1.0,
    "snippet_type": "verse"
  }
]
```

## Route

```
GET /api/canvases/{canvas_id}/search?q=<query>  (requires auth)
Validation: q minimum 1 character
```

## Future Enhancements

- `rank-bm25` library is installed but not wired up
- PostgreSQL `tsvector`/`ts_rank` or `pg_trgm` indexes for large libraries

## Schemas

```python
class SearchResult(BaseModel):
    snippet_id: str
    raw_text: str
    score: float
    snippet_type: str
```
