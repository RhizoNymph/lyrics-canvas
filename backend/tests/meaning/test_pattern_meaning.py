"""POST /api/nlp/rhyme-pattern/suggest with order=meaning: per slot column, line matches unchanged."""

import pytest

from app.schemas.rhyme_pattern import PatternSuggestRequest
from app.services import rhyme_pattern_service as svc
from app.services.rhyme_order import BAND_RANK, band_of
from tests.rhyme_pattern.fakes import VERSE, fake_dictionary_index, pattern_repo

from .fakes import FakeStore, cosine, fake_vector, meaning_service


def _span(line: int, start: int) -> dict:
    return {"line_index": line, "word_start": start, "word_end": start + 1}


SLOTS = [
    {"id": "s0", "members": [_span(0, 3), _span(1, 0), _span(2, 0)]},
    {"id": "s1", "members": [_span(0, 6), _span(1, 4), _span(2, 4)]},
    {"id": "s2", "members": [_span(0, 7), _span(1, 5), _span(2, 5)]},
]


def _req(**patch) -> PatternSuggestRequest:
    base = {"canvas_id": "c1", "snippet_id": "s-verse", "line_start": 0, "line_end": 3, "slots": SLOTS, "limit": 100}
    return PatternSuggestRequest.model_validate({**base, **patch})


async def _run(req, meaning=None, store=None):
    return await svc.suggest(req, "u1", pattern_repo(), dictionary=fake_dictionary_index, meaning=meaning, embeddings=store)


async def test_rhyme_order_by_default():
    service, factory = meaning_service()
    res = await _run(_req(), meaning=service)
    assert res.order_applied == "rhyme"
    for slot in res.slots:
        assert all(c.similarity is None and c.band == band_of(c.score) for c in slot.candidates)
    assert factory.loads == 0


async def test_each_slot_column_is_sorted_by_band_then_meaning():
    service, factory = meaning_service()
    rhyme = await _run(_req())
    res = await _run(_req(order="meaning"), meaning=service)
    assert res.order_applied == "meaning"
    assert len(factory.embedder.calls) == 1  # one batch for every column
    for before, after in zip(rhyme.slots, res.slots):
        assert after.total == before.total
        assert sorted(c.id for c in after.candidates) == sorted(c.id for c in before.candidates)
        ranks = [BAND_RANK[c.band] for c in after.candidates]
        assert ranks == sorted(ranks)
        for a, b in zip(after.candidates, after.candidates[1:]):
            if a.band == b.band:
                assert a.similarity >= b.similarity
        for c in after.candidates:
            assert c.band == band_of(c.score)
            assert c.similarity == pytest.approx(cosine(VERSE, c.text), abs=1e-4)


async def test_target_is_the_request_snippet():
    service, factory = meaning_service()
    await _run(_req(order="meaning"), meaning=service)
    assert factory.embedder.calls[0][0] == VERSE


async def test_stored_embedding_of_the_snippet_is_reused():
    service, factory = meaning_service()
    store = FakeStore({"s-verse": fake_vector("rain sea")})
    res = await _run(_req(order="meaning"), meaning=service, store=store)
    assert VERSE not in factory.embedder.encoded
    c = res.slots[0].candidates[0]
    assert c.similarity == pytest.approx(cosine("rain sea", c.text), abs=1e-4)


async def test_line_matches_keep_their_order():
    service, _ = meaning_service()
    rhyme = await _run(_req())
    res = await _run(_req(order="meaning"), meaning=service)
    assert [m.unit.id for m in res.line_matches] == [m.unit.id for m in rhyme.line_matches]
    assert res.line_match_total == rhyme.line_match_total


async def test_offsets_page_each_column_of_the_meaning_order():
    service, _ = meaning_service()
    full = await _run(_req(order="meaning"), meaning=service)
    paged = await _run(_req(order="meaning", limit=2, offsets={"s0": 2, "s1": 1}), meaning=service)
    assert [c.id for c in paged.slots[0].candidates] == [c.id for c in full.slots[0].candidates[2:4]]
    assert [c.id for c in paged.slots[1].candidates] == [c.id for c in full.slots[1].candidates[1:3]]
    assert [c.id for c in paged.slots[2].candidates] == [c.id for c in full.slots[2].candidates[0:2]]


async def test_model_unavailable_falls_back_for_every_column():
    service, _ = meaning_service(unavailable=True)
    rhyme = await _run(_req())
    res = await _run(_req(order="meaning"), meaning=service)
    assert res.order_applied == "rhyme"
    for before, after in zip(rhyme.slots, res.slots):
        assert [c.id for c in after.candidates] == [c.id for c in before.candidates]
        assert all(c.similarity is None for c in after.candidates)
