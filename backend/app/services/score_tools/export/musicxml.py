"""MusicXML 4.0 partwise writer (docs/features/score_draft_export.md#musicxml). Built with the
standard library's ElementTree; no dependency.

One part, one percussion staff with one line, ``divisions`` 48 (a duration is the tick count).
The pickup is measure 0 ``implicit="yes"``; bar ``b`` is measure ``b + 1``. Notes are split at
barlines and into notated values with ties; triplet values carry ``time-modification`` 3:2 and a
run of consecutive triplet values inside a measure is bracketed by ``tuplet`` start / stop. Gaps are
rests; an empty measure is one ``<rest measure="yes"/>``. Each note's first piece carries the
accent (``accent`` / ``strong-accent``) and the lyric with its ``syllabic``.
"""

from __future__ import annotations

import xml.etree.ElementTree as ET
from collections.abc import Sequence

from app.services.score import Score
from app.services.score.types import GridJson, Note
from app.services.score_tools.export.lyrics import Lyric, note_lyrics
from app.services.score_tools.export.notation import Measure, Piece, measures

DOCTYPE = (
    '<!DOCTYPE score-partwise PUBLIC "-//Recordare//DTD MusicXML 4.0 Partwise//EN" '
    '"http://www.musicxml.org/dtds/partwise.dtd">'
)
DIVISIONS = 48
PART_ID = "P1"
PART_NAME = "Voice"
DISPLAY_STEP = "B"  # the staff's single line (middle line of a percussion staff)
DISPLAY_OCTAVE = "4"
SOFTWARE = "lyrics-canvas"


def _sub(parent: ET.Element, tag: str, text: str | None = None, **attrs: str) -> ET.Element:
    element = ET.SubElement(parent, tag, attrs)
    if text is not None:
        element.text = text
    return element


def _bpm_text(bpm: int | float) -> str:
    return str(int(bpm)) if float(bpm).is_integer() else f"{bpm:g}"


def _attributes(parent: ET.Element, measure: Measure, first: bool) -> None:
    if not first and measure.meter is None:
        return
    attributes = _sub(parent, "attributes")
    if first:
        _sub(attributes, "divisions", str(DIVISIONS))
    if measure.meter is not None:
        time = _sub(attributes, "time")
        _sub(time, "beats", str(measure.meter.num))
        _sub(time, "beat-type", str(measure.meter.den))
    if first:
        _sub(_sub(attributes, "clef"), "sign", "percussion")
        details = _sub(attributes, "staff-details")
        _sub(details, "staff-lines", "1")


def _tempo(parent: ET.Element, bpm: int | float) -> None:
    direction = _sub(parent, "direction", placement="above")
    metronome = _sub(_sub(direction, "direction-type"), "metronome")
    _sub(metronome, "beat-unit", "quarter")
    _sub(metronome, "per-minute", _bpm_text(bpm))
    _sub(direction, "sound", tempo=_bpm_text(bpm))


def _tuplet_marks(pieces: Sequence[Piece]) -> list[tuple[bool, bool]]:
    """(starts a bracket, stops a bracket) per piece: runs of consecutive triplet values."""
    marks: list[tuple[bool, bool]] = []
    for index, piece in enumerate(pieces):
        if not piece.value.triplet:
            marks.append((False, False))
            continue
        before = index > 0 and pieces[index - 1].value.triplet
        after = index + 1 < len(pieces) and pieces[index + 1].value.triplet
        marks.append((not before, not after))
    return marks


def _piece(parent: ET.Element, piece: Piece, note: Note | None, lyric: Lyric | None, tuplet: tuple[bool, bool]) -> None:
    element = _sub(parent, "note")
    if note is None:
        _sub(element, "rest")
    else:
        unpitched = _sub(element, "unpitched")
        _sub(unpitched, "display-step", DISPLAY_STEP)
        _sub(unpitched, "display-octave", DISPLAY_OCTAVE)
    _sub(element, "duration", str(piece.value.ticks))
    if piece.tie_stop:
        _sub(element, "tie", type="stop")
    if piece.tie_start:
        _sub(element, "tie", type="start")
    _sub(element, "voice", "1")
    _sub(element, "type", piece.value.name)
    for _ in range(piece.value.dots):
        _sub(element, "dot")
    if piece.value.triplet:
        modification = _sub(element, "time-modification")
        _sub(modification, "actual-notes", "3")
        _sub(modification, "normal-notes", "2")
    first_piece = note is not None and not piece.tie_stop
    accent = note.a if note is not None and first_piece else 0
    if piece.tie_stop or piece.tie_start or any(tuplet) or accent:
        notations = _sub(element, "notations")
        if piece.tie_stop:
            _sub(notations, "tied", type="stop")
        if piece.tie_start:
            _sub(notations, "tied", type="start")
        if tuplet[0]:
            _sub(notations, "tuplet", type="start", bracket="yes")
        if tuplet[1]:
            _sub(notations, "tuplet", type="stop")
        if accent:
            articulations = _sub(notations, "articulations")
            if accent == 1:
                _sub(articulations, "accent")
            else:
                _sub(articulations, "strong-accent", type="up")
    if first_piece and lyric is not None and lyric.text:
        lyric_element = _sub(element, "lyric", number="1")
        _sub(lyric_element, "syllabic", lyric.syllabic.value)
        _sub(lyric_element, "text", lyric.text)


def _whole_measure_rest(parent: ET.Element, length: int) -> None:
    element = _sub(parent, "note")
    _sub(element, "rest", measure="yes")
    _sub(element, "duration", str(length))
    _sub(element, "voice", "1")


def build(score: Score, grid: GridJson, title: str) -> ET.Element:
    notes = score.all_notes()
    lyrics = note_lyrics(score, grid)
    root = ET.Element("score-partwise", version="4.0")
    _sub(_sub(root, "work"), "work-title", title)
    _sub(_sub(_sub(root, "identification"), "encoding"), "software", SOFTWARE)
    score_part = _sub(_sub(root, "part-list"), "score-part", id=PART_ID)
    _sub(score_part, "part-name", PART_NAME)
    part = _sub(root, "part", id=PART_ID)
    for index, measure in enumerate(measures(score)):
        attrs = {"number": str(measure.number)}
        if measure.implicit:
            attrs["implicit"] = "yes"
        element = _sub(part, "measure", **attrs)
        _attributes(element, measure, index == 0)
        if measure.bpm is not None:
            _tempo(element, measure.bpm)
        if not measure.pieces:
            _whole_measure_rest(element, measure.length)
            continue
        for piece, tuplet in zip(measure.pieces, _tuplet_marks(measure.pieces)):
            note = None if piece.note is None else notes[piece.note]
            lyric = None if piece.note is None else lyrics[piece.note]
            _piece(element, piece, note, lyric, tuplet)
    return root


def write_musicxml(score: Score, grid: GridJson, title: str) -> bytes:
    """The score as MusicXML 4.0 partwise (UTF-8 bytes); ``score`` must be valid against ``grid``."""
    root = build(score, grid, title)
    ET.indent(root)
    body = ET.tostring(root, encoding="unicode")
    return ('<?xml version="1.0" encoding="UTF-8" standalone="no"?>\n' + DOCTYPE + "\n" + body + "\n").encode("utf-8")

