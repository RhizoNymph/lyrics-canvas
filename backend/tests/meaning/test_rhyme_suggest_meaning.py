"""POST /api/nlp/rhyme-suggest with order=meaning, at the service level (fake repo, dictionary, embedder)."""

import pytest

from app.schemas.rhyme import RhymeSuggestRequest
from app.services.rest_marks import display_text
from app.services.rhyme_order import BAND_RANK, band_of
from app.services.rhyme_suggest_service import suggest
from tests.fakes import FakeDictionary, default_repo, make_record

from .fakes import FakeStore, cosine, fake_vector, meaning_service


def _req(**patch) -> RhymeSuggestRequest:
    base = {"canvas_id": "c1", "anchors": [{"kind": "text", "text": "cat"}], "k": 1, "limit": 100, "min_score": 0.3}
    return RhymeSuggestRequest.model_validate({**base, **patch})


async def _run(req, meaning=None, store=None, repo=None):
    return await suggest(req, "u1", repo or default_repo(), dictionary=FakeDictionary, meaning=meaning, embeddings=store)


def _assert_band_then_meaning(candidates):
    ranks = [BAND_RANK[c.band] for c in candidates]
    assert ranks == sorted(ranks)
    for a, b in zip(candidates, candidates[1:]):
        if a.band == b.band:
            assert a.similarity >= b.similarity


async def test_order_defaults_to_rhyme_with_bands_and_no_similarity():
    service, factory = meaning_service()
    res = await _run(_req(), meaning=service)
    assert res.order_applied == "rhyme"
    assert all(c.similarity is None for c in res.candidates)
    assert all(c.band == band_of(c.score) for c in res.candidates)
    assert factory.loads == 0


async def test_explicit_rhyme_order_is_todays_order():
    service, _ = meaning_service()
    plain = await _run(_req())
    rhyme = await _run(_req(order="rhyme"), meaning=service)
    assert [c.id for c in rhyme.candidates] == [c.id for c in plain.candidates]
    assert rhyme.total == plain.total


async def test_meaning_order_sorts_bands_then_similarity():
    service, _ = meaning_service()
    res = await _run(_req(order="meaning"), meaning=service)
    assert res.order_applied == "meaning"
    assert len(res.candidates) > 5
    assert all(c.similarity is not None for c in res.candidates)
    assert all(c.band == band_of(c.score) for c in res.candidates)
    _assert_band_then_meaning(res.candidates)
    for c in res.candidates:
        assert c.similarity == pytest.approx(cosine("cat", c.text), abs=1e-4)
        assert c.similarity == round(c.similarity, 4)


async def test_meaning_changes_the_order_within_a_band():
    service, _ = meaning_service()
    rhyme = await _run(_req())
    meaning = await _run(_req(order="meaning"), meaning=service)
    perfect_rhyme = [c.id for c in rhyme.candidates if c.band == "perfect"]
    perfect_meaning = [c.id for c in meaning.candidates if c.band == "perfect"]
    assert sorted(perfect_rhyme) == sorted(perfect_meaning)
    assert perfect_rhyme != perfect_meaning
    # "bat" (an animal) beats "hat" for the anchor "cat"
    assert perfect_meaning.index("dict:bat") < perfect_meaning.index("dict:hat")


async def test_same_candidate_set_and_total_in_both_orders():
    service, _ = meaning_service()
    rhyme = await _run(_req())
    meaning = await _run(_req(order="meaning"), meaning=service)
    assert meaning.total == rhyme.total
    assert sorted(c.id for c in meaning.candidates) == sorted(c.id for c in rhyme.candidates)


async def test_pages_are_disjoint_and_stable():
    service, _ = meaning_service()
    full = await _run(_req(order="meaning"), meaning=service)
    pages = []
    for offset in range(0, full.total, 3):
        page = await _run(_req(order="meaning", limit=3, offset=offset), meaning=service)
        pages.extend(c.id for c in page.candidates)
    assert pages == [c.id for c in full.candidates]
    assert len(set(pages)) == len(pages)


async def test_model_unavailable_falls_back_to_rhyme_order():
    service, _ = meaning_service(unavailable=True)
    plain = await _run(_req())
    res = await _run(_req(order="meaning"), meaning=service)
    assert res.order_applied == "rhyme"
    assert [c.id for c in res.candidates] == [c.id for c in plain.candidates]
    assert all(c.similarity is None for c in res.candidates)


async def test_no_meaning_service_falls_back_to_rhyme_order():
    res = await _run(_req(order="meaning"))
    assert res.order_applied == "rhyme"


async def test_snippet_anchor_target_is_the_whole_snippet_display_text():
    repo = default_repo()
    repo.snippets["s-rest"] = make_record("s-rest", "night_falls on the\nsleeping cat")
    service, factory = meaning_service()
    await _run(_req(order="meaning", anchors=[{"kind": "snippet", "snippet_id": "s-rest"}]), meaning=service, repo=repo)
    first_batch = factory.embedder.calls[0]
    assert first_batch[0] == display_text("night_falls on the\nsleeping cat").strip()


async def test_two_anchors_on_one_snippet_count_it_once_and_text_anchors_join_the_mean():
    service, factory = meaning_service()
    anchors = [
        {"kind": "snippet", "snippet_id": "s-cat", "line_index": 0},
        {"kind": "snippet", "snippet_id": "s-cat", "line_index": 1},
        {"kind": "text", "text": "tonight"},
    ]
    res = await _run(_req(order="meaning", anchors=anchors), meaning=service)
    assert res.order_applied == "meaning"
    batch = factory.embedder.calls[0]
    assert batch[:2] == ["I saw a black cat\nsleeping on the mat", "tonight"]
    assert batch.count("I saw a black cat\nsleeping on the mat") == 1


async def test_a_current_stored_snippet_embedding_is_used_instead_of_its_text():
    service, factory = meaning_service()
    store = FakeStore({"s-cat": fake_vector("moon night")})
    res = await _run(_req(order="meaning", anchors=[{"kind": "snippet", "snippet_id": "s-cat"}]), meaning=service, store=store)
    assert store.asked == [["s-cat"]]
    assert "I saw a black cat\nsleeping on the mat" not in factory.embedder.encoded
    for c in res.candidates:
        assert c.similarity == pytest.approx(cosine("moon night", c.text), abs=1e-4)


async def test_candidate_texts_are_cached_across_requests():
    service, factory = meaning_service()
    await _run(_req(order="meaning"), meaning=service)
    encoded = len(factory.embedder.encoded)
    await _run(_req(order="meaning", offset=2), meaning=service)
    assert len(factory.embedder.encoded) == encoded  # nothing new to encode
