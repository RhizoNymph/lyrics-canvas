"""Dictionary source: a process-wide RhymeIndex over the CMU dictionary, built lazily."""

from __future__ import annotations

import logging
import re
import threading
import time
from collections import OrderedDict
from functools import lru_cache
from typing import Sequence

import pronouncing
from wordfreq import iter_wordlist, zipf_frequency

from app.services.phonetics.dialect import DEFAULT_DIALECT_MAP, DialectMap
from app.services.phonetics.errors import PronunciationError
from app.services.phonetics.index import RhymeIndex
from app.services.phonetics.score import ScoreWeights
from app.services.phonetics.sources.base import Candidate, SourceName, suggest_from_index
from app.services.phonetics.syllables import parse_phones, syllabify_phones
from app.services.phonetics.types import Pronunciation, PronunciationSource, Provenance, Unit, WordPronunciation
from app.services.phonetics.units import build_unit, dict_unit_id

logger = logging.getLogger(__name__)

RARE_ZIPF_THRESHOLD = 3.0
_WORD_RE = re.compile(r"^[a-z]+(?:'[a-z]+)*'?$")
_MAX_CACHED_INDEXES = 4


@lru_cache(maxsize=1)
def common_words() -> frozenset[str]:
    """English words at or above the Zipf frequency threshold (wordfreq, offline)."""
    words: set[str] = set()
    for word in iter_wordlist("en"):
        if zipf_frequency(word, "en") < RARE_ZIPF_THRESHOLD:
            break
        words.add(word)
    return frozenset(words)


def dictionary_entries(include_rare: bool) -> list[tuple[str, str]]:
    """(word, phones) for CMU entries that are plain words (no variants, digits or symbols)."""
    pronouncing.init_cmu()
    keep = None if include_rare else common_words()
    seen: set[str] = set()
    entries: list[tuple[str, str]] = []
    for word, phones in pronouncing.pronunciations:
        if word in seen or not _WORD_RE.match(word):
            continue
        if keep is not None and word not in keep:
            continue
        seen.add(word)
        entries.append((word, phones))
    return entries


def build_dictionary_index(dialect: DialectMap, include_rare: bool) -> RhymeIndex:
    started = time.perf_counter()
    index = RhymeIndex(dialect)
    skipped = 0
    for word, phones in dictionary_entries(include_rare):
        try:
            syllables = syllabify_phones(parse_phones(phones))
            if not syllables:
                skipped += 1
                continue
            pron = Pronunciation(words=(WordPronunciation(word, syllables, PronunciationSource.cmudict),))
            index.insert(
                build_unit(id=dict_unit_id(word), text=word, pronunciation=pron, provenance=Provenance.dictionary, dialect=dialect)
            )
        except PronunciationError:
            skipped += 1
    logger.info(
        "phonetics.dictionary.built units=%d skipped=%d include_rare=%s dialect=%s elapsed_ms=%.0f",
        len(index), skipped, include_rare, dialect.digest()[:8], (time.perf_counter() - started) * 1000,
    )
    return index


class _IndexRegistry:
    """Bounded LRU of built dictionary indexes keyed by (dialect digest, include_rare)."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._indexes: OrderedDict[tuple[str, bool], RhymeIndex] = OrderedDict()

    def get(self, dialect: DialectMap, include_rare: bool) -> RhymeIndex:
        key = (dialect.digest(), include_rare)
        with self._lock:
            found = self._indexes.get(key)
            if found is not None:
                self._indexes.move_to_end(key)
                return found
            built = build_dictionary_index(dialect, include_rare)
            self._indexes[key] = built
            while len(self._indexes) > _MAX_CACHED_INDEXES:
                self._indexes.popitem(last=False)
            return built


_registry = _IndexRegistry()


class DictionarySource:
    name: SourceName = "dictionary"

    def __init__(
        self,
        dialect: DialectMap = DEFAULT_DIALECT_MAP,
        weights: ScoreWeights | None = None,
        include_rare: bool = False,
    ) -> None:
        self._dialect = dialect
        self._weights = weights
        self._include_rare = include_rare

    def index(self, include_rare: bool | None = None) -> RhymeIndex:
        return _registry.get(self._dialect, self._include_rare if include_rare is None else include_rare)

    def suggest(
        self, anchors: Sequence[Unit], k: int, min_score: float, limit: int, exclude: frozenset[str]
    ) -> list[Candidate]:
        return suggest_from_index(self.index(), self.name, anchors, k, min_score, limit, exclude, self._weights)
