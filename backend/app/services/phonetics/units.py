"""Unit construction: the only place derived phonetic fields are computed."""

from __future__ import annotations

import hashlib
from dataclasses import dataclass
from functools import lru_cache
from types import MappingProxyType
from typing import Sequence

from app.services.phonetics.dialect import DialectMap
from app.services.phonetics.errors import PronunciationError
from app.services.phonetics.pronounce import pronounce
from app.services.phonetics.score import MAX_K
from app.services.phonetics.types import DerivedFrom, Pronunciation, Provenance, RhymeKey, Syllable, Unit


def dict_unit_id(word: str) -> str:
    return f"dict:{word}"


def note_unit_id(snippet_id: str, line_index: int, word_offset: int) -> str:
    return f"note:{snippet_id}:{line_index}:{word_offset}"


def text_unit_id(text: str) -> str:
    return "text:" + hashlib.sha1(" ".join(text.lower().split()).encode()).hexdigest()


def tail_key(syllables: Sequence[Syllable], depth: int) -> RhymeKey:
    """Phones from the nucleus of the ``depth``-th syllable from the end to the end."""
    tail = syllables[-depth:]
    key: list[str] = [tail[0].nucleus, *tail[0].coda]
    for s in tail[1:]:
        key.extend(s.phones)
    return tuple(key)


def stress_string(syllables: Sequence[Syllable]) -> str:
    return "".join(str(s.stress) for s in syllables)


def rhyme_key_depth(syllables: Sequence[Syllable]) -> int:
    """Syllables from the last primary stress (else last secondary, else the last one) to the end."""
    for wanted in (1, 2):
        for i in range(len(syllables) - 1, -1, -1):
            if syllables[i].stress == wanted:
                return len(syllables) - i
    return 1


@dataclass(frozen=True, slots=True)
class _Derived:
    effective: Pronunciation
    stress: str
    syllable_count: int
    rhyme_key: RhymeKey
    rhyme_keys: MappingProxyType[int, RhymeKey]


@lru_cache(maxsize=262144)
def _derive(base: Pronunciation, dialect: DialectMap) -> _Derived:
    effective = dialect.apply(base)
    syllables = effective.syllables
    n = len(syllables)
    if n == 0:
        return _Derived(effective, "", 0, (), MappingProxyType({}))
    keys = {d: tail_key(syllables, d) for d in range(1, min(MAX_K, n) + 1)}
    return _Derived(
        effective=effective,
        stress=stress_string(syllables),
        syllable_count=n,
        rhyme_key=tail_key(syllables, rhyme_key_depth(syllables)),
        rhyme_keys=MappingProxyType(keys),
    )


def build_unit(
    *,
    id: str,
    text: str,
    pronunciation: Pronunciation,
    provenance: Provenance,
    dialect: DialectMap,
    ephemeral: bool = False,
    pronunciation_override: Pronunciation | None = None,
    tags: Sequence[str] = (),
    derived_from: DerivedFrom | None = None,
    snippet_id: str | None = None,
    line_index: int | None = None,
    word_offset: int | None = None,
) -> Unit:
    base = pronunciation_override if pronunciation_override is not None else pronunciation
    derived = _derive(base, dialect)
    if derived.syllable_count == 0:
        raise PronunciationError(f"unit {id!r} ({text!r}) has no syllables")
    return Unit(
        id=id,
        text=text,
        pronunciation=pronunciation,
        provenance=provenance,
        ephemeral=ephemeral,
        pronunciation_override=pronunciation_override,
        tags=tuple(tags),
        derived_from=derived_from,
        snippet_id=snippet_id,
        line_index=line_index,
        word_offset=word_offset,
        effective_pronunciation=derived.effective,
        dialect_digest=dialect.digest(),
        stress=derived.stress,
        syllable_count=derived.syllable_count,
        rhyme_key=derived.rhyme_key,
        rhyme_keys=derived.rhyme_keys,
    )


def unit_from_text(
    text: str,
    dialect: DialectMap,
    *,
    id: str | None = None,
    provenance: Provenance = Provenance.generated,
    ephemeral: bool = True,
    tags: Sequence[str] = (),
) -> Unit:
    """Pronounce ``text`` and build a unit; id defaults to ``text:<sha1>``."""
    return build_unit(
        id=id or text_unit_id(text),
        text=text,
        pronunciation=pronounce(text),
        provenance=provenance,
        dialect=dialect,
        ephemeral=ephemeral,
        tags=tags,
    )
