# NLP: Similarity & Embeddings

TF-IDF cosine similarity for snippet comparison and sentence-transformer embeddings for t-SNE. Both read
the display text (rest marks `_` removed, [rest_marks.md](../features/rest_marks.md)); snippets whose
display text is empty are left out. The similarity response's `raw_text` is the stored text.

## Similarity Service

**File:** `app/services/nlp/similarity_service.py`

### API

```python
def compute_tfidf_similarity(
    target_text: str,
    corpus_texts: list[str],
    corpus_ids: list[str],
    top_k: int = 10
) -> list[dict]
```

### Algorithm

```
[target_text] + corpus_texts
  -> TfidfVectorizer(stop_words="english")
    -> TF-IDF sparse matrix
      -> cosine_similarity(target_vector, corpus_matrix)
        -> flatten to 1D scores
          -> sort descending
            -> filter score > 0
              -> return top_k results
```

- Uses scikit-learn `TfidfVectorizer` with English stop words removed
- Cosine similarity via `sklearn.metrics.pairwise.cosine_similarity`
- Score range: 0.0 (no similarity) to 1.0 (identical)
- Only returns results with score > 0

### Output

```python
[
  {"snippet_id": "...", "score": 0.85, "raw_text": "..."},
  {"snippet_id": "...", "score": 0.72, "raw_text": "..."},
]
```

### Route

```
GET /api/snippets/{snippet_id}/similar?top_k=10[&canvas_id=<uuid>]  (requires auth)
Validation: top_k in [1, 50]; snippet_id and canvas_id are UUIDs
```

Flow:
1. Fetch target snippet (verify ownership via `Snippet.owner_id`; 404 `Snippet not found`)
2. Fetch all other non-empty snippets placed on `canvas_id` (the target must be placed there, else 404
   `placement_not_found`), or, without `canvas_id`, on any canvas holding the target, each once
   ([snippet_placements.md](../features/snippet_placements.md#canvas-scoped-readers))
3. Compute TF-IDF similarity
4. Enrich results with snippet_type

---

## Embedding Service

**File:** `app/services/nlp/embedding_service.py`

### API

```python
def get_model() -> SentenceTransformer | None
def compute_embedding(text: str) -> list[float] | None
def compute_embeddings_batch(texts: list[str]) -> list[list[float]] | None
```

### Model

- Model: `all-MiniLM-L6-v2` (sentence-transformers)
- Dimensions: 384
- Parameters: ~22M
- Loading: Lazy (global `_model = None`, initialized on first call)
- Failure: Returns None if model fails to load, logs warning

### Usage

Embeddings are used exclusively by the t-SNE service. They are computed on-the-fly for each t-SNE request (no caching/persistence, despite the `snippet_embeddings` table existing in the schema).

### Batch Configuration

- Batch size: 32
- `convert_to_numpy=True`
- Output converted to nested Python list for JSON serialization

## Schemas

**File:** `app/schemas/nlp.py`

```python
class SimilarityResult(BaseModel):
    snippet_id: str
    raw_text: str
    score: float
    snippet_type: str
```
