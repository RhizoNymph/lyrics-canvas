# Voice Dataset and Vocabulary Hints

Two backend features on top of [voice capture](./voice_capture.md), which stays the canonical document
for the endpoints and the data model:

- **A. Vocabulary hints.** Every voice take is transcribed with Whisper's `initial_prompt` filled with
  words from the user's [word bank](./word_bank.md), so rare words, names and slang come out right.
- **B. Transcript checking and dataset export.** Each recording keeps its raw STT words and the word
  ranges behind its lines. The user listens to a take and corrects the transcript to what was actually
  said (`verified_text`, stored on the recording, never on the snippet). Checked recordings export as a
  zip a fine-tuning script can read directly.

Clients: [web_voice_capture.md](./web_voice_capture.md) (hint toggle, check-transcript modal, stats and
download) and [android_voice_capture.md](./android_voice_capture.md) (hint toggle, check-transcript
screen, stats only).

## Scope

- Pure hint-prompt builder (ranking, character budget, reverse emission) and sending it to STT as the
  `initial_prompt` form field; opt-out per take (`meta.vocabulary_hints`) and per server
  (`VOICE_VOCAB_HINTS`); the exact prompt stored on the recording.
- Migration 007: `words`, `line_spans`, `language`, `initial_prompt`, `verified_text`, `verified_at`
  on `snippet_recordings`, all nullable.
- Line assembly exposing the word span of every line it produces (taps, auto and single modes).
- `RecordingResponse` gains `lines`, `has_timings`, `words`, `line_spans`, `language`,
  `initial_prompt`, `verified_text`, `verified_at` (list endpoint and voice-take response alike).
- `PUT` / `DELETE /api/recordings/{id}/verification`, `GET /api/recordings/stats`,
  `GET /api/recordings/export`.
- Chart values `backend.voice.vocabHints`, `vocabHintMaxChars`, `exportMaxBytes`.

## Non-scope

- Hints from anything but the word bank (snippet text, tags, other users); faster-whisper `hotwords`.
- Server-side cutting, segmenting, resampling or transcoding of audio; no ffmpeg. The export carries
  the original bytes and the timings to segment with.
- Running fine-tuning, hosting models, or feeding a tuned model back into the STT service.
- Re-transcribing old recordings to back-fill timings (old rows keep nulls).
- Export on Android (stats only there), export of shared canvases or other users' recordings.
- Changing the snippet from the check-transcript view: `verified_text` trains transcription, it is not
  the lyric.

## Vocabulary hints

```
POST /api/canvases/{id}/voice-takes  meta.vocabulary_hints (default true)
  app/api/voice.py _vocabulary_prompt(db, owner, wanted)
    wanted && settings.voice_vocab_hints ?  else -> None
    recording_service.bank_hint_words(db, owner)      SELECT text, created_at FROM bank_words WHERE user_id
                                                     (SQLAlchemyError -> rollback, warn, [])
    hints.build_hint_prompt(words, VOICE_VOCAB_HINT_MAX_CHARS) -> HintPrompt | None
    log voice.take.prompt words_offered=.. words_used=.. chars=..
  transcriber.transcribe(audio, mime, prompt)        SttClient: data={"initial_prompt": prompt} iff not None
  NewRecording(initial_prompt=prompt)                stored verbatim (null when nothing was sent)
```

Algorithm (`app/services/voice/hints.py`, pure, frequency source injectable):

1. Offer every bank word with a letter (`str.isalpha` on any character); phrases are whole entries.
2. Rank by `(zipf_frequency(text, "en"), -created_at, lower(text))`: rarer first (unknown = 0), then
   newer, then alphabetical.
3. Keep words in rank order while `len("Vocabulary: " + ", ".join(kept) + ".") <= max_chars` (code
   points). Stop at the first word that does not fit.
4. Emit kept words in reverse rank order: the most important word is last, because Whisper keeps the
   end of an over-long prompt (faster-whisper keeps the last ~223 tokens).
5. Format `Vocabulary: w1, w2, ..., wn.`; no fitting word or empty bank: no prompt.

Bank words cannot contain commas (word bank text rules), so the list is unambiguous.

