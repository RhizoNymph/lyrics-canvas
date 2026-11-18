"""Standard MIDI File writer (docs/features/score_draft_export.md#midi). Hand-written, no dependency.

Format 1 with one track, division 48 ticks per quarter (the score's ppq, so ticks map 1:1). The
track holds the track name, a time signature at every meter change, a tempo at every tempo change,
and per note a lyric meta event, a note on and a note off: middle C (60) on channel 1, velocity by
accent 64 / 96 / 120. A pickup is written as the tail of a full first bar: every event moves right by
``bar_length(meters[0]) - pickup`` ticks, so MIDI bar 1 is the pickup bar and the score's bar 0 is
MIDI bar 2.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from enum import IntEnum

from app.services.score import PPQ, Score, bar_count, bar_start_tick
from app.services.score.timing import is_compound
from app.services.score.types import GridJson, Meter
from app.services.score_tools.export.lyrics import note_lyrics

FORMAT = 1
CHANNEL = 0  # MIDI channel 1
PITCH = 60  # middle C
VELOCITY = {0: 64, 1: 96, 2: 120}
NOTES_PER_QUARTER_32ND = 8
MAX_VLQ = 0x0FFFFFFF


class Priority(IntEnum):
    """Order of events at the same tick."""

    note_off = 0
    meta = 1
    lyric = 2
    note_on = 3


@dataclass(frozen=True, slots=True)
class Event:
    tick: int
    priority: Priority
    data: bytes


def vlq(value: int) -> bytes:
    """MIDI variable-length quantity (0 .. 0x0FFFFFFF)."""
    if not 0 <= value <= MAX_VLQ:
        raise ValueError(f"{value} does not fit a MIDI variable-length quantity")
    out = [value & 0x7F]
    value >>= 7
    while value:
        out.append(0x80 | (value & 0x7F))
        value >>= 7
    return bytes(reversed(out))


def meta(kind: int, payload: bytes) -> bytes:
    return bytes([0xFF, kind]) + vlq(len(payload)) + payload


def time_signature(meter: Meter) -> bytes:
    clocks = 96 // meter.den * (3 if is_compound(meter) else 1)  # MIDI clocks per metronome click
    return meta(0x58, bytes([meter.num, meter.den.bit_length() - 1, clocks, NOTES_PER_QUARTER_32ND]))


def tempo(bpm: int | float) -> bytes:
    return meta(0x51, round(60_000_000 / bpm).to_bytes(3, "big"))


def pickup_offset(score: Score) -> int:
    return score.meters[0].bar_length - score.pickup if score.pickup > 0 else 0


def lyric_text(text: str, continues: bool) -> bytes:
    return (text + ("-" if continues else "")).encode("utf-8")


def events(score: Score, grid: GridJson, title: str) -> list[Event]:
    offset = pickup_offset(score)
    out = [
        Event(0, Priority.meta, meta(0x03, title.encode("utf-8"))),
        Event(0, Priority.meta, time_signature(score.meters[0])),
        Event(0, Priority.meta, tempo(score.tempos[0].bpm)),
    ]
    out += [Event(bar_start_tick(score, m.bar) + offset, Priority.meta, time_signature(m)) for m in score.meters[1:]]
    out += [Event(bar_start_tick(score, t.bar) + offset, Priority.meta, tempo(t.bpm)) for t in score.tempos[1:]]
    for note, lyric in zip(score.all_notes(), note_lyrics(score, grid)):
        start = note.t + offset
        if lyric.text:
            out.append(Event(start, Priority.lyric, meta(0x05, lyric_text(lyric.text, lyric.continues))))
        out.append(Event(start, Priority.note_on, bytes([0x90 | CHANNEL, PITCH, VELOCITY[note.a]])))
        out.append(Event(start + note.d, Priority.note_off, bytes([0x80 | CHANNEL, PITCH, 0])))
    return sorted(out, key=lambda event: (event.tick, event.priority))


def track(ordered: Sequence[Event], end: int) -> bytes:
    body = bytearray()
    cursor = 0
    for event in ordered:
        body += vlq(event.tick - cursor) + event.data
        cursor = event.tick
    body += vlq(max(end, cursor) - cursor) + meta(0x2F, b"")
    return b"MTrk" + len(body).to_bytes(4, "big") + bytes(body)


def write_midi(score: Score, grid: GridJson, title: str) -> bytes:
    """The score as MIDI bytes; ``score`` must be valid against ``grid``."""
    header = b"MThd" + (6).to_bytes(4, "big") + FORMAT.to_bytes(2, "big") + (1).to_bytes(2, "big") + PPQ.to_bytes(2, "big")
    end = bar_start_tick(score, bar_count(score)) + pickup_offset(score)
    return header + track(events(score, grid, title), end)
