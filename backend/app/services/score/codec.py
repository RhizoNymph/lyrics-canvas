"""Score <-> JSON. Reading goes through ``validate.check_score``; this writes the canonical JSON
(spec/score_format.md section 2, key order ``v, ppq, pickup, meters, tempos, snap, lines``)."""

from __future__ import annotations

from typing import Any

from app.services.score.types import Note, Score


def note_to_json(note: Note) -> dict[str, Any]:
    body: dict[str, Any] = {"s": note.s, "t": note.t, "d": note.d, "a": note.a}
    for key, value in note.extra.items():
        body[key] = value
    return body


def score_to_json(score: Score) -> dict[str, Any]:
    return {
        "v": score.v,
        "ppq": score.ppq,
        "pickup": score.pickup,
        "meters": [{"bar": m.bar, "num": m.num, "den": m.den} for m in score.meters],
        "tempos": [{"bar": t.bar, "bpm": t.bpm} for t in score.tempos],
        "snap": score.snap.value,
        "lines": [{"notes": [note_to_json(n) for n in line.notes]} for line in score.lines],
    }
