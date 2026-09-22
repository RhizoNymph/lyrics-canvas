from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.metrics.pairwise import cosine_similarity
import numpy as np


def compute_tfidf_similarity(target_text: str, corpus_texts: list[str], corpus_ids: list[str], top_k: int = 10) -> list[dict]:
    """Compute TF-IDF cosine similarity between target and corpus."""
    if not corpus_texts:
        return []

    all_texts = [target_text] + corpus_texts
    vectorizer = TfidfVectorizer(stop_words="english")
    tfidf_matrix = vectorizer.fit_transform(all_texts)

    similarities = cosine_similarity(tfidf_matrix[0:1], tfidf_matrix[1:]).flatten()

    ranked = sorted(zip(corpus_ids, similarities, corpus_texts), key=lambda x: x[1], reverse=True)
    results = []
    for snippet_id, score, text in ranked[:top_k]:
        if score > 0:
            results.append({
                "snippet_id": snippet_id,
                "score": float(score),
                "raw_text": text,
            })
    return results