## Timings and line spans

`assemble_lines` returns `AssembledLines(lines, split_mode, words, spans)`: `words` are the STT words
after trimming and dropping blanks (service order, `probability` kept), and `spans[i] = (start, end)`
with `" ".join(w.text for w in words[start:end]) == lines[i]`. `Timings.of(assembled)` carries them to
the recording row as jsonb (`timings.words_json`, `timings.spans_json`). Reading goes through
`timings.stored_timings`, which validates the JSON shape and the span invariants; a malformed row reads
as "no timings" with a warning (never written by this code).

`LineTiming` per line: `word_start`, `word_end`, `start_ms = words[start].start_ms`,
`end_ms = words[end - 1].end_ms`; both null for a blank span `[i, i)`.

## Verification

```
PUT /api/recordings/{id}/verification {"text"}
  verification.normalize_verification(text)   CRLF/CR -> LF; rstrip each line; drop leading/trailing
                                              blank lines; collapse blank runs to one; 1..10000 code
                                              points else InvalidVerificationError (422)
  recording_service.set_verification         owned row (snippet -> canvas -> owner) FOR UPDATE,
                                              verified_text = text, verified_at = now(), commit, refresh
DELETE /api/recordings/{id}/verification
  recording_service.clear_verification       owned row FOR UPDATE, both null, commit (idempotent)
```

Both answer the full `RecordingResponse`. Unknown or foreign ids: 404 `recording_not_found`.

## Stats

`recording_service.recording_stats`: one query over `snippet_recordings` whose snippet is in a canvas
the user owns: `count(*)`, `count(*) FILTER (verified_text IS NOT NULL)`, `sum(duration_ms)` (and
filtered), `count(*) FILTER (words IS NOT NULL AND line_spans IS NOT NULL)`.

## Export format

`GET /api/recordings/export?include_unverified=false` answers a zip named
`lyrics-canvas-voice-dataset-<yyyymmddThhmmssZ>.zip` (UTC export time):

```
audio/<recording_id>.<ext>     the original upload, byte for byte (ZIP_STORED)
metadata.jsonl                 one JSON object per recording, oldest first (created_at, id); UTF-8
dataset.json                   manifest
```

`<ext>` from the stored MIME type: `audio/webm` webm, `audio/ogg` ogg, `audio/mp4` m4a, `audio/aac`
aac, `audio/mpeg` mp3, `audio/wav` / `audio/x-wav` wav, anything else bin.

### `metadata.jsonl` fields

| Field | Type | Meaning |
|---|---|---|
| `id` | string (UUID) | the recording id |
| `file` | string | path of the audio inside the zip, `audio/<id>.<ext>` |
| `mime_type` | string | as uploaded, parameters kept (e.g. `audio/webm;codecs=opus`) |
| `duration_ms` | int | STT-decoded duration, else the client's |
| `language` | string \| null | the language STT detected |
| `split_mode` | `taps` \| `auto` \| `single` | how the take was split into lines |
| `created_at` | ISO 8601 string | when the take was recorded (`+00:00` offset) |
| `verified_at` | ISO 8601 string \| null | when the transcript was last checked |
| `text` | string \| null | the user-checked transcript (`\n` between lines); null for an unchecked recording (only with `include_unverified=true`) |
| `whisper_text` | string | the raw STT output as assembled lines (`transcript`) |
| `initial_prompt` | string \| null | the exact prompt Whisper was given |
| `words` | array \| null | `[{text, start_ms, end_ms, probability}]`, null for rows without timings |
| `lines` | array \| null | `[{whisper_text, word_start, word_end, start_ms, end_ms}]` per Whisper line; null without timings; `start_ms`/`end_ms` null for a blank line |

Example line (wrapped here; one line in the file):

