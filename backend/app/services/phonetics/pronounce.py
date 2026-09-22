"""Pronunciation lookup: CMU dict, then g2p_en, then a letter heuristic."""

from __future__ import annotations

import logging
import re
import threading
from functools import lru_cache
from typing import Callable

import pronouncing

from app.services.phonetics.distance import PHONES
from app.services.phonetics.errors import ResourceError
from app.services.phonetics.resources import ensure_resources
from app.services.phonetics.syllables import Phone, parse_phones, syllabify_phones
from app.services.phonetics.types import Pronunciation, PronunciationSource, Stress, Syllable, WordPronunciation

logger = logging.getLogger(__name__)

_CLEAN_RE = re.compile(r"[^a-zA-Z']")
_VOWEL_GROUP_RE = re.compile(r"[aeiouy]+")

G2pFn = Callable[[str], list[str]]


def clean_word(word: str) -> str:
    """Lowercase letters and apostrophes only; the same cleaning the beat grid uses."""
    return _CLEAN_RE.sub("", word).lower()


class _G2pLoader:
    """Lazily constructs g2p_en once; remembers failure so the heuristic path is used."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._fn: G2pFn | None = None
        self._failed = False

    def get(self) -> G2pFn | None:
        if self._fn is not None or self._failed:
            return self._fn
        with self._lock:
            if self._fn is not None or self._failed:
                return self._fn
            try:
                ensure_resources()
                from g2p_en import G2p

                self._fn = G2p()
            except ResourceError as exc:
                logger.warning("phonetics.g2p_unavailable reason=resources error=%s", exc)
                self._failed = True
            except (ImportError, LookupError, OSError) as exc:
                logger.warning("phonetics.g2p_unavailable reason=%s error=%s", type(exc).__name__, exc)
                self._failed = True
        return self._fn

    def reset(self) -> None:
        with self._lock:
            self._fn = None
            self._failed = False


_g2p = _G2pLoader()


def g2p_available() -> bool:
    return _g2p.get() is not None


_HEURISTIC_CONSONANTS: dict[str, tuple[str, ...]] = {
    "b": ("B",), "c": ("K",), "d": ("D",), "f": ("F",), "g": ("G",), "h": ("HH",), "j": ("JH",),
    "k": ("K",), "l": ("L",), "m": ("M",), "n": ("N",), "p": ("P",), "q": ("K",), "r": ("R",),
    "s": ("S",), "t": ("T",), "v": ("V",), "w": ("W",), "x": ("K", "S"), "z": ("Z",),
}
_HEURISTIC_DIGRAPHS: dict[str, tuple[str, ...]] = {
    "ch": ("CH",), "sh": ("SH",), "th": ("TH",), "ph": ("F",), "ng": ("NG",), "ck": ("K",), "qu": ("K", "W"),
}
_HEURISTIC_VOWELS: dict[str, str] = {
    "ee": "IY", "ea": "IY", "oo": "UW", "ai": "EY", "ay": "EY", "ou": "AW", "ow": "AW", "oi": "OY", "oy": "OY",
    "a": "AE", "e": "EH", "i": "IH", "o": "AA", "u": "AH", "y": "IY",
}


def _heuristic_nucleus(group: str) -> str:
    for prefix in (group[:2], group[:1]):
        if prefix in _HEURISTIC_VOWELS:
            return _HEURISTIC_VOWELS[prefix]
    return "AH"


def heuristic_pronunciation(word: str) -> WordPronunciation:
    """Vowel-group fallback: one syllable per vowel group, first syllable stressed."""
    phones: list[Phone] = []
    i = 0
    n_vowels = 0
    while i < len(word):
        group = _VOWEL_GROUP_RE.match(word, i)
        if group:
            stress: Stress = 1 if n_vowels == 0 else 0
            phones.append(Phone(_heuristic_nucleus(group.group()), stress))
            n_vowels += 1
            i = group.end()
            continue
        digraph = word[i : i + 2]
        if digraph in _HEURISTIC_DIGRAPHS:
            phones.extend(Phone(p, None) for p in _HEURISTIC_DIGRAPHS[digraph])
            i += 2
            continue
        phones.extend(Phone(p, None) for p in _HEURISTIC_CONSONANTS.get(word[i], ()))
        i += 1
    syllables = syllabify_phones(phones)
    if not syllables:
        syllables = (Syllable(onset=tuple(p.phone for p in phones), nucleus="AH", coda=(), stress=1),)
    return WordPronunciation(word=word, syllables=syllables, source=PronunciationSource.heuristic)


def _g2p_pronunciation(word: str) -> WordPronunciation | None:
    fn = _g2p.get()
    if fn is None:
        return None
    tokens = [t for t in fn(word) if t and t.rstrip("012") in PHONES]
    if not tokens:
        return None
    syllables = syllabify_phones(parse_phones(tokens))
    if not syllables:
        return None
    return WordPronunciation(word=word, syllables=syllables, source=PronunciationSource.g2p)


@lru_cache(maxsize=131072)
def pronounce_word(word: str) -> WordPronunciation | None:
    """Pronounce one surface word; None when nothing pronounceable remains after cleaning."""
    clean = clean_word(word)
    if not clean:
        return None
    variants = pronouncing.phones_for_word(clean)
    if variants:
        syllables = syllabify_phones(parse_phones(variants[0]))
        if syllables:
            return WordPronunciation(word=clean, syllables=syllables, source=PronunciationSource.cmudict)
    via_g2p = _g2p_pronunciation(clean)
    if via_g2p is not None:
        return via_g2p
    return heuristic_pronunciation(clean)


def pronounce(text: str) -> Pronunciation:
    """Pronounce whitespace-separated words; tokens with no letters are skipped."""
    words = [w for w in (pronounce_word(token) for token in text.split()) if w is not None]
    return Pronunciation(words=tuple(words))
