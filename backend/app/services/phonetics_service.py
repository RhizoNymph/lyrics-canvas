"""Snippet phonetics: compute the ``phonetics`` column from raw text and the beat grid.

The beat grid (``grid_data``) is the user's view; where its stress markers disagree with the
engine the grid wins and the difference is stored as a pronunciation override, so the two
never disagree for a stored snippet.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Mapping, Sequence

from app.schemas.phonetics import (
    LinePhonetics,
    PronunciationOverride,
    SnippetPhonetics,
    SyllableModel,
    WordPhonetics,
    override_key,
)
from app.services.phonetics import DEFAULT_DIALECT_MAP, build_unit, pronounce_word
from app.services.phonetics.dialect import DialectMap
from app.services.phonetics.pronounce import clean_word
from app.services.phonetics.types import (
    Pronunciation,
    PronunciationSource,
    Provenance,
    Stress,
    Syllable,
    Unit,
    WordPronunciation,
)
from app.services.phonetics.units import _derive, note_unit_id

FILLER_NUCLEUS = "AH"


@dataclass(frozen=True, slots=True)
class Disagreement:
    line_index: int
    word_index: int
    word: str
    grid_stress: str
    engine_stress: str


# --- conversions between engine types and stored models ---


def syllable_to_model(s: Syllable) -> SyllableModel:
    return SyllableModel(onset=list(s.onset), nucleus=s.nucleus, coda=list(s.coda), stress=s.stress)


def syllable_from_model(m: SyllableModel) -> Syllable:
    return Syllable(onset=tuple(m.onset), nucleus=m.nucleus, coda=tuple(m.coda), stress=m.stress)


def word_from_model(m: WordPhonetics) -> WordPronunciation:
    return WordPronunciation(
        word=m.word,
        syllables=tuple(syllable_from_model(s) for s in m.syllables),
        source=PronunciationSource(m.source),
    )


def pronunciation_from_line(line: LinePhonetics, word_offset: int = 0, word_end: int | None = None) -> Pronunciation:
    """The stored (override-applied) pronunciation of ``line.words[word_offset:word_end]``."""
    return Pronunciation(words=tuple(word_from_model(w) for w in line.words[word_offset:word_end]))


# --- reconciliation ---


def _grid_stresses(cells: Sequence[Mapping[str, object]]) -> dict[int, list[Stress]]:
    by_word: dict[int, list[Stress]] = {}
    for cell in cells:
        if cell.get("rest"):
            continue
        word_index = cell.get("word_index")
        if not isinstance(word_index, int):
            continue
        raw = cell.get("stress", 0)
        stress: Stress
        match raw:
            case 1:
                stress = 1
            case 2:
                stress = 2
            case _:
                stress = 0
        by_word.setdefault(word_index, []).append(stress)
    return by_word


def _restress(syllables: Sequence[Syllable], stresses: Sequence[Stress]) -> tuple[Syllable, ...]:
    """Syllables carrying the grid's stresses, right-aligned when the counts differ."""
    n = len(stresses)
    base = list(syllables[-n:]) if n <= len(syllables) else list(syllables)
    missing = n - len(base)
    filler = [Syllable(onset=(), nucleus=FILLER_NUCLEUS, coda=(), stress=0) for _ in range(missing)]
    aligned = filler + base
    return tuple(
        Syllable(onset=s.onset, nucleus=s.nucleus, coda=s.coda, stress=st, deletable_coda=s.deletable_coda)
        for s, st in zip(aligned, stresses)
    )


def reconcile(
    cells: Sequence[Mapping[str, object]], pronunciation: Pronunciation, line_index: int = 0
) -> tuple[dict[int, tuple[Syllable, ...]], tuple[Disagreement, ...]]:
    """Compare grid stress per word with the pronunciation; grid wins.

    Returns overrides keyed by word index (syllables with the grid's stresses) and the list of
    disagreements. Words without grid cells are left alone.
    """
    grid = _grid_stresses(cells)
    overrides: dict[int, tuple[Syllable, ...]] = {}
    disagreements: list[Disagreement] = []
    for word_index, word in enumerate(pronunciation.words):
        wanted = grid.get(word_index)
        if wanted is None or not word.syllables:
            continue
        engine = [s.stress for s in word.syllables]
        if engine == wanted:
            continue
        overrides[word_index] = _restress(word.syllables, wanted)
        disagreements.append(
            Disagreement(
                line_index=line_index,
                word_index=word_index,
                word=word.word,
                grid_stress="".join(map(str, wanted)),
                engine_stress="".join(map(str, engine)),
            )
        )
    return overrides, tuple(disagreements)


# --- snippet-level computation ---


def split_lines(raw_text: str) -> list[str]:
    """The same line splitting ``syllabify_text`` uses, so line indexes match the grid."""
    return [line.strip() for line in raw_text.strip().split("\n") if line.strip()]


def _engine_word(token: str) -> WordPronunciation:
    pron = pronounce_word(token)
    if pron is None:
        return WordPronunciation(word=clean_word(token), syllables=(), source=PronunciationSource.heuristic)
    return pron


def _override_word(word: str, syllables: tuple[Syllable, ...]) -> WordPronunciation:
    return WordPronunciation(word=word, syllables=syllables, source=PronunciationSource.override)


