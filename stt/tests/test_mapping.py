from stt_service.domain import Transcript, Word
from stt_service.mapping import seconds_to_ms, to_transcript

from .fakes import raw


def test_seconds_to_ms_rounds_to_nearest() -> None:
    assert seconds_to_ms(0.0) == 0
    assert seconds_to_ms(0.42) == 420
    assert seconds_to_ms(1.2344) == 1234
    assert seconds_to_ms(1.2346) == 1235
    assert seconds_to_ms(61.234) == 61234


def test_seconds_to_ms_never_negative() -> None:
    assert seconds_to_ms(-0.02) == 0


def test_basic_mapping() -> None:
    result = to_transcript(raw((" hello", 0.0, 0.42, 0.98), (" world.", 0.5, 0.9, 0.876), duration=61.234))
    assert result == Transcript(
        language="en",
        duration_ms=61234,
        words=(
            Word(text="hello", start_ms=0, end_ms=420, probability=0.98),
            Word(text="world.", start_ms=500, end_ms=900, probability=0.876),
        ),
    )


def test_text_is_stripped_but_punctuation_kept() -> None:
    result = to_transcript(raw(("  Hey,\n", 0.0, 0.2, 0.9), ("\tyou! ", 0.3, 0.5, 0.9)))
    assert [w.text for w in result.words] == ["Hey,", "you!"]


def test_whitespace_only_words_are_dropped() -> None:
    result = to_transcript(
        raw((" a", 0.0, 0.1, 0.9), ("   ", 0.1, 0.2, 0.9), ("", 0.2, 0.3, 0.9), (" b", 0.3, 0.4, 0.9))
    )
    assert [w.text for w in result.words] == ["a", "b"]


def test_words_are_ordered_by_start_stably() -> None:
    result = to_transcript(
        raw((" c", 1.0, 1.2, 0.9), (" a", 0.0, 0.2, 0.9), (" b1", 0.5, 0.6, 0.9), (" b2", 0.5, 0.7, 0.9))
    )
    assert [w.text for w in result.words] == ["a", "b1", "b2", "c"]


def test_end_never_before_start() -> None:
    result = to_transcript(raw((" x", 1.0, 0.9, 0.9)))
    assert result.words[0].start_ms == 1000
    assert result.words[0].end_ms == 1000


def test_probability_clamped_to_unit_interval() -> None:
    result = to_transcript(raw((" a", 0.0, 0.1, 1.0000001), (" b", 0.2, 0.3, -0.1)))
    assert [w.probability for w in result.words] == [1.0, 0.0]


def test_empty_transcript() -> None:
    result = to_transcript(raw(duration=0.0))
    assert result == Transcript(language="en", duration_ms=0, words=())


def test_language_passed_through() -> None:
    assert to_transcript(raw(language="de")).language == "de"