```json
{"id": "6f1c2a4e-8d0b-4c8e-9a51-0e2f3b7d9c10", "file": "audio/6f1c2a4e-8d0b-4c8e-9a51-0e2f3b7d9c10.webm",
 "mime_type": "audio/webm;codecs=opus", "duration_ms": 2350, "language": "en", "split_mode": "taps",
 "created_at": "2026-11-06T10:15:02.481920+00:00", "verified_at": "2026-11-06T10:20:44.102311+00:00",
 "text": "the night is young\nso are we", "whisper_text": "the night is young\nso are wee",
 "initial_prompt": "Vocabulary: young, zephyr.",
 "words": [{"text": "the", "start_ms": 0, "end_ms": 180, "probability": 0.97},
           {"text": "night", "start_ms": 180, "end_ms": 520, "probability": 0.95},
           {"text": "is", "start_ms": 520, "end_ms": 640, "probability": 0.99},
           {"text": "young", "start_ms": 640, "end_ms": 1100, "probability": 0.93},
           {"text": "so", "start_ms": 1500, "end_ms": 1700, "probability": 0.98},
           {"text": "are", "start_ms": 1700, "end_ms": 1850, "probability": 0.96},
           {"text": "wee", "start_ms": 1850, "end_ms": 2200, "probability": null}],
 "lines": [{"whisper_text": "the night is young", "word_start": 0, "word_end": 4, "start_ms": 0, "end_ms": 1100},
           {"whisper_text": "so are wee", "word_start": 4, "word_end": 7, "start_ms": 1500, "end_ms": 2200}]}
```

### `dataset.json`

```json
{
  "format_version": 1,
  "exported_at": "2026-11-06T12:00:00.000000+00:00",
  "count": 12,
  "verified_count": 12,
  "total_duration_ms": 250000,
  "notes": "text is the user-checked transcript; whisper_text is the raw STT output. Clips longer than 30 s must be segmented (use lines[].start_ms/end_ms) before Whisper fine-tuning."
}
```

`count`, `verified_count` and `total_duration_ms` cover the exported records. An export with no
records is a valid zip with an empty `metadata.jsonl` and `count: 0`.

### Building and limits

`recording_service.export_recordings` selects the rows without audio and refuses (413
`export_too_large`) when `sum(byte_size)` exceeds `VOICE_EXPORT_MAX_BYTES` (default 1 GiB), before any
audio is read. `dataset_export.build_export` then writes into a `SpooledTemporaryFile` (32 MiB in
memory, then disk): for each record it awaits that recording's audio (owner-checked `get_audio`) and
writes it in `asyncio.to_thread`; `metadata.jsonl` and `dataset.json` are deflated. The finished file is
streamed in 1 MiB chunks with a `Content-Length` and closed when the stream ends or is aborted. A
recording deleted mid-export answers 404 `recording_not_found` and discards the temp file.

## Fine-tuning outline

An outline only; nothing here runs in the app.

