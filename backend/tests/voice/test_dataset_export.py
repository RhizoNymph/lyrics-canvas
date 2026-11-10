"""The dataset zip: metadata records, manifest, file name and the archive layout."""

from __future__ import annotations

import io
import json
import uuid
import zipfile
from datetime import datetime, timezone

import pytest

from app.models.recording import SplitMode
from app.services.voice.dataset_export import (
    NOTES,
    ExportRecording,
    build_export,
    export_filename,
    manifest,
    metadata_jsonl,
    metadata_record,
)
from app.services.voice.line_assembly import TimedWord
from app.services.voice.timings import Timings

CREATED = datetime(2026, 11, 1, 12, 0, tzinfo=timezone.utc)
VERIFIED = datetime(2026, 11, 2, 8, 30, 5, tzinfo=timezone.utc)
WORDS = (TimedWord("a", 0, 100, 0.9), TimedWord("b", 150, 300, None), TimedWord("c", 400, 500, 0.5))


def rec(**overrides) -> ExportRecording:
    fields = dict(
        id=uuid.UUID("11111111-2222-3333-4444-555555555555"),
        mime_type="audio/webm;codecs=opus",
        duration_ms=1500,
        byte_size=5,
        language="en",
        split_mode=SplitMode.taps,
        created_at=CREATED,
        verified_at=VERIFIED,
        verified_text="a b\nsee",
        transcript="a b\nc",
        initial_prompt="Vocabulary: zephyr.",
        timings=Timings(WORDS, ((0, 2), (2, 3))),
    )
    fields.update(overrides)
    return ExportRecording(**fields)


def test_metadata_record_fields():
    assert metadata_record(rec()) == {
        "id": "11111111-2222-3333-4444-555555555555",
        "file": "audio/11111111-2222-3333-4444-555555555555.webm",
        "mime_type": "audio/webm;codecs=opus",
        "duration_ms": 1500,
        "language": "en",
        "split_mode": "taps",
        "created_at": "2026-11-01T12:00:00+00:00",
        "verified_at": "2026-11-02T08:30:05+00:00",
        "text": "a b\nsee",
        "whisper_text": "a b\nc",
        "initial_prompt": "Vocabulary: zephyr.",
        "words": [
            {"text": "a", "start_ms": 0, "end_ms": 100, "probability": 0.9},
            {"text": "b", "start_ms": 150, "end_ms": 300, "probability": None},
            {"text": "c", "start_ms": 400, "end_ms": 500, "probability": 0.5},
        ],
        "lines": [
            {"whisper_text": "a b", "word_start": 0, "word_end": 2, "start_ms": 0, "end_ms": 300},
            {"whisper_text": "c", "word_start": 2, "word_end": 3, "start_ms": 400, "end_ms": 500},
        ],
    }


def test_unverified_old_row_has_nulls():
    record = metadata_record(rec(verified_at=None, verified_text=None, timings=None, language=None, initial_prompt=None))
    assert record["text"] is None and record["verified_at"] is None
    assert record["words"] is None and record["lines"] is None
    assert record["language"] is None and record["initial_prompt"] is None


def test_blank_line_has_null_times():
    timings = Timings(WORDS, ((0, 3), (3, 3)))
    record = metadata_record(rec(transcript="a b c\n", timings=timings))
    assert record["lines"][1] == {"whisper_text": "", "word_start": 3, "word_end": 3, "start_ms": None, "end_ms": None}


def test_line_count_mismatch_drops_lines_but_keeps_words():
    record = metadata_record(rec(transcript="a b c"))  # one line, two spans
    assert record["lines"] is None
    assert record["words"] is not None


@pytest.mark.parametrize("mime, ext", [("audio/ogg;codecs=opus", "ogg"), ("audio/mp4", "m4a"), ("audio/x-wav", "wav")])
def test_file_extension_follows_the_mime_type(mime, ext):
    assert rec(mime_type=mime).file.endswith(f".{ext}")


def test_manifest():
    exported = datetime(2026, 11, 6, 10, 0, tzinfo=timezone.utc)
    body = manifest([rec(), rec(verified_text=None, verified_at=None, duration_ms=500)], exported)
    assert body == {
        "format_version": 1,
        "exported_at": "2026-11-06T10:00:00+00:00",
        "count": 2,
        "verified_count": 1,
        "total_duration_ms": 2000,
        "notes": NOTES,
    }
    assert "30 s" in NOTES


def test_jsonl_is_one_object_per_line():
    text = metadata_jsonl([rec(), rec(id=uuid.uuid4())])
    lines = text.split("\n")
    assert lines[-1] == ""
    assert [json.loads(line)["whisper_text"] for line in lines[:-1]] == ["a b\nc", "a b\nc"]
    assert metadata_jsonl([]) == ""


def test_export_filename_is_utc():
    from datetime import timedelta

    local = datetime(2026, 11, 6, 12, 5, 9, tzinfo=timezone(timedelta(hours=2)))
    assert export_filename(local) == "lyrics-canvas-voice-dataset-20261106T100509Z.zip"


async def test_build_export_layout():
    first = rec(id=uuid.uuid4(), mime_type="audio/ogg")
    second = rec(id=uuid.uuid4(), mime_type="audio/mp4", verified_text=None, verified_at=None)
    audio = {first.id: b"OggS-one", second.id: b"\x00\x00ftyp-two"}
    loaded: list[uuid.UUID] = []

    async def load(recording_id):
        loaded.append(recording_id)
        return audio[recording_id]

    out = await build_export([first, second], load, CREATED)
    try:
        archive = zipfile.ZipFile(io.BytesIO(out.read()))
    finally:
        out.close()
    assert loaded == [first.id, second.id]
    assert archive.namelist() == [f"audio/{first.id}.ogg", f"audio/{second.id}.m4a", "metadata.jsonl", "dataset.json"]
    assert archive.read(f"audio/{first.id}.ogg") == b"OggS-one"
    assert archive.getinfo(f"audio/{first.id}.ogg").compress_type == zipfile.ZIP_STORED
    rows = [json.loads(line) for line in archive.read("metadata.jsonl").decode().splitlines()]
    assert [r["id"] for r in rows] == [str(first.id), str(second.id)]
    assert all(r["file"] in archive.namelist() for r in rows)
    manifest_body = json.loads(archive.read("dataset.json"))
    assert (manifest_body["count"], manifest_body["verified_count"]) == (2, 1)


async def test_empty_export_is_a_valid_zip():
    async def load(recording_id):
        raise AssertionError("no audio to load")

    out = await build_export([], load, CREATED)
    archive = zipfile.ZipFile(io.BytesIO(out.read()))
    out.close()
    assert archive.namelist() == ["metadata.jsonl", "dataset.json"]
    assert archive.read("metadata.jsonl") == b""
    assert json.loads(archive.read("dataset.json"))["count"] == 0


async def test_failing_audio_load_propagates():
    class Boom(Exception):
        pass

    async def load(recording_id):
        raise Boom()

    with pytest.raises(Boom):
        await build_export([rec()], load, CREATED)
