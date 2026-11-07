"""Vocabulary-hint prompt builder (contract section 2): ranking, budget, reverse emission, format."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest

from app.services.voice.hints import (
    PREFIX,
    HintWord,
    build_hint_prompt,
    eligible_words,
    rank_hint_words,
    zipf_en,
)

T0 = datetime(2026, 11, 1, tzinfo=timezone.utc)


def word(text: str, minutes: int = 0) -> HintWord:
    return HintWord(text=text, created_at=T0 + timedelta(minutes=minutes))


def table(freqs: dict[str, float]):
    return lambda text: freqs.get(text, 0.0)


def prompt_of(words, max_chars=600, freqs=None):
    result = build_hint_prompt(words, max_chars, table(freqs or {}))
    return None if result is None else result.text


# ---- ranking ----


def test_rarer_words_rank_first():
    ranked = rank_hint_words([word("the"), word("zephyr"), word("night")], table({"the": 7.7, "night": 5.4, "zephyr": 2.1}))
    assert [w.text for w in ranked] == ["zephyr", "night", "the"]


def test_unknown_words_count_as_zero_and_rank_first():
    ranked = rank_hint_words([word("night"), word("glorbix")], table({"night": 5.4}))
    assert [w.text for w in ranked] == ["glorbix", "night"]


def test_equal_frequency_newer_first():
    ranked = rank_hint_words([word("old", 0), word("new", 10), word("mid", 5)], table({}))
    assert [w.text for w in ranked] == ["new", "mid", "old"]


def test_equal_frequency_and_time_by_lowercase_text():
    ranked = rank_hint_words([word("beta"), word("Alpha"), word("gamma")], table({}))
    assert [w.text for w in ranked] == ["Alpha", "beta", "gamma"]


def test_frequency_beats_recency():
    ranked = rank_hint_words([word("common", 99), word("rare", 0)], table({"common": 5.0, "rare": 1.0}))
    assert [w.text for w in ranked] == ["rare", "common"]


def test_words_without_a_letter_are_not_offered():
    assert [w.text for w in eligible_words([word("---"), word("''"), word("a-ha"), word("ça")])] == ["a-ha", "ça"]


# ---- emission and format ----


def test_most_important_word_comes_last():
    text = prompt_of([word("the"), word("zephyr"), word("night")], freqs={"the": 7.7, "night": 5.4, "zephyr": 2.1})
    assert text == "Vocabulary: the, night, zephyr."


def test_single_word_format():
    assert prompt_of([word("Zephyr")]) == "Vocabulary: Zephyr."


def test_phrases_are_kept_whole_and_case_preserved():
    text = prompt_of([word("Midnight Train", 1), word("o'clock", 0)])
    assert text == "Vocabulary: o'clock, Midnight Train."


def test_empty_bank_sends_no_prompt():
    assert build_hint_prompt([], 600, table({})) is None


def test_bank_of_letterless_words_sends_no_prompt():
    assert build_hint_prompt([word("--")], 600, table({})) is None


# ---- budget ----


def test_budget_counts_prefix_separators_and_period():
    # "Vocabulary: ab, cd." is 19 code points
    words = [word("ab", 1), word("cd", 0)]
    assert prompt_of(words, max_chars=19) == "Vocabulary: cd, ab."
    assert prompt_of(words, max_chars=18) == "Vocabulary: ab."


def test_exact_fit_for_one_word():
    assert len(PREFIX + "abc.") == 16
    assert prompt_of([word("abc")], max_chars=16) == "Vocabulary: abc."
    assert prompt_of([word("abc")], max_chars=15) is None


def test_budget_keeps_the_most_important_words():
    freqs = {f"w{i}": float(i) for i in range(50)}  # w0 is the rarest
    words = [word(f"w{i}") for i in range(50)]
    result = build_hint_prompt(words, 60, table(freqs))
    assert result is not None
    kept = result.text.removeprefix(PREFIX).removesuffix(".").split(", ")
    assert kept == [f"w{i}" for i in reversed(range(len(kept)))]
    assert kept[-1] == "w0"
    assert len(result.text) <= 60
    # one more word would not have fit
    longer = PREFIX + ", ".join([f"w{len(kept)}", *kept]) + "."
    assert len(longer) > 60


def test_stops_at_the_first_word_that_does_not_fit():
    # the second-ranked word is long; the shorter third is not used to fill the gap
    words = [word("aa", 2), word("x" * 50, 1), word("bb", 0)]
    assert prompt_of(words, max_chars=30) == "Vocabulary: aa."


def test_most_important_word_too_long_sends_no_prompt():
    assert build_hint_prompt([word("x" * 64, 1), word("ab", 0)], 40, table({})) is None


def test_budget_counts_code_points_not_bytes():
    assert prompt_of([word("ñññ")], max_chars=16) == "Vocabulary: ñññ."


@pytest.mark.parametrize("max_chars", [0, -5])
def test_non_positive_budget_sends_no_prompt(max_chars):
    assert build_hint_prompt([word("a")], max_chars, table({})) is None


def test_counts():
    result = build_hint_prompt([word("ab", 1), word("cd", 0), word("--")], 18, table({}))
    assert result is not None
    assert (result.words_offered, result.words_used, result.chars) == (2, 1, 15)


def test_every_word_fits_under_the_default_budget():
    words = [word(f"word{i}", i) for i in range(5)]
    result = build_hint_prompt(words, 600, table({}))
    assert result is not None and result.words_used == 5


def test_deterministic_under_input_order():
    words = [word("night", 3), word("zephyr", 1), word("Alpha", 1), word("alps", 1)]
    freqs = {"night": 5.0}
    assert prompt_of(words, freqs=freqs) == prompt_of(list(reversed(words)), freqs=freqs)


# ---- the real frequency source ----


def test_zipf_en_ranks_common_above_rare_and_unknown_as_zero():
    assert zipf_en("the") > zipf_en("zephyr") > 0
    assert zipf_en("glorbixqua") == 0.0


def test_zipf_en_handles_phrases():
    assert zipf_en("midnight train") > 0
