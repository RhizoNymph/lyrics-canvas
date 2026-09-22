import re
import pronouncing


def syllabify_word(word: str) -> list[dict]:
    """Syllabify a single word using CMU dict, with fallback heuristic."""
    clean = re.sub(r"[^a-zA-Z']", "", word).lower()
    if not clean:
        return []

    phones_list = pronouncing.phones_for_word(clean)
    if phones_list:
        phones = phones_list[0]
        return _cmu_syllabify(clean, phones)
    return _heuristic_syllabify(clean)


def _cmu_syllabify(word: str, phones: str) -> list[dict]:
    """Split CMU phonemes into syllables with stress markers."""
    phoneme_list = phones.split()
    syllables = []
    current_syllable_phones = []
    current_stress = 0

    for phoneme in phoneme_list:
        stress_match = re.search(r"\d", phoneme)
        if stress_match:
            # This is a vowel phoneme (nucleus of syllable)
            if current_syllable_phones and any(re.search(r"\d", p) for p in current_syllable_phones):
                # We already have a vowel — start new syllable
                syllables.append({"stress": current_stress})
                current_syllable_phones = []
            current_stress = int(stress_match.group())
            current_syllable_phones.append(phoneme)
        else:
            current_syllable_phones.append(phoneme)

    if current_syllable_phones:
        syllables.append({"stress": current_stress})

    # Map syllables back to the word text
    text_syllables = _split_word_text(word, len(syllables))
    result = []
    for i, syl in enumerate(syllables):
        result.append({
            "syllable": text_syllables[i] if i < len(text_syllables) else "",
            "stress": syl["stress"],
        })
    return result


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


def _heuristic_syllabify(word: str) -> list[dict]:
    """Fallback: count vowel groups as syllables."""
    vowels = set("aeiouy")
    groups = re.findall(r"[aeiouy]+", word.lower())
    if not groups:
        return [{"syllable": word, "stress": 0}]

    n = len(groups)
    parts = _split_word_text(word, n)
    result = []
    for i, part in enumerate(parts):
        result.append({
            "syllable": part,
            "stress": 1 if i == 0 else 0,  # Default: stress first syllable
        })
    return result


def syllabify_text(text: str) -> dict:
    """Syllabify full text into beat grid data structure."""
    lines_data = []
    for line_text in text.strip().split("\n"):
        line_text = line_text.strip()
        if not line_text:
            continue
        words = line_text.split()
        cells = []
        syllable_count = 0
        for word_index, word in enumerate(words):
            syllables = syllabify_word(word)
            for syl in syllables:
                cells.append({
                    "syllable": syl["syllable"],
                    "stress": syl["stress"],
                    "word_index": word_index,
                })
                syllable_count += 1
        lines_data.append({
            "cells": cells,
            "syllable_count": syllable_count,
            "raw_text": line_text,
        })
    return {"lines": lines_data}
