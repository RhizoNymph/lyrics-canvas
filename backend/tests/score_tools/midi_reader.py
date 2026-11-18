"""Test-only Standard MIDI File reader: enough to check what export.midi writes, and strict about
the container (chunk lengths, VLQs, running status, end of track)."""

from __future__ import annotations

from dataclasses import dataclass, field


class MidiParseError(Exception):
    pass


@dataclass(frozen=True)
class MidiEvent:
    tick: int  # absolute
    kind: str  # "meta", "note_on", "note_off", "other"
    meta_type: int | None = None
    data: bytes = b""
    channel: int | None = None
    pitch: int | None = None
    velocity: int | None = None


@dataclass
class MidiFile:
    format: int
    division: int
    tracks: list[list[MidiEvent]] = field(default_factory=list)

    def metas(self, meta_type: int) -> list[MidiEvent]:
        return [e for t in self.tracks for e in t if e.kind == "meta" and e.meta_type == meta_type]


class _Reader:
    def __init__(self, data: bytes) -> None:
        self.data = data
        self.pos = 0

    def take(self, n: int) -> bytes:
        if self.pos + n > len(self.data):
            raise MidiParseError(f"truncated at {self.pos} (+{n})")
        chunk = self.data[self.pos : self.pos + n]
        self.pos += n
        return chunk

    def u8(self) -> int:
        return self.take(1)[0]

    def vlq(self) -> int:
        value = 0
        for _ in range(4):
            byte = self.u8()
            value = (value << 7) | (byte & 0x7F)
            if not byte & 0x80:
                return value
        raise MidiParseError("variable-length quantity longer than 4 bytes")


def _track(body: bytes) -> list[MidiEvent]:
    r = _Reader(body)
    events: list[MidiEvent] = []
    tick = 0
    status: int | None = None
    ended = False
    while r.pos < len(body):
        if ended:
            raise MidiParseError("events after end of track")
        tick += r.vlq()
        first = r.u8()
        if first == 0xFF:
            meta_type = r.u8()
            data = r.take(r.vlq())
            events.append(MidiEvent(tick, "meta", meta_type=meta_type, data=data))
            ended = meta_type == 0x2F
            continue
        if first in (0xF0, 0xF7):
            r.take(r.vlq())
            events.append(MidiEvent(tick, "other"))
            continue
        if first & 0x80:
            status = first
            data1 = r.u8()
        else:
            if status is None:
                raise MidiParseError("running status without a status byte")
            data1 = first
        kind = status & 0xF0
        if kind in (0xC0, 0xD0):
            events.append(MidiEvent(tick, "other"))
            continue
        data2 = r.u8()
        channel = status & 0x0F
        if kind == 0x90 and data2 > 0:
            events.append(MidiEvent(tick, "note_on", channel=channel, pitch=data1, velocity=data2))
        elif kind in (0x80, 0x90):
            events.append(MidiEvent(tick, "note_off", channel=channel, pitch=data1, velocity=data2))
        else:
            events.append(MidiEvent(tick, "other"))
    if not ended:
        raise MidiParseError("track has no end-of-track event")
    return events


def parse_midi(data: bytes) -> MidiFile:
    r = _Reader(data)
    if r.take(4) != b"MThd":
        raise MidiParseError("missing MThd")
    if int.from_bytes(r.take(4), "big") != 6:
        raise MidiParseError("header length is not 6")
    fmt = int.from_bytes(r.take(2), "big")
    ntrks = int.from_bytes(r.take(2), "big")
    division = int.from_bytes(r.take(2), "big")
    if division & 0x8000:
        raise MidiParseError("SMPTE division not supported")
    midi = MidiFile(fmt, division)
    for _ in range(ntrks):
        if r.take(4) != b"MTrk":
            raise MidiParseError("missing MTrk")
        length = int.from_bytes(r.take(4), "big")
        midi.tracks.append(_track(r.take(length)))
    if r.pos != len(data):
        raise MidiParseError("trailing bytes after the last track")
    return midi
