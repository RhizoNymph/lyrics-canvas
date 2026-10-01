"""ARPAbet parsing and maximal-onset syllabification."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Sequence

from app.services.phonetics.distance import CONSONANTS, PHONES, VOWELS
from app.services.phonetics.errors import PronunciationError
from app.services.phonetics.types import Stress, Syllable


@dataclass(frozen=True, slots=True)
class Phone:
    phone: str
    stress: Stress | None  # set for vowels only


_SINGLE_ONSETS: frozenset[tuple[str, ...]] = frozenset((c,) for c in CONSONANTS if c != "NG")

_CLUSTER_ONSETS: frozenset[tuple[str, ...]] = frozenset(
    tuple(s.split())
    for s in (
        "P L", "P R", "P Y", "B L", "B R", "B Y", "T R", "T W", "T Y", "D R", "D W", "D Y",
        "K L", "K R", "K W", "K Y", "G L", "G R", "G W", "G Y", "F L", "F R", "F Y", "V Y",
        "TH R", "TH W", "TH Y", "SH R", "S P", "S T", "S K", "S M", "S N", "S L", "S W", "S F",
        "S Y", "HH Y", "M Y", "N Y", "L Y",
        "S P L", "S P R", "S P Y", "S T R", "S T Y", "S K R", "S K W", "S K Y", "S K L",
    )
)

LEGAL_ONSETS: frozenset[tuple[str, ...]] = _SINGLE_ONSETS | _CLUSTER_ONSETS | frozenset({()})

_INTERN: dict[str, str] = {p: p for p in PHONES}


def parse_phones(phones: str | Sequence[str]) -> tuple[Phone, ...]:
    """Parse an ARPAbet string ("K AE1 T") or token list into phones with stress split out."""
    tokens = phones.split() if isinstance(phones, str) else list(phones)
    out: list[Phone] = []
    for token in tokens:
        stress: Stress | None = None
        base = token
        if token and token[-1].isdigit():
            base = token[:-1]
            match token[-1]:
                case "0":
                    stress = 0
                case "1":
                    stress = 1
                case "2":
                    stress = 2
                case _:
                    raise PronunciationError(f"bad stress digit in phone {token!r}")
        if base not in PHONES:
            raise PronunciationError(f"unknown phone {token!r}")
        if base in VOWELS and stress is None:
            stress = 0
        if base in CONSONANTS and stress is not None:
            raise PronunciationError(f"consonant {token!r} carries a stress digit")
        out.append(Phone(_INTERN[base], stress))
    return tuple(out)


def _split_cluster(cluster: tuple[str, ...]) -> tuple[tuple[str, ...], tuple[str, ...]]:
    """Split an intervocalic consonant cluster into (coda of previous, onset of next)
    keeping the longest legal onset (maximal onset principle)."""
    for i in range(len(cluster) + 1):
        onset = cluster[i:]
        if onset in LEGAL_ONSETS:
            return cluster[:i], onset
    return cluster, ()


def syllabify_phones(phones: Sequence[Phone]) -> tuple[Syllable, ...]:
    """Group phones into syllables around each vowel using maximal onset.

    Word-initial consonants all join the first onset; word-final consonants all join the
    last coda. A phone sequence with no vowel yields no syllables.
    """
    vowel_positions = [i for i, p in enumerate(phones) if p.phone in VOWELS]
    if not vowel_positions:
        return ()
    syllables: list[Syllable] = []
    onset = tuple(p.phone for p in phones[: vowel_positions[0]])
    for n, pos in enumerate(vowel_positions):
        nucleus = phones[pos]
        if n + 1 < len(vowel_positions):
            cluster = tuple(p.phone for p in phones[pos + 1 : vowel_positions[n + 1]])
            coda, next_onset = _split_cluster(cluster)
        else:
            coda = tuple(p.phone for p in phones[pos + 1 :])
            next_onset = ()
        stress: Stress = nucleus.stress if nucleus.stress is not None else 0
        syllables.append(Syllable(onset=onset, nucleus=nucleus.phone, coda=coda, stress=stress))
        onset = next_onset
    return tuple(syllables)


def flatten(syllables: Sequence[Syllable]) -> tuple[Phone, ...]:
    """Inverse of syllabify_phones: the phone sequence with vowel stresses attached."""
    out: list[Phone] = []
    for s in syllables:
        out.extend(Phone(p, None) for p in s.onset)
        out.append(Phone(s.nucleus, s.stress))
        out.extend(Phone(p, None) for p in s.coda)
    return tuple(out)