1. Unzip; read `metadata.jsonl`; keep records with `text` (the export default already does).
2. Decode each `file` to 16 kHz mono (e.g. ffmpeg or torchaudio locally).
3. Segment clips over 30 s (Whisper's window): cut at `lines[].start_ms`/`end_ms` boundaries, grouping
   consecutive lines up to 30 s. Pair each segment with the matching lines of `text`. When `text` has
   the same number of lines as `lines`, map line to line; otherwise align `text` to `whisper_text`
   (e.g. difflib on words) or keep the clip whole when it is under 30 s. Records without `lines`
   (older rows) can only be used whole.
4. Optionally keep `initial_prompt` as the decoder prompt during training so the model learns to use
   the vocabulary hint the same way it is given at inference.
5. Fine-tune (e.g. Hugging Face `transformers` Seq2SeqTrainer on `openai/whisper-large-v3-turbo`, or
   LoRA), hold out a few checked takes, compare WER of `whisper_text` vs `text` before and after.
6. Convert to CTranslate2 (`ct2-transformers-converter`) to serve it from the STT service
   ([stt_service.md](./stt_service.md)).

## Files

| File | Role / key exports |
|---|---|
| `backend/alembic/versions/007_recording_dataset.py` | adds the six columns and `ck_snippet_recordings_verified_pair`; downgrade drops them |
| `backend/app/models/recording.py` | `SnippetRecording.words`, `line_spans` (`JSONB(none_as_null=True)`), `language`, `initial_prompt`, `verified_text`, `verified_at`; `LANGUAGE_MAX_LENGTH` |
| `backend/app/services/voice/hints.py` | `HintWord`, `HintPrompt` (`text`, `words_offered`, `words_used`, `chars`), `build_hint_prompt`, `rank_hint_words`, `eligible_words`, `zipf_en`, `PREFIX`, `SEPARATOR`, `SUFFIX` |
| `backend/app/services/voice/line_assembly.py` | `TimedWord.probability`, `WordSpan`, `AssembledLines.words` / `.spans`, `spans_from_boundaries` |
| `backend/app/services/voice/timings.py` | `Timings`, `LineTiming`, `TimingsError`, `span_problem`, `words_json`, `spans_json`, `parse_words`, `parse_spans`, `stored_timings` |
| `backend/app/services/voice/verification.py` | `normalize_verification`, `VerifiedText`, `MAX_VERIFIED_CHARS` |
| `backend/app/services/voice/dataset_export.py` | `ExportRecording`, `metadata_record`, `metadata_jsonl`, `manifest`, `export_filename`, `ZipBuilder`, `build_export`, `FORMAT_VERSION`, `NOTES`, `SPOOL_MAX_BYTES` |
| `backend/app/services/voice/stt_client.py` | `Transcriber.transcribe(audio, mime_type, initial_prompt=None)`, `audio_extension` |
| `backend/app/services/voice/errors.py` | `InvalidVerificationError` (422 `invalid_verification`), `ExportTooLargeError` (413 `export_too_large`) |
| `backend/app/services/recording_service.py` | `bank_hint_words`, `get_recording`, `set_verification`, `clear_verification`, `RecordingStats`, `recording_stats`, `export_recordings`, `timings_of`, `stored_language`; `NewRecording.timings/language/initial_prompt` |
| `backend/app/schemas/voice.py` | `VoiceTakeMeta.vocabulary_hints`, `TimedWordResponse`, `RecordingResponse` (new fields), `VerificationRequest`, `RecordingStatsResponse` |
| `backend/app/api/voice.py` | `_vocabulary_prompt`, `get_recording_stats`, `export_recordings`, `put_recording_verification`, `delete_recording_verification`, `_recording_response` |
| `backend/app/config.py` | `voice_vocab_hints`, `voice_vocab_hint_max_chars`, `voice_export_max_bytes` |
| `deploy/helm/lyrics-canvas/values.yaml`, `values.schema.json`, `templates/backend/deployment.yaml`, `README.md` | `backend.voice.*` -> `VOICE_VOCAB_HINTS`, `VOICE_VOCAB_HINT_MAX_CHARS`, `VOICE_EXPORT_MAX_BYTES` |
| `backend/tests/voice/test_hints.py`, `test_line_spans.py`, `test_timings.py`, `test_verification.py`, `test_dataset_export.py`, `test_stt_client_prompt.py`, `test_dataset_routes.py` | unit and route tests |
| `backend/tests/voice/test_recording_dataset_db.py`, `test_migration_007_db.py` | real Postgres (opt-in via `VOICE_TEST_DATABASE_URL` / `TAG_TEST_DATABASE_URL`) |

## Invariants

- `line_spans` tile `words`: contiguous, non-overlapping, in order, covering every word; one span per
  `transcript` line. `Timings` cannot be constructed otherwise.
- `verified_text` and `verified_at` are both null or both set (DB check constraint); saving always sets
  `verified_at = now()`; checking never touches the snippet or `transcript`.
- `initial_prompt` is exactly what STT received, or null when the form field was not sent; it is at
  most `VOICE_VOCAB_HINT_MAX_CHARS` code points and its last word is the most important bank word.
- Old rows keep null in all six columns; every reader treats null as "no timings / not checked".
- Every route checks ownership through snippet -> canvas -> owner; foreign ids are 404.
- No bank word, prompt, transcript or checked text is logged; logs carry counts only.
- An export holds at most `VOICE_EXPORT_MAX_BYTES` of audio, at most one recording's audio in memory
  at a time, and never runs zip writes on the event loop.
- `metadata.jsonl` lines are in `created_at, id` order and every `file` exists in the zip.
