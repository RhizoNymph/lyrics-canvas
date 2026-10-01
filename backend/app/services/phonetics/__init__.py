"""Phonetic engine: pronunciation, dialect map, rhyme scoring and index.

Pure module: no UI or DB dependencies.
"""

from app.services.phonetics.dialect import DEFAULT_DIALECT_MAP, DialectMap, DialectRule, RuleKind
from app.services.phonetics.errors import (
    DialectRuleError,
    PhoneticsError,
    PronunciationError,
    ResourceError,
    RhymeIndexError,
    RhymeQueryError,
)
from app.services.phonetics.index import IndexHit, RhymeIndex
from app.services.phonetics.pronounce import pronounce, pronounce_word
from app.services.phonetics.resources import ensure_resources
from app.services.phonetics.score import RhymeScore, ScoreWeights, SyllableAlignment, score
from app.services.phonetics.types import (
    DerivedFrom,
    Pronunciation,
    PronunciationSource,
    Provenance,
    RhymeKey,
    Syllable,
    Unit,
    WordPronunciation,
)
from app.services.phonetics.units import build_unit, unit_from_text

__all__ = [
    "DEFAULT_DIALECT_MAP",
    "DerivedFrom",
    "DialectMap",
    "DialectRule",
    "DialectRuleError",
    "IndexHit",
    "PhoneticsError",
    "Pronunciation",
    "PronunciationError",
    "PronunciationSource",
    "Provenance",
    "ResourceError",
    "RhymeIndex",
    "RhymeIndexError",
    "RhymeKey",
    "RhymeQueryError",
    "RhymeScore",
    "RuleKind",
    "ScoreWeights",
    "Syllable",
    "SyllableAlignment",
    "Unit",
    "WordPronunciation",
    "build_unit",
    "ensure_resources",
    "pronounce",
    "pronounce_word",
    "score",
    "unit_from_text",
]
