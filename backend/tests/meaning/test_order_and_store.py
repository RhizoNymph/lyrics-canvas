"""apply_order / apply_orders fallbacks and snippet meaning targets."""

from dataclasses import dataclass

import numpy as np

from app.services.meaning.order import apply_order, apply_orders
from app.services.meaning.service import TextPart, VectorPart
from app.services.meaning.store import NoStoredEmbeddings, SnippetText, snippet_parts
from app.services.rest_marks import display_text

from .fakes import FakeStore, fake_vector, meaning_service


@dataclass(frozen=True)
class Item:
    text: str
    score: float


ITEMS = [Item("hat", 1.0), Item("bat", 1.0), Item("night", 0.9), Item("dog", 0.9), Item("nation", 0.5)]


def _kw(**patch):
    return {"score": lambda i: i.score, "text": lambda i: i.text, **patch}


async def test_rhyme_order_never_touches_the_model():
    service, factory = meaning_service()
    out = await apply_order("rhyme", ITEMS, target=[TextPart("cat")], meaning=service, **_kw())
    assert out.applied == "rhyme"
    assert [o.item for o in out.items] == ITEMS and all(o.similarity is None for o in out.items)
    assert factory.loads == 0


async def test_meaning_order_sorts_within_bands():
    service, _ = meaning_service()
    out = await apply_order("meaning", ITEMS, target=[TextPart("cat")], meaning=service, **_kw())
    assert out.applied == "meaning"
    assert [o.item.text for o in out.items] == ["bat", "hat", "dog", "night", "nation"]
    assert all(o.similarity is not None for o in out.items)


async def test_meaning_falls_back_without_service_target_or_model():
    service, _ = meaning_service(unavailable=True)
    for meaning, target in [(None, [TextPart("cat")]), (meaning_service()[0], []), (service, [TextPart("cat")])]:
        out = await apply_order("meaning", ITEMS, target=target, meaning=meaning, **_kw())
        assert out.applied == "rhyme"
        assert [o.item for o in out.items] == ITEMS and all(o.similarity is None for o in out.items)


async def test_pool_limits_the_embedded_texts():
    service, factory = meaning_service()
    out = await apply_order("meaning", ITEMS, target=[TextPart("cat")], meaning=service, pool=2, **_kw())
    assert factory.embedder.encoded == ["cat", "hat", "bat"]
    assert [o.item.text for o in out.items] == ["bat", "hat", "night", "dog", "nation"]
    assert [o.similarity is None for o in out.items] == [False, False, True, True, True]


async def test_apply_orders_embeds_all_lists_in_one_call():
    service, factory = meaning_service()
    lists = [ITEMS[:2], [], ITEMS[2:]]
    out = await apply_orders("meaning", lists, target=[TextPart("cat")], meaning=service, **_kw())
    assert out.applied == "meaning"
    assert len(factory.embedder.calls) == 1
    assert [[o.item.text for o in col] for col in out.lists] == [["bat", "hat"], [], ["dog", "night", "nation"]]


async def test_apply_orders_falls_back_together():
    service, _ = meaning_service(unavailable=True)
    out = await apply_orders("meaning", [ITEMS[:2], ITEMS[2:]], target=[TextPart("cat")], meaning=service, **_kw())
    assert out.applied == "rhyme"
    assert [[o.item for o in col] for col in out.lists] == [ITEMS[:2], ITEMS[2:]]
    out = await apply_orders("rhyme", [ITEMS], target=[], meaning=None, **_kw())
    assert out.applied == "rhyme"


async def test_snippet_parts_prefer_current_stored_rows_and_use_display_text():
    stored = fake_vector("night")
    store = FakeStore({"s1": stored})
    parts = await snippet_parts(
        [SnippetText("s1", "dark"), SnippetText("s2", "black_cat\n_ sleeping"), SnippetText("s1", "dark"), SnippetText("s3", "_ _")],
        store,
    )
    assert len(parts) == 2
    assert isinstance(parts[0], VectorPart) and np.array_equal(parts[0].vector, stored)
    assert parts[1] == TextPart(display_text("black_cat\n_ sleeping").strip())
    assert "_" not in parts[1].text and "black" in parts[1].text and "sleeping" in parts[1].text
    assert store.asked == [["s1", "s2"]]  # each snippet once, blank ones not asked


async def test_snippet_parts_without_a_store():
    parts = await snippet_parts([SnippetText("s1", "cat")], NoStoredEmbeddings())
    assert parts == [TextPart("cat")]
