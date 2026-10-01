import pytest

from app.services.phonetics import DEFAULT_DIALECT_MAP, pronounce
from app.services.phonetics.dialect import DialectMap, DialectRule, RuleKind
from app.services.phonetics.errors import DialectRuleError
from app.services.phonetics.syllables import parse_phones, syllabify_phones
from app.services.phonetics.types import Pronunciation, PronunciationSource, WordPronunciation


def _pron(*phone_strings: str) -> Pronunciation:
    return Pronunciation(
        words=tuple(
            WordPronunciation(word=f"w{i}", syllables=syllabify_phones(parse_phones(p)), source=PronunciationSource.cmudict)
            for i, p in enumerate(phone_strings)
        )
    )


def _phones(pron: Pronunciation) -> list[str]:
    out = []
    for s in pron.syllables:
        out.extend(s.onset)
        out.append(s.nucleus)
        out.extend(s.coda)
    return out


def test_default_map_has_the_documented_rules():
    kinds = [(r.kind, r.source, r.target) for r in DEFAULT_DIALECT_MAP.rules]
    assert (RuleKind.final_merge, ("IH", "NG"), ("IH", "N")) in kinds
    assert (RuleKind.optional_final, ("T",), ()) in kinds
    assert (RuleKind.optional_final, ("D",), ()) in kinds
    assert (RuleKind.merge, ("ER",), ("AH", "R")) in kinds
    assert (RuleKind.merge, ("AO",), ("AA",)) in kinds


def test_final_merge_only_applies_at_word_end():
    pron = _pron("S IH1 NG IH0 NG")
    out = DEFAULT_DIALECT_MAP.apply(pron)
    assert _phones(out) == ["S", "IH", "NG", "IH", "N"]


def test_merge_er_to_ah_r_keeps_stress_and_syllable_count():
    pron = _pron("B ER1 D")
    out = DEFAULT_DIALECT_MAP.apply(pron)
    assert _phones(out) == ["B", "AH", "R", "D"]
    assert len(out.syllables) == 1
    assert out.syllables[0].stress == 1
    assert out.syllables[0].coda == ("R", "D")


def test_merge_ao_to_aa():
    out = DEFAULT_DIALECT_MAP.apply(_pron("K AO1 T"))
    assert _phones(out) == ["K", "AA", "T"]


def test_optional_final_marks_deletable_only_after_consonant():
    test = DEFAULT_DIALECT_MAP.apply(_pron("T EH1 S T"))
    assert test.syllables[-1].deletable_coda == 1
    cat = DEFAULT_DIALECT_MAP.apply(_pron("K AE1 T"))
    assert cat.syllables[-1].deletable_coda == 0
    hand = DEFAULT_DIALECT_MAP.apply(_pron("HH AE1 N D"))
    assert hand.syllables[-1].deletable_coda == 1


def test_optional_final_applies_per_word_final_syllable():
    out = DEFAULT_DIALECT_MAP.apply(_pron("T EH1 S T", "K AE1 T"))
    assert out.words[0].syllables[-1].deletable_coda == 1
    assert out.words[1].syllables[-1].deletable_coda == 0


def test_empty_map_is_identity():
    pron = _pron("S IH1 NG IH0 NG", "B ER1 D")
    assert DialectMap(rules=()).apply(pron) == pron


def test_digest_is_stable_and_order_sensitive():
    a = DialectMap(rules=(DialectRule(RuleKind.merge, ("AO",), ("AA",)), DialectRule(RuleKind.optional_final, ("T",), ())))
    b = DialectMap(rules=(DialectRule(RuleKind.merge, ("AO",), ("AA",)), DialectRule(RuleKind.optional_final, ("T",), ())))
    c = DialectMap(rules=(DialectRule(RuleKind.optional_final, ("T",), ()), DialectRule(RuleKind.merge, ("AO",), ("AA",))))
    assert a.digest() == b.digest()
    assert a.digest() != c.digest()
    assert len(a.digest()) == 40
    assert DEFAULT_DIALECT_MAP.digest() == DEFAULT_DIALECT_MAP.digest()
    assert DialectMap(rules=()).digest() != DEFAULT_DIALECT_MAP.digest()


@pytest.mark.parametrize(
    "rule",
    [
        DialectRule(RuleKind.merge, (), ("AA",)),
        DialectRule(RuleKind.merge, ("AO",), ()),
        DialectRule(RuleKind.merge, ("AO",), ("K",)),
        DialectRule(RuleKind.merge, ("AO", "R"), ("AA", "IY")),
        DialectRule(RuleKind.optional_final, ("AO",), ()),
        DialectRule(RuleKind.optional_final, ("T",), ("D",)),
        DialectRule(RuleKind.optional_final, ("S", "T"), ()),
        DialectRule(RuleKind.merge, ("XX",), ("AA",)),
    ],
)
def test_invalid_rules_raise(rule):
    with pytest.raises(DialectRuleError):
        DialectMap(rules=(rule,))


def test_apply_on_pronounced_text_matches_expected_phones():
    out = DEFAULT_DIALECT_MAP.apply(pronounce("door hinge"))
    assert _phones(out) == ["D", "AA", "R", "HH", "IH", "N", "JH"]
