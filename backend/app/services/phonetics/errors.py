"""Typed exception hierarchy for the phonetic engine."""


class PhoneticsError(Exception):
    """Base class for every error raised by the phonetics package."""


class PronunciationError(PhoneticsError):
    """A word or phone sequence could not be turned into a pronunciation."""


class DialectRuleError(PhoneticsError):
    """A dialect rule is malformed (bad phones, vowel count mismatch, wrong kind)."""


class RhymeQueryError(PhoneticsError):
    """A rhyme query has invalid parameters (k, limit, min_score, stress, empty anchors)."""


class RhymeIndexError(PhoneticsError):
    """A unit cannot be inserted into an index (dialect mismatch, no syllables)."""


class ResourceError(PhoneticsError):
    """External resources (NLTK data for g2p_en) could not be installed."""
