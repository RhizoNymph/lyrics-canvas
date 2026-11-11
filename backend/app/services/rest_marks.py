"""Rest marks: ``_`` in snippet text is an intentional empty beat (docs/features/rest_marks.md).

Every ``_`` is one rest and also separates words; whitespace separates tokens; punctuation stays part
of its word. ``word_index`` counts word tokens only, so rests never shift word indexes. The rules are
pinned by tests/fixtures/rest_mark_vectors.json, which the web and Android clients copy byte for byte.

- ``tokenize_line`` / ``word_tokens``: what the syllabifier and phonetics read per line.
- ``display_text``: the text without rest marks, read by export, similarity, embeddings, t-SNE,
  rhyme, the merge word count and search.
- ``rebuild_line_text``: the canonical text of a grid line (the clients' ``rebuildRawText``).
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import TypeAlias

REST_MARK = "_"
LINE_BREAK = "\n"


@dataclass(frozen=True, slots=True)
class Word:
    text: str  # never empty, never contains whitespace or REST_MARK


@dataclass(frozen=True, slots=True)
class Rest:
    pass


LineToken: TypeAlias = Word | Rest


def tokenize_line(line: str) -> tuple[LineToken, ...]:
    """Replace each ``_`` with `` _ ``, split on any whitespace, map ``_`` pieces to rests."""
    pieces = line.replace(REST_MARK, f" {REST_MARK} ").split()
    return tuple(Rest() if piece == REST_MARK else Word(piece) for piece in pieces)


def word_tokens(line: str) -> tuple[str, ...]:
    """The words of one line, in order; position in this tuple is the ``word_index``."""
    return tuple(token.text for token in tokenize_line(line) if isinstance(token, Word))


def word_index_per_token(line: str) -> tuple[int | None, ...]:
    """For each token of the line: its word index, or None for a rest."""
    indexes: list[int | None] = []
    next_index = 0
    for token in tokenize_line(line):
        match token:
            case Word():
                indexes.append(next_index)
                next_index += 1
            case Rest():
                indexes.append(None)
    return tuple(indexes)


def has_rest(line: str) -> bool:
    return REST_MARK in line


def display_lines(text: str) -> list[str]:
    """Each line's words joined by single spaces; lines with a rest and no words are dropped, other
    empty lines (stanza breaks) are kept."""
    lines: list[str] = []
    for line in text.split(LINE_BREAK):
        words = word_tokens(line)
        if not words and has_rest(line):
            continue
        lines.append(" ".join(words))
    return lines


def display_text(text: str) -> str:
    return LINE_BREAK.join(display_lines(text))


def _word_spelling(cells: Sequence[Mapping[str, object]], word_index: int) -> str:
    for cell in cells:
        word = cell.get("word")
        if cell.get("word_index") == word_index and isinstance(word, str) and word:
            return word
    return "".join(
        str(cell.get("syllable") or "") for cell in cells if not cell.get("rest") and cell.get("word_index") == word_index
    )


def rebuild_line_text(cells: Sequence[Mapping[str, object]]) -> str:
    """Canonical text of a grid line: ``_`` per rest, each word once (by ``word`` when known, else
    its concatenated syllables) at its first syllable, anonymous cells by their syllable."""
    pieces: list[str] = []
    emitted: set[int] = set()
    for cell in cells:
        if cell.get("rest"):
            pieces.append(REST_MARK)
            continue
        word_index = cell.get("word_index")
        if not isinstance(word_index, int) or isinstance(word_index, bool):
            pieces.append(str(cell.get("syllable") or ""))
            continue
        if word_index in emitted:
            continue
        emitted.add(word_index)
        pieces.append(_word_spelling(cells, word_index))
    return " ".join(piece for piece in pieces if piece)
