# NLP: t-SNE Layout

Dimensionality reduction of sentence embeddings for spatial clustering on the canvas.

**File:** `app/services/nlp/tsne_service.py`

## API

```python
def compute_tsne(
    embeddings: list[list[float]],
    snippet_ids: list[str],
    n_components: int = 2,
    perplexity: float = 30.0,
) -> list[dict]
```

## Algorithm

```
384-dim sentence embeddings (all-MiniLM-L6-v2)
  -> scikit-learn TSNE
    -> 2D or 3D coordinates
      -> min-max normalization to [0, 2000]
        -> canvas positions
```

### Configuration

| Parameter | Default | Notes |
|-----------|---------|-------|
| `n_components` | 2 | Capped at 3 |
| `perplexity` | 30.0 | Auto-adjusted: `min(perplexity, N-1)` |
| `random_state` | 42 | Reproducible results |
| `max_iter` | 1000 | Standard iterations |

### Perplexity Adjustment

t-SNE requires perplexity < N samples. For small datasets, perplexity is automatically reduced:
```python
effective_perplexity = min(perplexity, len(embeddings) - 1)
```

### Normalization

Output coordinates are scaled to a ~2000x2000 canvas space:
```python
mins = coords.min(axis=0)
ranges = coords.max(axis=0) - mins
ranges[ranges == 0] = 1  # Prevent division by zero
normalized = (coords - mins) / ranges * 2000
```

### Edge Cases

- < 2 embeddings: Returns fixed position `(0, 0)` or `(0, 0, 0)` without running t-SNE
- Zero-range axes: Set range to 1 to prevent division by zero

## Output

```python
[
  {"snippet_id": "...", "x": 1234.5, "y": 678.9},
  {"snippet_id": "...", "x": 456.7, "y": 890.1, "z": 345.6}  # if 3D
]
```

## Route

```
POST /api/canvases/{canvas_id}/tsne  (requires auth)
Body: { "n_components": 2, "perplexity": 30.0 }
```

Flow:
1. Fetch all non-empty snippets in canvas (verify ownership)
2. Compute embeddings on-the-fly: `compute_embedding(s.raw_text)` for each snippet
3. If < 2 valid embeddings, return fixed zero positions
4. Run `compute_tsne()` with request parameters
5. Return canvas-normalized positions

Note: Embeddings are recomputed fresh on each request; the `snippet_embeddings` table exists but is not yet used for caching.

## Schemas

```python
class TSNERequest(BaseModel):
    n_components: int = 2
    perplexity: float = 30.0

class TSNEResult(BaseModel):
    positions: list[dict]  # [{"snippet_id", "x", "y", "z"?}]
```

## Frontend Integration

The canvas store manages t-SNE mode:
1. `enterTSNE(positions)`: Saves current positions, applies t-SNE layout
2. `exitTSNE()`: Restores original positions

Toolbar toggles t-SNE on/off. When entering, calls the API; when exiting, restores saved positions without an API call.
