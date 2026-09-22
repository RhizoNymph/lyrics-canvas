import numpy as np
from sklearn.manifold import TSNE


def compute_tsne(
    embeddings: list[list[float]],
    snippet_ids: list[str],
    n_components: int = 2,
    perplexity: float = 30.0,
) -> list[dict]:
    """Compute t-SNE positions from embeddings."""
    if len(embeddings) < 2:
        return [{"snippet_id": sid, "x": 0.0, "y": 0.0} for sid in snippet_ids]

    arr = np.array(embeddings)
    effective_perplexity = min(perplexity, len(embeddings) - 1)

    tsne = TSNE(
        n_components=min(n_components, 3),
        perplexity=effective_perplexity,
        random_state=42,
        max_iter=1000,
    )
    coords = tsne.fit_transform(arr)

    # Normalize to canvas-friendly coordinates (scale to ~2000x2000 area)
    if coords.shape[0] > 1:
        mins = coords.min(axis=0)
        maxs = coords.max(axis=0)
        ranges = maxs - mins
        ranges[ranges == 0] = 1
        coords = (coords - mins) / ranges * 2000

    results = []
    for i, sid in enumerate(snippet_ids):
        pos = {"snippet_id": sid, "x": float(coords[i, 0]), "y": float(coords[i, 1])}
        if n_components >= 3 and coords.shape[1] >= 3:
            pos["z"] = float(coords[i, 2])
        results.append(pos)
    return results
