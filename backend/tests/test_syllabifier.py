from app.services.nlp.syllabifier import syllabify_text, syllabify_word
from app.services.phonetics import pronounce_word


def test_syllabify_text_shape_is_unchanged():
    result = syllabify_text("hello world\n\nsecond line")
    assert set(result) == {"lines"}
    assert len(result["lines"]) == 2
    line = result["lines"][0]
    assert set(line) == {"cells", "syllable_count", "raw_text"}
    assert line["raw_text"] == "hello world"
    assert line["syllable_count"] == 3
    assert line["cells"] == [
        {"syllable": "hel", "stress": 0, "word_index": 0},
        {"syllable": "lo", "stress": 1, "word_index": 0},
        {"syllable": "world", "stress": 1, "word_index": 1},
    ]


def test_syllabify_word_uses_engine_phones():
    cells = syllabify_word("Tomorrow,")
    assert [c["stress"] for c in cells] == [0, 1, 2]
    assert "".join(c["syllable"] for c in cells) == "tomorrow"
    assert syllabify_word("42") == []


def test_syllabify_word_stress_matches_pronounce_for_oov_words():
    word = "flarbnip"
    cells = syllabify_word(word)
    pron = pronounce_word(word)
    assert pron is not None
    assert [c["stress"] for c in cells] == [s.stress for s in pron.syllables]


def test_empty_text():
    assert syllabify_text("") == {"lines": []}
    assert syllabify_text("   \n  ") == {"lines": []}
