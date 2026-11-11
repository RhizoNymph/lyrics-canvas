"""Beat-grid syllabification.

Per-word phones and stress come from the phonetic engine (``phonetics.pronounce_word``: CMU
dict, then g2p_en, then a letter heuristic) so grid cells and ``snippets.phonetics`` never
disagree for new text. Only the mapping of syllables back onto the word's letters lives here.
"""

from app.services.phonetics import pronounce_word
from app.services.phonetics.pronounce import clean_word
from app.services.rest_marks import Rest, Word, tokenize_line


def syllabify_word(word: str) -> list[dict]:
    """Syllabify a single word into ``[{"syllable": str, "stress": 0|1|2}, ...]``."""
    pron = pronounce_word(word)
    if pron is None:
        return []
    clean = clean_word(word)
    text_syllables = _split_word_text(clean, len(pron.syllables))
    return [
        {"syllable": text_syllables[i] if i < len(text_syllables) else "", "stress": syllable.stress}
        for i, syllable in enumerate(pron.syllables)
    ]


def _split_word_text(word: str, n_syllables: int) -> list[str]:
    """Approximate splitting of word text into n syllables."""
    if n_syllables <= 1:
        return [word]

    vowels = set("aeiouy")
    splits = []
    current = ""
    vowel_seen = False
    syl_count = 0

    for i, ch in enumerate(word):
        current += ch
        if ch in vowels:
            vowel_seen = True
        elif vowel_seen and syl_count < n_syllables - 1:
            # After a vowel and hitting a consonant, potential split
            remaining_chars = len(word) - i - 1
            remaining_syls = n_syllables - syl_count - 1
            if remaining_chars >= remaining_syls:
                splits.append(current)
                current = ""
                vowel_seen = False
                syl_count += 1

    if current:
        splits.append(current)

    # Pad or merge if split count doesn't match
    while len(splits) < n_syllables:
        splits.append("")
    while len(splits) > n_syllables:
        splits[-2] = splits[-2] + splits[-1]
        splits.pop()

    return splits


def syllabify_text(text: str) -> dict:
    """Syllabify full text into beat grid data structure.

    Blank lines are skipped. Each ``_`` is a rest cell ``{"rest": true}`` (docs/features/rest_marks.md);
    ``word_index`` counts word tokens only and ``syllable_count`` excludes rests, so a line of rests
    is a line of rest cells with count 0.
    """
    lines_data = []
    for line_text in text.strip().split("\n"):
        line_text = line_text.strip()
        if not line_text:
            continue
        cells: list[dict] = []
        syllable_count = 0
        word_index = 0
        for token in tokenize_line(line_text):
            match token:
                case Rest():
                    cells.append({"rest": True})
                case Word(text=word):
                    for syl in syllabify_word(word):
                        cells.append({
                            "syllable": syl["syllable"],
                            "stress": syl["stress"],
                            "word_index": word_index,
                        })
                        syllable_count += 1
                    word_index += 1
        lines_data.append({
            "cells": cells,
            "syllable_count": syllable_count,
            "raw_text": line_text,
        })
    return {"lines": lines_data}
