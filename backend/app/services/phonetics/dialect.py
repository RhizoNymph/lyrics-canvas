"""Dialect map: phone rewrites applied after pronunciation and before any key or score."""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, replace
from enum import StrEnum

from app.services.phonetics.distance import CONSONANTS, PHONES, VOWELS
from app.services.phonetics.errors import DialectRuleError
from app.services.phonetics.syllables import Phone, flatten, syllabify_phones
from app.services.phonetics.types import Pronunciation, Syllable, WordPronunciation


class RuleKind(StrEnum):
    merge = "merge"
    final_merge = "final_merge"
    optional_final = "optional_final"


@dataclass(frozen=True, slots=True)
class DialectRule:
    kind: RuleKind
    source: tuple[str, ...]
    target: tuple[str, ...]

    def validate(self) -> None:
        if not self.source:
            raise DialectRuleError("rule source must not be empty")
        for phone in (*self.source, *self.target):
            if phone not in PHONES:
                raise DialectRuleError(f"unknown phone {phone!r} in dialect rule")
        source_vowels = sum(p in VOWELS for p in self.source)
        target_vowels = sum(p in VOWELS for p in self.target)
        match self.kind:
            case RuleKind.merge | RuleKind.final_merge:
                if not self.target:
                    raise DialectRuleError("merge target must not be empty")
                if source_vowels > 1:
                    raise DialectRuleError("merge source may contain at most one vowel")
                if source_vowels != target_vowels:
                    raise DialectRuleError("merge must keep the vowel count (syllable count) unchanged")
            case RuleKind.optional_final:
                if len(self.source) != 1 or self.source[0] not in CONSONANTS:
                    raise DialectRuleError("optional_final source must be exactly one consonant")
                if self.target:
                    raise DialectRuleError("optional_final takes no target")


@dataclass(frozen=True, slots=True)
class DialectMap:
    rules: tuple[DialectRule, ...]

    def __post_init__(self) -> None:
        for rule in self.rules:
            rule.validate()

    def digest(self) -> str:
        canonical = json.dumps(
            [[r.kind.value, list(r.source), list(r.target)] for r in self.rules], separators=(",", ":")
        )
        return hashlib.sha1(canonical.encode()).hexdigest()

    def apply(self, pron: Pronunciation) -> Pronunciation:
        if not self.rules:
            return pron
        return Pronunciation(words=tuple(self._apply_word(w) for w in pron.words))

    def _apply_word(self, word: WordPronunciation) -> WordPronunciation:
        phones = list(flatten(word.syllables))
        for rule in self.rules:
            match rule.kind:
                case RuleKind.merge:
                    phones = _merge(phones, rule, final_only=False)
                case RuleKind.final_merge:
                    phones = _merge(phones, rule, final_only=True)
                case RuleKind.optional_final:
                    pass
        syllables = syllabify_phones(phones)
        if syllables:
            last = syllables[-1]
            for rule in self.rules:
                if rule.kind is RuleKind.optional_final and _final_deletable(last, rule.source[0]):
                    syllables = (*syllables[:-1], replace(last, deletable_coda=1))
                    break
        return WordPronunciation(word=word.word, syllables=syllables, source=word.source)


def _final_deletable(syllable: Syllable, phone: str) -> bool:
    coda = syllable.coda
    return len(coda) >= 2 and coda[-1] == phone and coda[-2] in CONSONANTS


def _merge(phones: list[Phone], rule: DialectRule, *, final_only: bool) -> list[Phone]:
    src = rule.source
    n = len(src)
    if n > len(phones):
        return phones

    def replacement(window: list[Phone]) -> list[Phone]:
        stress = next((p.stress for p in window if p.phone in VOWELS), None)
        return [Phone(p, stress if p in VOWELS else None) for p in rule.target]

    if final_only:
        if tuple(p.phone for p in phones[-n:]) == src:
            return phones[:-n] + replacement(phones[-n:])
        return phones

    out: list[Phone] = []
    i = 0
    while i < len(phones):
        if tuple(p.phone for p in phones[i : i + n]) == src:
            out.extend(replacement(phones[i : i + n]))
            i += n
        else:
            out.append(phones[i])
            i += 1
    return out


DEFAULT_DIALECT_MAP = DialectMap(
    rules=(
        DialectRule(RuleKind.final_merge, ("IH", "NG"), ("IH", "N")),
        DialectRule(RuleKind.optional_final, ("T",), ()),
        DialectRule(RuleKind.optional_final, ("D",), ()),
        DialectRule(RuleKind.merge, ("ER",), ("AH", "R")),
        DialectRule(RuleKind.merge, ("AO",), ("AA",)),
    )
)
