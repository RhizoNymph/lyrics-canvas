"""Meaning order with the real all-MiniLM-L6-v2 model. Opt-in and offline: runs only with
RUN_EMBEDDING_MODEL_TESTS=1 and the model already in the local Hugging Face cache (it is never
downloaded here). ``pytest -m embedding_model`` selects it."""

from __future__ import annotations

import os

import pytest

MODEL_REPO = "sentence-transformers/all-MiniLM-L6-v2"


def _model_cached() -> bool:
    try:
        from huggingface_hub import try_to_load_from_cache
    except ImportError:
        return False
    found = try_to_load_from_cache(MODEL_REPO, "config.json")
    return isinstance(found, str)


pytestmark = [
    pytest.mark.embedding_model,
    pytest.mark.skipif(os.environ.get("RUN_EMBEDDING_MODEL_TESTS") != "1", reason="set RUN_EMBEDDING_MODEL_TESTS=1"),
    pytest.mark.skipif(not _model_cached(), reason=f"{MODEL_REPO} is not in the local Hugging Face cache"),
]


@pytest.fixture
def offline(monkeypatch):
    """No network: huggingface_hub reads HF_HUB_OFFLINE at import, so patch its constant too."""
    from huggingface_hub import constants

    monkeypatch.setenv("HF_HUB_OFFLINE", "1")
    monkeypatch.setattr(constants, "HF_HUB_OFFLINE", True)


async def test_real_model_ranks_related_words_higher(offline):
    from app.services.meaning.embedder import sentence_transformer_embedder
    from app.services.meaning.order import apply_order
    from app.services.meaning.service import MeaningService, TextPart

    service = MeaningService(sentence_transformer_embedder)
    sims = await service.similarities([TextPart("my black cat purrs")], ["kitten", "hat", "democracy"])
    assert sims is not None
    assert sims[0] > sims[1] > sims[2] - 0.2
    assert all(-1.0 <= s <= 1.0 for s in sims)

    items = [("hat", 1.0), ("mat", 1.0), ("bat", 1.0), ("rat", 1.0)]
    out = await apply_order(
        "meaning", items, score=lambda i: i[1], text=lambda i: i[0], target=[TextPart("animals in the night")], meaning=service
    )
    assert out.applied == "meaning"
    texts = [o.item[0] for o in out.items]
    assert texts.index("bat") < texts.index("hat") and texts.index("rat") < texts.index("mat")