def _line_model(line_index: int, pron: Pronunciation, dialect: DialectMap) -> LinePhonetics:
    derived = _derive(pron, dialect)
    return LinePhonetics(
        line_index=line_index,
        words=[
            WordPhonetics(word=w.word, source=w.source.value, syllables=[syllable_to_model(s) for s in w.syllables])
            for w in pron.words
        ],
        stress=derived.stress,
        syllable_count=derived.syllable_count,
        rhyme_key=list(derived.rhyme_key),
        rhyme_keys={str(d): list(key) for d, key in derived.rhyme_keys.items()},
    )


def _final_word(
    engine: WordPronunciation,
    existing: PronunciationOverride | None,
    grid_override: tuple[Syllable, ...] | None,
    grid_stress: Sequence[Stress] | None,
) -> tuple[WordPronunciation, tuple[Syllable, ...] | None]:
    """Pick the stored pronunciation of one word and the override to persist (if any).

    Grid stress always wins. An existing override survives only while it still differs from
    the engine after the grid's stresses are applied to it (custom phones, or stress the grid
    still disagrees with).
    """
    if existing is None:
        if grid_override is None:
            return engine, None
        return _override_word(engine.word, grid_override), grid_override
    kept = tuple(syllable_from_model(s) for s in existing.syllables)
    if grid_stress is not None and kept:
        kept = _restress(kept, grid_stress)
    if kept == engine.syllables:
        return engine, None
    return _override_word(engine.word, kept), kept


def compute_snippet_phonetics_with_report(
    raw_text: str,
    grid_data: Mapping[str, object] | None,
    existing_overrides: Mapping[str, PronunciationOverride] | None,
    dialect: DialectMap = DEFAULT_DIALECT_MAP,
) -> tuple[SnippetPhonetics, list[Disagreement]]:
    existing = dict(existing_overrides or {})
    grid_lines = list(grid_data.get("lines", [])) if grid_data else []  # type: ignore[union-attr]
    lines: list[LinePhonetics] = []
    overrides: dict[str, PronunciationOverride] = {}
    report: list[Disagreement] = []
    for line_index, text in enumerate(split_lines(raw_text)):
        engine_words = [_engine_word(token) for token in text.split()]
        engine = Pronunciation(words=tuple(engine_words))
        cells = grid_lines[line_index].get("cells", []) if line_index < len(grid_lines) else []
        grid_stress = _grid_stresses(cells)
        grid_overrides, disagreements = reconcile(cells, engine, line_index)
        final_words: list[WordPronunciation] = []
        for word_index, word in enumerate(engine_words):
            key = override_key(line_index, word_index)
            final, override = _final_word(word, existing.get(key), grid_overrides.get(word_index), grid_stress.get(word_index))
            if override is not None:
                overrides[key] = PronunciationOverride(syllables=[syllable_to_model(s) for s in override])
            final_words.append(final)
        # report grid-vs-engine differences that no existing override already encodes
        for d in disagreements:
            previous = existing.get(override_key(line_index, d.word_index))
            if previous is None or "".join(str(s.stress) for s in previous.syllables) != d.grid_stress:
                report.append(d)
        lines.append(_line_model(line_index, Pronunciation(words=tuple(final_words)), dialect))
    return SnippetPhonetics(dialect_digest=dialect.digest(), lines=lines, overrides=overrides), report


def compute_snippet_phonetics(
    raw_text: str,
    grid_data: Mapping[str, object] | None,
    existing_overrides: Mapping[str, PronunciationOverride] | None,
    dialect: DialectMap = DEFAULT_DIALECT_MAP,
) -> SnippetPhonetics:
    phonetics, _ = compute_snippet_phonetics_with_report(raw_text, grid_data, existing_overrides, dialect)
    return phonetics


# --- units for the notes source and anchors ---


def _has_override(line: LinePhonetics, word_offset: int, word_end: int | None) -> bool:
    return any(w.source == "override" for w in line.words[word_offset:word_end])


def note_unit(
    snippet_id: str,
    line: LinePhonetics,
    word_offset: int,
    dialect: DialectMap,
    tags: Sequence[str] = (),
    word_end: int | None = None,
) -> Unit | None:
    """One suffix unit of a stored line (or, with ``word_end``, one word range of it).

    None when nothing in the range is pronounceable. Range units get a ``<start>-<end>`` id
    and carry ``word_end``; suffix units keep the plain ``<offset>`` id and ``word_end=None``.
    """
    pron = pronunciation_from_line(line, word_offset, word_end)
    if not pron.syllables:
        return None
    text = " ".join(w.word for w in line.words[word_offset:word_end] if w.word)
    return build_unit(
        id=note_unit_id(snippet_id, line.line_index, word_offset, word_end),
        text=text,
        pronunciation=pron,
        provenance=Provenance.note,
        dialect=dialect,
        ephemeral=False,
        pronunciation_override=pron if _has_override(line, word_offset, word_end) else None,
        tags=tags,
        snippet_id=snippet_id,
        line_index=line.line_index,
        word_offset=word_offset,
        word_end=word_end,
    )


def note_units_from_phonetics(
    snippet_id: str, phonetics: SnippetPhonetics, dialect: DialectMap, tags: Sequence[str] = ()
) -> list[Unit]:
    """Every line and every word-boundary suffix of it as ``note:`` units."""
    units: list[Unit] = []
    for line in phonetics.lines:
        for word_offset in range(len(line.words)):
            unit = note_unit(snippet_id, line, word_offset, dialect, tags)
            if unit is not None:
                units.append(unit)
    return units
