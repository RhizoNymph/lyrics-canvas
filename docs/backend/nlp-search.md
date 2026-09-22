# NLP: Full-Text Search

Case-insensitive text search across canvas snippets.

**File:** `app/services/nlp/search_service.py`

## API

```python
async def search_snippets(
    db: AsyncSession,
    canvas_id: str,
    query: str,
    owner_id: str | None = None,
    limit: int = 20,
) -> list[dict]
```

## Algorithm

Currently uses PostgreSQL `ILIKE` pattern matching:

```sql
SELECT * FROM snippets
WHERE canvas_id = :canvas_id
  AND raw_text ILIKE '%query%'
LIMIT 20
```

- Case-insensitive substring match
- No ranking (all results get `score: 1.0`)
- Maximum 20 results per search

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

The codebase has provisions for more sophisticated search:
- `rank-bm25` library is installed but not wired up
- PostgreSQL `tsvector`/`ts_rank` full-text search noted as possible enhancement
- `owner_id` parameter exists in the service but is unused in the current query

## Schemas

```python
class SearchResult(BaseModel):
    snippet_id: str
    raw_text: str
    score: float
    snippet_type: str
```
