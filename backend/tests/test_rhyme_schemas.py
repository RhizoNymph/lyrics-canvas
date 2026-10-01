import pytest
from pydantic import ValidationError

from app.schemas.rhyme import RhymeSuggestRequest, SnippetAnchor, TextAnchor

BASE = {"canvas_id": "c1", "anchors": [{"kind": "text", "text": "door hinge"}]}


def test_defaults_match_contract():
    req = RhymeSuggestRequest.model_validate(BASE)
    assert req.k == 2
    assert req.min_score == 0.5
    assert req.sources == ["notes", "dictionary"]
    assert req.include_rare is False
    assert req.exclude_ids == []
    assert req.limit == 40
    assert req.offset == 0
    assert req.slot is None and req.weights is None and req.dialect is None
    assert isinstance(req.anchors[0], TextAnchor)


def test_discriminated_anchors():
    req = RhymeSuggestRequest.model_validate(
        {**BASE, "anchors": [{"kind": "snippet", "snippet_id": "s1", "line_index": None}, {"kind": "text", "text": "x"}]}
    )
    assert isinstance(req.anchors[0], SnippetAnchor) and req.anchors[0].line_index is None
    with pytest.raises(ValidationError):
        RhymeSuggestRequest.model_validate({**BASE, "anchors": [{"kind": "snippet"}]})
    with pytest.raises(ValidationError):
        RhymeSuggestRequest.model_validate({**BASE, "anchors": [{"kind": "text", "text": "   "}]})


@pytest.mark.parametrize(
    "patch",
    [
        {"anchors": []},
        {"k": 0},
        {"k": 5},
        {"limit": 0},
        {"limit": 101},
        {"offset": -1},
        {"min_score": 1.5},
        {"sources": []},
        {"sources": ["datamuse"]},
        {"slot": {"syllable_count": 2, "stress": "1x"}},
        {"slot": {"syllable_count": 3, "stress": "10"}},
        {"slot": {"syllable_count": 0, "stress": ""}},
        {"weights": {"vowel": -1}},
        {"dialect": {"rules": [{"kind": "swap", "source": ["A"], "target": []}]}},
        {"dialect": {"rules": [{"kind": "merge", "source": [], "target": ["AA"]}]}},
    ],
)
def test_invalid_requests_are_rejected(patch):
    with pytest.raises(ValidationError):
        RhymeSuggestRequest.model_validate({**BASE, **patch})


def test_slot_and_overrides_parse():
    req = RhymeSuggestRequest.model_validate(
        {
            **BASE,
            "slot": {"syllable_count": 2, "stress": "10"},
            "weights": {"vowel": 1.0, "coda": 0.6, "onset": 0.25},
            "dialect": {"rules": [{"kind": "final_merge", "source": ["IH", "NG"], "target": ["IH", "N"]}]},
        }
    )
    assert req.slot is not None and req.slot.stress == "10"
    assert req.weights is not None and req.weights.coda == 0.6
    assert req.dialect is not None and req.dialect.rules[0].kind == "final_merge"
