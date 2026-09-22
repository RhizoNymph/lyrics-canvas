from app.services.phonetics import pronounce
from app.services.phonetics.pronounce import clean_word, pronounce_word
from app.services.phonetics.types import PronunciationSource


def test_cmu_word():
    pron = pronounce_word("orange")
    assert pron is not None
    assert pron.source == PronunciationSource.cmudict
    assert pron.word == "orange"
    assert [s.nucleus for s in pron.syllables] == ["AO", "AH"]
    assert [s.stress for s in pron.syllables] == [1, 0]


def test_clean_word_strips_punctuation_and_case():
    assert clean_word("Hello,") == "hello"
    assert clean_word("don't!") == "don't"
    assert clean_word("42") == ""


def test_pronounce_phrase_splits_on_whitespace_and_skips_empty_tokens():
    pron = pronounce("door  hinge, 42")
    assert [w.word for w in pron.words] == ["door", "hinge"]
    assert len(pron.syllables) == 2


def test_pronounce_empty_text_has_no_syllables():
    assert pronounce("   ") .syllables == ()
    assert pronounce("") .words == ()


def test_oov_word_falls_back_to_g2p_or_heuristic():
    pron = pronounce_word("flarbnip")
    assert pron is not None
    assert pron.source in (PronunciationSource.g2p, PronunciationSource.heuristic)
    assert len(pron.syllables) == 2
    assert all(s.nucleus for s in pron.syllables)


def test_pronounce_is_cached_and_deterministic():
    assert pronounce("in the dark") == pronounce("in the dark")
    assert pronounce_word("flarbnip") is pronounce_word("flarbnip")


def test_heuristic_pronunciation_shape():
    from app.services.phonetics.pronounce import heuristic_pronunciation

    pron = heuristic_pronunciation("zorpable")
    assert pron.source == PronunciationSource.heuristic
    assert [s.stress for s in pron.syllables] == [1, 0, 0]
    assert all(s.nucleus for s in pron.syllables)
    assert heuristic_pronunciation("brr").syllables[0].nucleus == "AH"
