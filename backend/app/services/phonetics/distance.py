"""Articulatory feature vectors per ARPAbet phone and the phone distance in [0, 1].

Vowels carry height, backness, rounding, tenseness and diphthong offglide; consonants
carry place, manner and voicing. A vowel and a consonant are at distance 1. A small
override table nudges vowel pairs that the Hirjee & Brown (2010) rap-lyrics substitution
matrix rates closer than the raw feature count (AH/ER, IH/IY, AH/IH, AA/AO).
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import IntEnum
from functools import lru_cache


@dataclass(frozen=True, slots=True)
class VowelFeatures:
    height: float  # 0 high .. 1 low
    backness: float  # 0 front .. 1 back
    rounded: float
    tense: float
    glide: float  # 1 for diphthongs
    glide_back: float  # offglide target: 0 front (IY), 1 back (UW)


class Place(IntEnum):
    bilabial = 0
    labiodental = 1
    dental = 2
    alveolar = 3
    postalveolar = 4
    palatal = 5
    velar = 6
    glottal = 7


class Manner(IntEnum):
    stop = 0
    affricate = 1
    fricative = 2
    nasal = 3
    liquid = 4
    glide = 5


@dataclass(frozen=True, slots=True)
class ConsonantFeatures:
    place: Place
    manner: Manner
    voiced: float


VOWELS: dict[str, VowelFeatures] = {
    "IY": VowelFeatures(0.00, 0.00, 0, 1, 0, 0),
    "IH": VowelFeatures(0.20, 0.15, 0, 0, 0, 0),
    "EY": VowelFeatures(0.35, 0.00, 0, 1, 1, 0),
    "EH": VowelFeatures(0.50, 0.10, 0, 0, 0, 0),
    "AE": VowelFeatures(0.80, 0.10, 0, 0, 0, 0),
    "AA": VowelFeatures(1.00, 0.90, 0, 1, 0, 0),
    "AO": VowelFeatures(0.70, 1.00, 1, 1, 0, 0),
    "AH": VowelFeatures(0.50, 0.60, 0, 0, 0, 0),
    "ER": VowelFeatures(0.50, 0.50, 0, 0, 0, 0),
    "UH": VowelFeatures(0.20, 0.85, 1, 0, 0, 0),
    "UW": VowelFeatures(0.00, 1.00, 1, 1, 0, 0),
    "OW": VowelFeatures(0.40, 1.00, 1, 1, 1, 1),
    "AY": VowelFeatures(1.00, 0.50, 0, 1, 1, 0),
    "AW": VowelFeatures(1.00, 0.60, 0, 1, 1, 1),
    "OY": VowelFeatures(0.60, 1.00, 1, 1, 1, 0),
}

CONSONANTS: dict[str, ConsonantFeatures] = {
    "P": ConsonantFeatures(Place.bilabial, Manner.stop, 0),
    "B": ConsonantFeatures(Place.bilabial, Manner.stop, 1),
    "T": ConsonantFeatures(Place.alveolar, Manner.stop, 0),
    "D": ConsonantFeatures(Place.alveolar, Manner.stop, 1),
    "K": ConsonantFeatures(Place.velar, Manner.stop, 0),
    "G": ConsonantFeatures(Place.velar, Manner.stop, 1),
    "CH": ConsonantFeatures(Place.postalveolar, Manner.affricate, 0),
    "JH": ConsonantFeatures(Place.postalveolar, Manner.affricate, 1),
    "F": ConsonantFeatures(Place.labiodental, Manner.fricative, 0),
    "V": ConsonantFeatures(Place.labiodental, Manner.fricative, 1),
    "TH": ConsonantFeatures(Place.dental, Manner.fricative, 0),
    "DH": ConsonantFeatures(Place.dental, Manner.fricative, 1),
    "S": ConsonantFeatures(Place.alveolar, Manner.fricative, 0),
    "Z": ConsonantFeatures(Place.alveolar, Manner.fricative, 1),
    "SH": ConsonantFeatures(Place.postalveolar, Manner.fricative, 0),
    "ZH": ConsonantFeatures(Place.postalveolar, Manner.fricative, 1),
    "HH": ConsonantFeatures(Place.glottal, Manner.fricative, 0),
    "M": ConsonantFeatures(Place.bilabial, Manner.nasal, 1),
    "N": ConsonantFeatures(Place.alveolar, Manner.nasal, 1),
    "NG": ConsonantFeatures(Place.velar, Manner.nasal, 1),
    "L": ConsonantFeatures(Place.alveolar, Manner.liquid, 1),
    "R": ConsonantFeatures(Place.postalveolar, Manner.liquid, 1),
    "W": ConsonantFeatures(Place.bilabial, Manner.glide, 1),
    "Y": ConsonantFeatures(Place.palatal, Manner.glide, 1),
}

PHONES: frozenset[str] = frozenset(VOWELS) | frozenset(CONSONANTS)

# Feature weights. Vowel weights sum above 1 so that distinct vowels spread out; the
# result is capped at 1.
_V_HEIGHT = 0.5
_V_BACK = 0.6
_V_ROUND = 0.15
_V_TENSE = 0.2
_V_GLIDE = 0.3
_V_GLIDE_BACK = 0.2

_C_MANNER = 0.45
_C_PLACE = 0.35
_C_VOICE = 0.2

_MANNER_DISTANCE: dict[frozenset[Manner], float] = {
    frozenset({Manner.stop, Manner.affricate}): 0.3,
    frozenset({Manner.affricate, Manner.fricative}): 0.3,
    frozenset({Manner.stop, Manner.fricative}): 0.6,
    frozenset({Manner.stop, Manner.nasal}): 0.6,
    frozenset({Manner.nasal, Manner.fricative}): 0.8,
    frozenset({Manner.nasal, Manner.affricate}): 0.8,
    frozenset({Manner.liquid, Manner.glide}): 0.4,
    frozenset({Manner.nasal, Manner.liquid}): 0.7,
    frozenset({Manner.fricative, Manner.liquid}): 0.8,
    frozenset({Manner.fricative, Manner.glide}): 0.8,
    frozenset({Manner.nasal, Manner.glide}): 0.9,
}

# Symmetric vowel overrides (Hirjee & Brown 2010 substitution weights).
_VOWEL_OVERRIDES: dict[frozenset[str], float] = {
    frozenset({"AH", "ER"}): 0.2,
    frozenset({"IH", "IY"}): 0.25,
    frozenset({"AH", "IH"}): 0.3,
    frozenset({"AA", "AO"}): 0.15,
    frozenset({"AH", "UH"}): 0.35,
}

NEIGHBOUR_THRESHOLD = 0.35


def is_vowel(phone: str) -> bool:
    return phone in VOWELS


def _vowel_distance(a: VowelFeatures, b: VowelFeatures) -> float:
    d = (
        _V_HEIGHT * abs(a.height - b.height)
        + _V_BACK * abs(a.backness - b.backness)
        + _V_ROUND * abs(a.rounded - b.rounded)
        + _V_TENSE * abs(a.tense - b.tense)
        + _V_GLIDE * abs(a.glide - b.glide)
    )
    if a.glide and b.glide:
        d += _V_GLIDE_BACK * abs(a.glide_back - b.glide_back)
    return min(1.0, d)


def _consonant_distance(a: ConsonantFeatures, b: ConsonantFeatures) -> float:
    manner = 0.0 if a.manner == b.manner else _MANNER_DISTANCE.get(frozenset({a.manner, b.manner}), 1.0)
    place = min(abs(int(a.place) - int(b.place)), 3) / 3
    d = _C_MANNER * manner + _C_PLACE * place + _C_VOICE * abs(a.voiced - b.voiced)
    return min(1.0, d)


def _compute_distance(a: str, b: str) -> float:
    if a == b:
        return 0.0
    match (a in VOWELS, b in VOWELS):
        case (True, True):
            override = _VOWEL_OVERRIDES.get(frozenset({a, b}))
            return override if override is not None else _vowel_distance(VOWELS[a], VOWELS[b])
        case (False, False):
            return _consonant_distance(CONSONANTS[a], CONSONANTS[b])
        case _:
            return 1.0


_TABLE: dict[tuple[str, str], float] = {
    (a, b): _compute_distance(a, b) for a in PHONES for b in PHONES
}


def phone_distance(a: str, b: str) -> float:
    """Articulatory distance between two phones in [0, 1]; unknown phones are maximally far."""
    return _TABLE.get((a, b), 1.0)


@lru_cache(maxsize=None)
def vowel_neighbours(vowel: str) -> tuple[str, ...]:
    """The vowel itself plus every vowel within NEIGHBOUR_THRESHOLD, sorted."""
    if vowel not in VOWELS:
        return (vowel,)
    return tuple(sorted(v for v in VOWELS if phone_distance(vowel, v) <= NEIGHBOUR_THRESHOLD))
