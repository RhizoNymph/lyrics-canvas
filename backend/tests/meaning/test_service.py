"""MeaningService: target means, cache, vocabulary rows, model loading and failures."""

import asyncio
import logging

import numpy as np
import pytest

from app.services.meaning.cache import VectorLRU
from app.services.meaning.service import MeaningService, TextPart, VectorPart

from .fakes import FakeEmbedder, FakeFactory, FakeVocabulary, cosine, fake_vector, meaning_service


async def test_similarity_is_the_cosine_to_a_text_target():
    service, _ = meaning_service()
    sims = await service.similarities([TextPart("black cat")], ["bat", "night", "hat"])
    assert sims is not None and len(sims) == 3
    for text, sim in zip(["bat", "night", "hat"], sims):
        assert sim == pytest.approx(cosine("black cat", text), abs=1e-5)
    assert sims[0] > sims[2] and sims[0] > sims[1]


async def test_target_is_the_mean_of_its_normalized_parts():
    service, _ = meaning_service()
    vector = fake_vector("night") * 10  # stored rows need not be normalized
    sims = await service.similarities([TextPart("cat"), VectorPart(vector)], ["dog", "moon", "nation"])
    assert sims is not None
    # an animal word and a night word are equally close to the mean of "cat" and "night"
    assert sims[0] == pytest.approx(sims[1], abs=0.05)
    assert sims[0] > 0.5 and sims[2] < 0.2


async def test_texts_are_encoded_once_and_then_served_from_the_cache():
    service, factory = meaning_service()
    await service.similarities([TextPart("cat")], ["bat", "hat", "bat"])
    assert factory.embedder.calls == [["cat", "bat", "hat"]]  # distinct, one batch
    await service.similarities([TextPart("cat")], ["hat", "dog"])
    assert factory.embedder.calls[1] == ["dog"]
    assert factory.loads == 1
    assert len(service.cache) == 4


async def test_vocabulary_rows_are_used_without_encoding_or_caching():
    factory = FakeFactory()
    vocabulary = FakeVocabulary(["bat", "hat"])
    service = MeaningService(factory, vocabulary=vocabulary)
    sims = await service.similarities([TextPart("cat")], ["bat", "hat", "the black mat"])
    assert sims is not None
    assert factory.embedder.encoded == ["cat", "the black mat"]
    assert "bat" not in service.cache
    assert sims[0] == pytest.approx(cosine("cat", "bat"), abs=1e-5)


async def test_vocabulary_of_another_model_is_ignored():
    factory = FakeFactory()
    service = MeaningService(factory, vocabulary=FakeVocabulary(["bat"], model_name="other"))
    await service.similarities([TextPart("cat")], ["bat"])
    assert factory.embedder.encoded == ["cat", "bat"]


async def test_cache_hits_skip_the_vocabulary_lookup():
    factory = FakeFactory()
    vocabulary = FakeVocabulary(["bat"])
    service = MeaningService(factory, vocabulary=vocabulary)
    await service.similarities([TextPart("cat")], ["dog"])
    await service.similarities([TextPart("cat")], ["dog", "bat"])
    assert vocabulary.asked[-1] == ["bat"]


async def test_unavailable_model_answers_none_and_warns_once(caplog):
    service, factory = meaning_service(unavailable=True)
    with caplog.at_level(logging.WARNING, logger="app.services.meaning.service"):
        assert await service.similarities([TextPart("cat")], ["bat"]) is None
        assert await service.similarities([TextPart("cat")], ["hat"]) is None
        assert await service.embed(["x"]) is None
    assert factory.loads == 1
    assert service.unavailable
    warnings = [r for r in caplog.records if "meaning.unavailable" in r.getMessage()]
    assert len(warnings) == 1


async def test_a_failing_batch_answers_none_for_that_call_only(caplog):
    embedder = FakeEmbedder(fail=True)
    service = MeaningService(FakeFactory(embedder))
    with caplog.at_level(logging.WARNING, logger="app.services.meaning.service"):
        assert await service.similarities([TextPart("cat")], ["bat"]) is None
    assert any("meaning.embed_failed" in r.getMessage() for r in caplog.records)
    assert not service.unavailable
    embedder.fail = False
    assert await service.similarities([TextPart("cat")], ["bat"]) is not None


async def test_a_wrong_shape_is_a_failed_batch():
    service = MeaningService(FakeFactory(FakeEmbedder(bad_shape=True)))
    assert await service.similarities([TextPart("cat")], ["bat"]) is None
    assert len(service.cache) == 0


async def test_concurrent_first_calls_load_the_model_once():
    service, factory = meaning_service()
    results = await asyncio.gather(*(service.similarities([TextPart("cat")], [f"w{i}"]) for i in range(8)))
    assert all(r is not None for r in results)
    assert factory.loads == 1


async def test_empty_texts_and_empty_target():
    service, _ = meaning_service()
    assert await service.similarities([TextPart("cat")], []) == []
    with pytest.raises(ValueError):
        await service.similarities([], ["bat"])


async def test_rows_are_normalized():
    service, _ = meaning_service()
    rows = await service.embed(["cat cat cat", "night"])
    assert rows is not None
    for row in rows.values():
        assert float(np.linalg.norm(row)) == pytest.approx(1.0, abs=1e-5)


async def test_cache_bound_is_respected():
    factory = FakeFactory()
    service = MeaningService(factory, cache=VectorLRU(2))
    await service.similarities([TextPart("cat")], ["a", "b", "c"])
    assert len(service.cache) == 2
