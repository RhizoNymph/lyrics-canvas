import pytest

from app.services.phonetics.errors import PronunciationError
from app.services.phonetics.syllables import parse_phones, syllabify_phones
from app.services.phonetics.types import Syllable


def _shape(syllables: tuple[Syllable, ...]) -> list[tuple[tuple[str, ...], str, tuple[str, ...], int]]:
    return [(s.onset, s.nucleus, s.coda, s.stress) for s in syllables]


def test_parse_strips_stress_digits_and_keeps_stress():
    phones = parse_phones("K AE1 T")
    assert [p.phone for p in phones] == ["K", "AE", "T"]
    assert [p.stress for p in phones] == [None, 1, None]


def test_parse_rejects_unknown_phone():
    with pytest.raises(PronunciationError):
        parse_phones("K XX1 T")


def test_single_syllable_word():
    syls = syllabify_phones(parse_phones("K AE1 T"))
    assert _shape(syls) == [(("K",), "AE", ("T",), 1)]


def test_maximal_onset_single_consonant_goes_to_next_syllable():
    # banana: B AH0 N AE1 N AH0 -> ba.na.na
    syls = syllabify_phones(parse_phones("B AH0 N AE1 N AH0"))
    assert _shape(syls) == [
        ((("B",)), "AH", (), 0),
        (("N",), "AE", (), 1),
        (("N",), "AH", (), 0),
    ]


def test_maximal_onset_legal_cluster():
    # extra: EH1 K S T R AH0 -> ek.stra (S T R is a legal onset, K S T R is not)
    syls = syllabify_phones(parse_phones("EH1 K S T R AH0"))
    assert _shape(syls) == [((), "EH", ("K",), 1), (("S", "T", "R"), "AH", (), 0)]


def test_illegal_cluster_splits_before_legal_suffix():
    # after: AE1 F T ER0 -> af.ter (F T is not a legal onset)
    syls = syllabify_phones(parse_phones("AE1 F T ER0"))
    assert _shape(syls) == [((), "AE", ("F",), 1), (("T",), "ER", (), 0)]


def test_ng_never_starts_a_syllable():
    # singing: S IH1 NG IH0 NG -> sing.ing
    syls = syllabify_phones(parse_phones("S IH1 NG IH0 NG"))
    assert _shape(syls) == [(("S",), "IH", ("NG",), 1), ((), "IH", ("NG",), 0)]


def test_word_final_consonants_all_go_to_coda():
    syls = syllabify_phones(parse_phones("T EH1 S T S"))
    assert _shape(syls) == [(("T",), "EH", ("S", "T", "S"), 1)]


def test_word_initial_consonants_all_go_to_onset():
    syls = syllabify_phones(parse_phones("S T R IY1 T"))
    assert _shape(syls) == [(("S", "T", "R"), "IY", ("T",), 1)]


def test_no_vowel_yields_no_syllables():
    assert syllabify_phones(parse_phones("HH M")) == ()


def test_stress_values_are_carried():
    syls = syllabify_phones(parse_phones("T EH1 L AH0 F OW2 N"))
    assert [s.stress for s in syls] == [1, 0, 2]
