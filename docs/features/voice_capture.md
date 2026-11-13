# Voice Capture

Record lyrics by voice on the web or Android client. The modal records from the microphone; a single
tap anywhere means "next line", a double tap stops (optionally silence stops it too). The audio is
uploaded, transcribed by a self-hosted speech-to-text service, broken into lines (at the taps, or by
an automatic split when there were none) and turned into a **new snippet** (canvas or list view) or
**appended** after the last line of the snippet open in the editor. The audio is kept, attached to
the snippet, and can be played back or deleted from the editor.

Each take is transcribed with the user's **word bank as vocabulary hints** (Whisper's
`initial_prompt`). The recording keeps its raw STT words and the word ranges behind its lines, so the
user can **check the transcript** against the audio (stored as `verified_text` on the recording, never
on the snippet) and **export** the checked recordings as a zip dataset for fine-tuning STT.

This is the canonical document for the whole feature. The backend part is described in full here;
the other parts have their own feature docs:

| Part | Doc | Where |
|---|---|---|
| STT service (faster-whisper, own image + chart component) | [stt_service.md](./stt_service.md) | `stt/` |
| Backend: endpoints, line assembly, storage | this document | `backend/app/api/voice.py`, `backend/app/services/voice/`, `backend/app/services/recording_service.py` |
| Web client: record button, modal, recordings list | [web_voice_capture.md](./web_voice_capture.md) | `frontend/src/components/voice/`, `frontend/src/lib/voice/` |
| Android client: same behaviour | [android_voice_capture.md](./android_voice_capture.md) | `android/app/.../app/voice/`, `android/core/logic/.../logic/voice/` |
| Editor Enter-split / Backspace-join for fixing lines | [editor_line_split_join.md](./editor_line_split_join.md) | web + Android editors |
| Ingress TLS (mic needs a secure context) and upload limits | [homelab_tls.md](./homelab_tls.md) | `deploy/homelab/tls/`, `frontend/nginx/` |
| Recording dataset: export format, fine-tuning outline, hint and verification rules | [voice_dataset.md](./voice_dataset.md) | `backend/app/services/voice/` (`hints`, `timings`, `verification`, `dataset_export`) |

## Scope

- `POST /api/canvases/{canvas_id}/voice-takes`: multipart upload of one recording plus a `meta` JSON
  document (tap breaks, duration, target). Transcribes it through the STT service, assembles lines,
  and in **one transaction** creates a new freestyle snippet or appends to an existing one, and stores
  the recording.
- Line assembly: a tap in a pause splits at that pause, a tap inside a word splits before it (first
  half, a late tap) or after it (second half); without taps an automatic split by
  pause length, syllable balance and end rhyme (phonetic engine), or a single line.
- Append keeps the existing (possibly hand-arranged) grid lines verbatim, syllabifies only the new
  lines, reuses a blank trailing line, recomputes phonetics and size, bumps `content_updated_at`
  and the snippet's content `version` ([sync_versions.md](./sync_versions.md)).
  The target must be placed on the take's canvas; the content is shared by every canvas holding the
  snippet, the new size goes to the take canvas's placement only
  ([snippet_placements.md](./snippet_placements.md#voice-takes)). A new take is placed on the take's
  canvas.
- Recordings: list per snippet (newest first, no audio), stream one recording's audio, delete one.
- Recordings follow their snippet, whatever canvas the take was recorded on: deleted with it (database
  cascade, also when a canvas delete leaves it with no placement), kept when it is only removed from
  one of several canvases or moved, moved onto the merged snippet when a merge deletes it (an original
  also placed on another canvas keeps them).
- Feature switch: `STT_URL` empty (the default) answers 503 `stt_disabled`.
- Vocabulary hints: the user's word bank, ranked (rarest, newest first) and cut to a character
  budget, sent as Whisper's `initial_prompt`; per take opt-out `meta.vocabulary_hints=false`, server
  switch `VOICE_VOCAB_HINTS`. The exact prompt is stored on the recording.
- Timings: each recording stores the take's STT `words`, the `line_spans` behind its lines and the
  detected `language` (migration 007).
- Transcript checking: `PUT`/`DELETE /api/recordings/{id}/verification` store or clear the user's
  corrected transcript (`verified_text`, `verified_at`).
- Dataset: `GET /api/recordings/stats` and `GET /api/recordings/export` (zip of original audio plus
  JSONL metadata), across all of the user's canvases. Format in [voice_dataset.md](./voice_dataset.md).

## Non-scope

- Live/streaming transcription or partial results while recording; one upload per take.
- Choosing the language or writing a prompt from the client (the STT service auto-detects the
  language; the only prompt is the word-bank hint list, which the client can only switch off).
  faster-whisper `hotwords` are not used.
- Re-transcribing or re-splitting a stored recording; editing lines happens in the editor (Enter
  splits, Backspace joins, see [editor_line_split_join.md](./editor_line_split_join.md)).
- Copying recordings when a canvas is forked, or showing them on shared (public) canvases.
- Cutting, segmenting or transcoding audio for the dataset export (no ffmpeg; the zip carries the
  original bytes and the line timings to segment with).
- Audio transcoding, trimming or compression on the server; the bytes are stored as uploaded.
- Quotas beyond the per-upload size limit.
- Exposing the STT service outside the cluster (it is ClusterIP only, never routed by ingress/nginx).

## End-to-end flow

```
client (web toolbar / editor, Android top bar / editor)
  record button -> modal: MediaRecorder (web: audio/webm;codecs=opus, Safari audio/mp4;
                   Android: audio/mp4 AAC or audio/ogg Opus), live level, "Line N"
    single tap   -> break t (ms since recorder start), committed after 300 ms without a second tap
    double tap   -> stop (the pending break is discarded); Stop button / silence auto-stop also stop
  target: editor open on S -> flush pending editor edits, then {kind: append, snippet_id: S}
          else             -> {kind: new, position = viewport centre in canvas coordinates}
  POST /api/canvases/{canvas_id}/voice-takes  (Bearer; multipart audio + meta)
        |
        v
backend  app/api/voice.py  create_voice_take
  parse meta (pydantic, discriminated on target.kind)          422 invalid_meta
  MIME allow-list                                              415 unsupported_audio_type
  read audio in 1 MiB chunks up to VOICE_MAX_UPLOAD_BYTES      413 audio_too_large
  canvas owned? / append: snippet in this canvas and owned?    404 canvas_not_found / snippet_not_found
  empty audio                                                  422 empty_transcript
  meta.vocabulary_hints && VOICE_VOCAB_HINTS: bank words -> build_hint_prompt -> prompt | None
  SttClient.transcribe(audio, mime, prompt) -- POST {STT_URL}/v1/transcribe (audio [+ initial_prompt]) -> STT
        <---- 200 {language, duration_ms, words:[{text,start_ms,end_ms,probability}]} --------  (faster-whisper,
     STT_URL empty -> 503 stt_disabled; connect error / timeout / 503 -> 503 stt_unavailable;      word timestamps,
     413 -> 413 audio_too_large; anything else -> 502 stt_failed; no words -> 422 empty_transcript   VAD filter)
  assemble_lines(words, breaks_ms, EngineOracle())  -> lines + split_mode (taps | auto | single)
                                                       + cleaned words + per-line word spans
  recording_service.create_snippet_with_recording | append_with_recording   ONE transaction  500 persist_failed
    (the recording row also stores words, line_spans, language, initial_prompt)
        |
        v
  201 {snippet: SnippetResponse, recording: RecordingResponse, lines, split_mode, created}
client
  new: insert the snippet, select it (scroll to it in list view); append: replace the editor rows from
  the returned snippet; the editor's Recordings section lists GET /api/snippets/{id}/recordings and
  plays GET /api/recordings/{id}/audio (fetched with the bearer token into a blob/file)
  errors: switch on detail.error; the audio stays in memory for retry until success or discard
  check transcript: play the audio, edit, PUT /api/recordings/{id}/verification {text}
  dataset: GET /api/recordings/stats, GET /api/recordings/export (web only) -> zip
```

## Backend API

All routes need a bearer token. Errors are `HTTPException` with
`detail = {"error": "<code>", "message": "<human text>"}`; the body is
`{"detail": {"error": ..., "message": ...}}` (`VoiceErrorResponse` in the OpenAPI document). Path ids
are UUIDs (a malformed one is FastAPI's plain 422).

### `POST /api/canvases/{canvas_id}/voice-takes` (multipart)

| Part | Content |
|---|---|
| `audio` | the file. Accepted `Content-Type` base types: `audio/webm`, `audio/ogg`, `audio/mp4`, `audio/aac`, `audio/mpeg`, `audio/wav`, `audio/x-wav`; parameters such as `;codecs=opus` are allowed and stored |
| `meta` | JSON string, see below |

```jsonc
{
  "breaks_ms": [1830, 4210],       // ms since recording start of each confirmed single tap; may be empty or omitted
  "duration_ms": 6400,             // the client's measured length (fallback when STT reports none)
  "target": {"kind": "new", "position_x": 120.5, "position_y": -40.0},
         // | {"kind": "append", "snippet_id": "<uuid>"}
  "vocabulary_hints": true         // optional, default true: send the word bank as initial_prompt
}
```

`breaks_ms`: non-negative integers, at most 2000, any order (sorted and de-duplicated by the
assembly). Positions must be finite numbers. Unknown extra keys are ignored.

201:

```jsonc
{
  "snippet": SnippetResponse,      // the new snippet, or the appended one
  "recording": RecordingResponse,
  "lines": ["first line", "second line"],   // the lines this take added
  "split_mode": "taps" | "auto" | "single",
  "created": true                  // false for append
}
```

| Status | `error` | When |
|---|---|---|
| 404 | `canvas_not_found` | the canvas does not exist or is not the caller's |
| 404 | `snippet_not_found` | append target missing, not the caller's, or not placed on this canvas |
| 413 | `audio_too_large` | more than `VOICE_MAX_UPLOAD_BYTES`, or the STT service answered 413 |
| 415 | `unsupported_audio_type` | missing or non-allow-listed `Content-Type` on the `audio` part |
| 422 | `invalid_meta` | `meta` missing, not JSON, or failing validation (message lists field paths, never values) |
| 422 | `empty_transcript` | zero-byte audio, or no words recognised |
| 500 | `persist_failed` | the database transaction failed; rolled back, nothing changed |
| 502 | `stt_failed` | the STT service answered an error other than 503/413, an unreadable body, or the connection broke mid-request |
| 503 | `stt_disabled` | `STT_URL` is empty |
| 503 | `stt_unavailable` | connect error, timeout (`STT_TIMEOUT_SECONDS`), or STT 503 (`model_not_ready`) |

Checks run in this order: meta, MIME, size, canvas/snippet ownership, empty audio, STT, assembly,
persistence. Ownership is checked before the (expensive) transcription.

### `GET /api/snippets/{snippet_id}/recordings`

200 `[RecordingResponse]`, newest first (`created_at desc, id desc`); 404 `snippet_not_found` when the
snippet is missing or not the caller's.

```jsonc
RecordingResponse = {"id", "snippet_id", "mime_type", "duration_ms", "byte_size",
                     "transcript",   // the assembled lines joined by "\n"
                     "split_mode", "created_at",   // created_at: str(datetime), like SnippetResponse
                     "lines",          // transcript.split("\n")
                     "has_timings",    // words and line_spans are stored
                     "words",          // [{text, start_ms, end_ms, probability|null}] | null
                     "line_spans",     // [[start, end), ...] word indices per line | null
                     "language",       // STT language | null
                     "initial_prompt", // the exact prompt sent to STT | null
                     "verified_text",  // the user's checked transcript ("\n" between lines) | null
                     "verified_at"}    // ISO 8601 datetime | null; set iff verified_text is
```

Rows from before migration 007 have `null` in every new field, `has_timings: false`, and `lines`
derived from `transcript`. Example (one take, two lines by taps, checked):

```json
{
  "id": "6f1c2a4e-8d0b-4c8e-9a51-0e2f3b7d9c10",
  "snippet_id": "0b7e5d3a-1f2c-4e6b-8a9d-3c4b5a6f7e81",
  "mime_type": "audio/webm;codecs=opus",
  "duration_ms": 2350,
  "byte_size": 38211,
  "transcript": "the night is young\nso are wee",
  "split_mode": "taps",
  "created_at": "2026-11-06 10:15:02.481920+00:00",
  "lines": ["the night is young", "so are wee"],
  "has_timings": true,
  "words": [
    {"text": "the", "start_ms": 0, "end_ms": 180, "probability": 0.97},
    {"text": "night", "start_ms": 180, "end_ms": 520, "probability": 0.95},
    {"text": "is", "start_ms": 520, "end_ms": 640, "probability": 0.99},
    {"text": "young", "start_ms": 640, "end_ms": 1100, "probability": 0.93},
    {"text": "so", "start_ms": 1500, "end_ms": 1700, "probability": 0.98},
    {"text": "are", "start_ms": 1700, "end_ms": 1850, "probability": 0.96},
    {"text": "wee", "start_ms": 1850, "end_ms": 2200, "probability": null}
  ],
  "line_spans": [[0, 4], [4, 7]],
  "language": "en",
  "initial_prompt": "Vocabulary: young, zephyr.",
  "verified_text": "the night is young\nso are we",
  "verified_at": "2026-11-06T10:20:44.102311Z"
}
```

### `GET /api/recordings/{recording_id}/audio`

200 with the raw bytes, `Content-Type` = the stored MIME type (parameters included),
`Cache-Control: private, max-age=0`. 404 `recording_not_found`.

### `DELETE /api/recordings/{recording_id}`

204; 404 `recording_not_found`; 500 `persist_failed`.

### `PUT /api/recordings/{recording_id}/verification`

Body `{"text": string}`; 200 `RecordingResponse`. The owner is checked through `snippets.owner_id`
like the audio and delete routes (404 `recording_not_found` for missing or foreign ids). The text
is normalized (`app/services/voice/verification.py`, pure): CR LF and CR become LF; trailing whitespace
is stripped from every line; blank lines at the start and end are dropped; runs of blank lines collapse
to one. The result must be non-empty and at most 10 000 code points, else 422 `invalid_verification`.
Every save sets `verified_at = now()` (the transaction clock). The snippet and the recording's
`transcript` are never changed. A body without `text` is FastAPI's plain 422.

### `DELETE /api/recordings/{recording_id}/verification`

200 `RecordingResponse` with `verified_text` and `verified_at` both `null`; idempotent; 404
`recording_not_found`.

### `GET /api/recordings/stats`

200 `{"total", "verified", "total_duration_ms", "verified_duration_ms", "with_timings"}` over the
current user's recordings in all canvases (`with_timings` counts rows with both `words` and
`line_spans`). One aggregate query.

### `GET /api/recordings/export?include_unverified=false`

200 `application/zip`, `Content-Disposition: attachment;
filename="lyrics-canvas-voice-dataset-<UTC yyyymmddThhmmssZ>.zip"`, `Content-Length`,
`Cache-Control: no-store`. Without `include_unverified` only checked recordings are exported; with it,
all of them (`text` is `null` for unchecked ones). Oldest first. 413 `export_too_large` when the
selected audio exceeds `VOICE_EXPORT_MAX_BYTES` (checked from `byte_size` before any audio is read);
404 `recording_not_found` if a recording disappears mid-export. Zero recordings still give a valid zip.

```
audio/<recording_id>.<ext>   original bytes; ext from the MIME map (webm, ogg, m4a, aac, mp3, wav, else bin)
metadata.jsonl               one object per recording: id, file, mime_type, duration_ms, language,
                             split_mode, created_at, verified_at, text, whisper_text, initial_prompt,
                             words, lines [{whisper_text, word_start, word_end, start_ms, end_ms}] | null
dataset.json                 {format_version: 1, exported_at, count, verified_count, total_duration_ms, notes}
```

The full field reference and an example line are in
[voice_dataset.md](./voice_dataset.md#export-format). The zip is built in a `SpooledTemporaryFile`
(memory up to 32 MiB, then disk): the audio is read one recording at a time and every zip write runs in
`asyncio.to_thread`; the finished file is streamed in 1 MiB chunks and closed (deleted) afterwards.

`/api/recordings/stats` and `/api/recordings/export` are declared before the
`/api/recordings/{recording_id}...` routes, and no `GET /api/recordings/{recording_id}` exists, so the
fixed paths are never parsed as ids.

## Vocabulary hints

`app/services/voice/hints.py` (pure) builds the STT `initial_prompt` from the user's bank words
(`recording_service.bank_hint_words`: text and `created_at`, no tags):

1. Every bank word with at least one letter is offered (phrases too).
2. Ranked by importance: rarer first (wordfreq `zipf_frequency(text, "en")` ascending, phrases
   included, unknown = 0), then newer (`created_at` desc), then `lower(text)` ascending.
3. The most important words are kept while `"Vocabulary: " + ", ".join(kept) + "."` fits in
   `VOICE_VOCAB_HINT_MAX_CHARS` code points (default 600). The first word that does not fit ends the
   list; shorter, less important words are not used to fill the gap.
4. The kept words are emitted in **reverse** importance order, so the most important word is last:
   Whisper and faster-whisper keep the end of an over-long prompt.
5. No word fits, or the bank is empty: no prompt, and the form field is not sent.

A failed bank read degrades to no prompt (logged at warn). The log line `voice.take.prompt` carries
`words_offered`, `words_used` and `chars`, never the words. Example: bank `night` (Zipf 5.4), `zephyr`
(2.1), `glorbix` (unknown) gives `Vocabulary: night, zephyr, glorbix.`

## Line assembly

`backend/app/services/voice/line_assembly.py`, pure. Input: the transcript's words
(`TimedWord(text, start_ms, end_ms)`, blank ones dropped, text trimmed) and `breaks_ms`. Output:
`AssembledLines(lines, split_mode)`, at least one line, never a blank line, words joined by single
spaces in order (punctuation kept as the STT service emits it).

### Taps

- Boundary `i` lies between word `i` and word `i + 1`.
- A break in the pause after word `i` (`words[i].end_ms <= t < words[i + 1].start_ms`) resolves to
  boundary `i`, however long the pause.
- A break inside word `i` resolves to boundary `i - 1` when it lands in the word's first half
  (inclusive: the writer tapped late, as the next line began) and to boundary `i` otherwise. When
  timestamps overlap, the word that started last owns the instant.
- Whisper often stretches a word over the pause before it, so gap midpoints are a poor guide: with
  "Americans," ending at 2140 and "ask" spanning 3340-3780, a tap at 3500 belongs before "ask" even
  though it is nearer the "ask"/"not" gap.
- Breaks that would split before the first word or after the last word (before or in the first half
  of the first word; in the second half of the last word or after it) are ignored.
- Breaks resolving to the same boundary count once; unsorted breaks are fine.
- If at least one break resolves, `split_mode = "taps"` and only the taps decide the lines.
- If none resolves (no breaks, or all ignored), the auto split runs.

### Auto-split scoring

A dynamic program over the boundaries picks the segmentation (two or more lines) maximising

```
  sum over chosen boundaries i   (PAUSE_WEIGHT * pause_strength(i) - SPLIT_COST)
+ sum over consecutive lines     rhyme_bonus(last word of line k, last word of line k+1)
- sum over lines                 line_cost(syllables, T)
```

once for every target line length `T` in `MIN_LINE_SYLLABLES..MAX_LINE_SYLLABLES`, and keeps the best
over all `T`. It is compared with keeping one line, scored `-OVERLONG_PENALTY * max(0, total -
MAX_LINE_SYLLABLES)`; the split must beat it strictly. Otherwise `split_mode = "single"`.

- `pause_strength(i) = min(1, max(0, gap_i - median_low(gaps)) / PAUSE_SATURATION_MS)`: only the part
  of a gap beyond the speaker's normal inter-word gap counts, so fast and slow speakers behave alike.
- `line_cost(s, T) = BALANCE_WEIGHT * |s - T| / T + SHORT_LINE_PENALTY * max(0, MIN_LINE_SYLLABLES - s)
  + OVERLONG_PENALTY * max(0, s - MAX_LINE_SYLLABLES)`. A common `T` per run is what makes lines
  balanced against each other without extra DP state.
- `rhyme_bonus(a, b) = RHYME_WEIGHT * score` when the engine's near-rhyme score (`phonetics.score`,
  depth 2, default dialect map applied) is `>= RHYME_MIN_SCORE`, else 0; the same word repeated gets
  `IDENTICAL_RHYME_FACTOR` of it. Only consecutive lines are compared (AABB, not ABAB).
- Syllables per word come from the beat-grid syllabifier (`syllabify_word`), so the balance matches
  what the grid will show.
- Takes with fewer than `SHORT_TAKE_SYLLABLES` syllables or `MIN_WORDS_TO_SPLIT` words are never split.
- Lines over `HARD_MAX_LINE_SYLLABLES` are not considered (a single word is always allowed), which
  bounds the DP to `O(targets x words x words-per-line)`: a typical take (~150 words) assembles in
  about 50 ms, a 2000-word take in about a second.

| Constant | Value | Meaning |
|---|---|---|
| `PAUSE_WEIGHT` | 3.0 | reward for a full-strength pause |
| `PAUSE_SATURATION_MS` | 600 | excess gap (over the median) that counts as a full pause |
| `SPLIT_COST` | 1.0 | cost of any split; a split where the speaker did not pause scores -1 |
| `BALANCE_WEIGHT` | 1.5 | lines 4 and 8 syllables long cost 0.75 (best `T` = 8) |
| `RHYME_WEIGHT` | 1.0 | a perfect end rhyme is worth about one full split |
| `RHYME_MIN_SCORE` | 0.75 | below this the engine score earns nothing |
| `IDENTICAL_RHYME_FACTOR` | 0.5 | repeated word = half a rhyme |
| `MIN_LINE_SYLLABLES` | 4 | shorter lines cost `SHORT_LINE_PENALTY` (0.5) per missing syllable |
| `MAX_LINE_SYLLABLES` | 14 | longer lines (and the one-line option) cost `OVERLONG_PENALTY` (0.25) per extra syllable |
| `HARD_MAX_LINE_SYLLABLES` | 32 | lines longer than this are not considered |
| `SHORT_TAKE_SYLLABLES` | 10 | shorter takes stay one line |
| `MIN_WORDS_TO_SPLIT` | 4 | takes with fewer words stay one line |

All are collected in `AutoSplitParams` (validated: weights non-negative, `1 <= MIN <= MAX <= HARD_MAX`).

Worked examples (12 one-syllable words, 100 ms normal gaps):

- One 700 ms pause after word 6: split there `+2`, lines 6/6 cost 0 -> two lines. Extra splits cost -1 each.
- One 700 ms pause after word 4: 4 | 8 scores `2 - 0.75 = 1.25`; 4 | 4 | 4 would score `2 - 1 = 1.0` ->
  a clear pause beats perfect balance.
- Two 400 ms pauses after words 5 and 6, and word 5 rhymes with the last word: 6 | 6 scores `0.5`;
  5 | 7 scores `0.5 + 1.0 - 0.43 = 1.07` -> the rhyme moves the split.
- Uniform delivery, no rhyme: every split scores -1, the one line scores 0 -> `single`.
- 60 syllables of uniform delivery: one line costs `0.25 * 46 = 11.5`; five lines of 12 cost 4 -> split.
- "I saw a black cat | sleeping on the mat" with a 500 ms pause after "cat": `1.1 + 1.0` (cat/mat) -> two lines.

## Append semantics

`backend/app/services/voice/append.py` (`append_lines`, pure) and
`recording_service.append_with_recording`:

1. Lock the snippet row (`SELECT ... FOR UPDATE`, through its placement on the caller's canvas).
2. Strip trailing blank lines from `raw_text` (whitespace-only lines at the end); middle blank lines
   and trailing spaces on the last real line stay.
3. Copy the stored grid lines verbatim (rests, hand-set stress, client keys such as `arranged`), minus
   trailing blank grid lines (blank `raw_text` and no syllable cells). A snippet without a grid gets
   its existing text syllabified (nothing to preserve).
4. Syllabify only the new lines (`syllabify_text`) and append them.
5. `raw_text = base + "\n" + new lines` (just the new lines when the snippet was blank).
6. Recompute phonetics with the stored overrides (`compute_snippet_phonetics`, grid stress wins), and
   the size (`compute_size`); set `content_updated_at = now()` (transaction clock).
7. Insert the recording and commit once.

`PUT /api/snippets/{id}` with only `raw_text` re-syllabifies every line and would destroy hand
arrangements, which is why the append does not use it. Fixed grid mode is not padded (same as PUT).

## Data model

Table `snippet_recordings` (`backend/app/models/recording.py`, migration `004_snippet_recordings`):

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | `uuid4`, serialized as a string |
| `snippet_id` | UUID NOT NULL | FK -> `snippets.id` **ON DELETE CASCADE** |
| `mime_type` | varchar(127) | as uploaded (type/subtype lower-cased, parameters kept) |
| `duration_ms` | int | STT `duration_ms` when > 0, else the client's `meta.duration_ms` |
| `byte_size` | int | `len(audio)` |
| `audio` | bytea | deferred: only the audio endpoint loads it |
| `transcript` | text | the assembled lines joined by `\n` |
| `split_mode` | enum `recording_split_mode` (`taps`, `auto`, `single`) | |
| `created_at` | timestamptz | server default `now()`, returned on insert (`eager_defaults`) |

| `words` | jsonb, null | migration 007: STT words in service order, `[{"text", "start_ms", "end_ms", "probability": float\|null}]` (trimmed, blanks dropped) |
| `line_spans` | jsonb, null | per assembled line `[start, end)` indices into `words`, e.g. `[[0,5],[5,9]]`; contiguous, covering every word; a blank line would be `[i, i)` |
| `language` | varchar(8), null | the language STT detected; a longer value is dropped (null) |
| `initial_prompt` | text, null | exactly what was sent as `initial_prompt`; null when nothing was sent |
| `verified_text` | text, null | the user's checked transcript, lines joined by `\n` |
| `verified_at` | timestamptz, null | when `verified_text` was last saved |

Index `ix_snippet_recordings_snippet_created (snippet_id, created_at)` serves the newest-first list.
Check constraint `ck_snippet_recordings_verified_pair`: `verified_text` and `verified_at` are both null
or both set. Rows from before migration 007 keep null in all six new columns; its downgrade drops them.
`words`/`line_spans` are read and written only through `app/services/voice/timings.py`, whose `Timings`
type cannot hold spans that do not tile the words. An append that reuses a blank trailing snippet line
still records the take's own lines; a merge moves recordings unchanged.

There is deliberately no ORM relationship on `Snippet`: loading snippets never touches recordings.
Deletion is the database's job (snippet delete, and canvas delete, which deletes the snippets it leaves
with no placement). **Merging** calls
`snippet_service.replace_with_merged(..., reparent=recording_service.reassign_recordings)`: after the
merged snippet is flushed and before the originals are deleted, one `UPDATE` moves every recording of
the originals the merge deletes (those only on the merge canvas) onto the merged snippet, inside the
same transaction (a failure rolls the whole merge back). An original also placed on other canvases is
not deleted and keeps its recordings.

## Backend data / control flow

```
app/api/voice.py create_voice_take(canvas_id: UUID, audio: UploadFile, meta: str | None, user, db, transcriber)
  _voice_take
    parse_meta(meta)                         VoiceTakeMeta.model_validate_json -> InvalidMetaError
    accepted_mime(audio.content_type)        audio.py -> UnsupportedAudioTypeError
    read_bounded(audio, VOICE_MAX_UPLOAD_BYTES)   1 MiB chunks; AudioTooLargeError as soon as the sum exceeds
    recording_service.canvas_owned / snippet_in_canvas   -> CanvasNotFoundError / SnippetNotFoundError
    empty bytes                              -> EmptyTranscriptError
    _vocabulary_prompt(db, owner, meta.vocabulary_hints)
      off (meta or VOICE_VOCAB_HINTS) -> None
      recording_service.bank_hint_words -> hints.build_hint_prompt(words, VOICE_VOCAB_HINT_MAX_CHARS)
      log voice.take.prompt words_offered words_used chars
    transcriber.transcribe(bytes, mime, prompt)   stt_client.SttClient (Depends(get_transcriber)) -> Transcript
    assemble_lines(words, breaks_ms, EngineOracle())   line_assembly + engine_oracle
                                             -> AssembledLines(lines, split_mode, words, spans)
    NewRecording(mime, duration, bytes, "\n".join(lines), split_mode,
                 timings=Timings.of(assembled), language, initial_prompt=prompt)
    match target:
      NewSnippetTarget -> create_snippet_with_recording(db, canvas, owner, position, lines, recording)
                            canvas owned again; derive_new_content("\n".join(lines))  (same as POST /snippets)
                            Snippet(owner, freestyle, auto grid mode, grid, phonetics)
                            + SnippetPlacement(canvas, position, size)
                            add -> flush -> add recording -> commit (rollback -> VoicePersistError)
      AppendTarget     -> append_with_recording(db, canvas, owner, snippet_id, lines, recording)
                            placement on canvas, snippet SELECT ... FOR UPDATE -> append_lines -> content_for
                            -> content_updated_at = now(); placement width/height = new size
                            add recording -> commit (rollback -> VoicePersistError)
    re-fetch the snippet on this canvas (PlacedSnippet: tags, canvases) -> VoiceTakeResponse
  except VoiceTakeError -> HTTPException(exc.status, {"error": exc.code, "message": exc.message})

put_recording_verification(recording_id, {text})
  normalize_verification(text) -> VerifiedText          InvalidVerificationError 422
  recording_service.set_verification                    owned row FOR UPDATE; verified_at = now(); commit; refresh
delete_recording_verification(recording_id)
  recording_service.clear_verification                  owned row FOR UPDATE; both null; commit
get_recording_stats -> recording_service.recording_stats    one aggregate over the owner's recordings
export_recordings(include_unverified)
  recording_service.export_recordings                   rows without audio, oldest first; sum(byte_size)
                                                        > VOICE_EXPORT_MAX_BYTES -> ExportTooLargeError 413
  dataset_export.build_export(records, load_audio=get_audio per id, now)
    to_thread(ZipBuilder) -> per record: await get_audio -> to_thread(add_audio, ZIP_STORED)
    to_thread(add_text metadata.jsonl, dataset.json; DEFLATED) -> to_thread(finish) -> rewound file
  StreamingResponse(_file_chunks(file))                 1 MiB chunks; file closed in finally
```

Shared snippet derivation: `app/services/snippet_content.py` (`derive_new_content`, `content_for`,
`compute_size`) is used by `POST /api/canvases/{id}/snippets`, the voice take, and (via
`app.api.snippets._compute_size`) the merge route.

### STT client

`app/services/voice/stt_client.py`: `SttClient(base_url, timeout_seconds, transport=None)` opens one
`httpx.AsyncClient` per call (a take is rare and heavy), posts `files={"audio": (take.<ext>, bytes,
mime)}` to `{STT_URL}/v1/transcribe` with the configured timeout for connect/read/write/pool, and
parses the body with pydantic. Words with blank text are dropped; each word keeps its
`probability` (metadata, not part of `TimedWord` equality). `transcribe(audio, mime, initial_prompt)`
adds the `initial_prompt` form field only when the prompt is not `None`.

| Outcome | Error | Code / status |
|---|---|---|
| `STT_URL` empty or whitespace | `SttDisabledError` (no request made) | `stt_disabled` 503 |
| `httpx.TimeoutException`, `httpx.ConnectError` | `SttUnavailableError` | `stt_unavailable` 503 |
| other `httpx.TransportError`, `httpx.InvalidURL` | `SttFailedError` | `stt_failed` 502 |
| HTTP 503 | `SttUnavailableError` | `stt_unavailable` 503 |
| HTTP 413 | `AudioTooLargeError` | `audio_too_large` 413 |
| any other non-200 (incl. 400 `undecodable_audio`, 500) | `SttFailedError` (message carries the service's code) | `stt_failed` 502 |
| 200 with an unparseable body | `SttFailedError` | `stt_failed` 502 |
| 200 with no non-blank words | `EmptyTranscriptError` | `empty_transcript` 422 |

`Transcriber` is a `Protocol`; tests override `get_transcriber` with fakes or give `SttClient` an
`httpx.MockTransport`.

## Configuration

`backend/app/config.py` (pydantic-settings, environment or `.env`):

| Setting | Env | Default | Meaning |
|---|---|---|---|
| `stt_url` | `STT_URL` | `""` | STT base URL, e.g. `http://<release>-stt:8000`; empty disables voice takes |
| `stt_timeout_seconds` | `STT_TIMEOUT_SECONDS` | `120` | per-request timeout (connect, read, write, pool) |
| `voice_max_upload_bytes` | `VOICE_MAX_UPLOAD_BYTES` | `26214400` (25 MiB) | largest accepted recording |
| `voice_vocab_hints` | `VOICE_VOCAB_HINTS` | `true` | send the word bank as `initial_prompt` (per take opt-out in `meta`) |
| `voice_vocab_hint_max_chars` | `VOICE_VOCAB_HINT_MAX_CHARS` | `600` | prompt budget in code points, prefix and period included |
| `voice_export_max_bytes` | `VOICE_EXPORT_MAX_BYTES` | `1073741824` (1 GiB) | most audio one export may hold |

The chart sets the last three from `backend.voice.vocabHints`, `backend.voice.vocabHintMaxChars` and
`backend.voice.exportMaxBytes` (schema-validated). The export zip is built before the response starts,
so a very large export needs an ingress read timeout to match (homelab: 180 s).

The upload passes through nginx (`client_max_body_size 26m` for `/api/`) and the ingress
(`proxy-body-size: 26m`) ([homelab_tls.md](./homelab_tls.md)); the chart wires `STT_URL` to the STT
component ([stt_service.md](./stt_service.md)). `docker-compose.yaml` has no STT service, so local Compose runs with
voice takes disabled; to try them locally, run the STT service yourself and set `STT_URL` for the
backend (e.g. in `backend/.env`). Starlette spools multipart uploads (to disk past 1 MiB) before the
handler runs, so the byte limit is enforced while reading the spooled part; nginx/ingress bound the
raw request.

## Files

### Backend (this branch)

| File | Role / key exports |
|---|---|
| `backend/app/api/voice.py` | Routes `create_voice_take`, `list_recordings`, `get_recording_stats`, `export_recordings`, `put_recording_verification`, `delete_recording_verification`, `get_recording_audio`, `delete_recording`; `get_transcriber` dependency; `parse_meta`; `_vocabulary_prompt`; error mapping |
| `backend/app/schemas/voice.py` | `VoiceTakeMeta` (`vocabulary_hints`), `NewSnippetTarget`, `AppendTarget`, `VoiceTakeTarget` (discriminated union), `TimedWordResponse`, `RecordingResponse`, `VerificationRequest`, `RecordingStatsResponse`, `VoiceTakeResponse`, `VoiceErrorBody`, `VoiceErrorResponse`, `MAX_BREAKS` |
| `backend/app/services/voice/line_assembly.py` | Pure: `TimedWord` (with `probability`), `WordSpan`, `AssembledLines` (lines, split_mode, words, spans), `WordOracle`, `AutoSplitParams`, `DEFAULT_PARAMS`, scoring constants, `tap_boundaries`, `lines_from_boundaries`, `spans_from_boundaries`, `pause_strengths`, `auto_boundaries`, `assemble_lines`, `LineAssemblyError` |
| `backend/app/services/voice/hints.py` | Pure: `HintWord`, `HintPrompt`, `build_hint_prompt`, `rank_hint_words`, `eligible_words`, `zipf_en`, `PREFIX` |
| `backend/app/services/voice/timings.py` | Pure: `Timings` (span invariants), `LineTiming`, `TimingsError`, `span_problem`, `words_json`, `spans_json`, `parse_words`, `parse_spans`, `stored_timings` |
| `backend/app/services/voice/verification.py` | Pure: `normalize_verification`, `VerifiedText`, `MAX_VERIFIED_CHARS` |
| `backend/app/services/voice/dataset_export.py` | `ExportRecording`, `metadata_record`, `metadata_jsonl`, `manifest`, `export_filename`, `ZipBuilder`, `build_export`, `NOTES`, `FORMAT_VERSION` |
| `backend/app/services/voice/engine_oracle.py` | `EngineOracle` (syllables via `syllabify_word`, rhyme via `phonetics.score` k=2 with the dialect map), `RHYME_DEPTH` |
| `backend/app/services/voice/append.py` | Pure: `append_lines`, `AppendedContent`, `AppendError` |
| `backend/app/services/voice/audio.py` | `ACCEPTED_AUDIO_TYPES`, `accepted_mime`, `read_bounded` |
| `backend/app/services/voice/stt_client.py` | `SttClient`, `Transcriber`, `Transcript`, `audio_extension`, `audio_filename`, `TRANSCRIBE_PATH` |
| `backend/app/services/voice/errors.py` | `VoiceErrorCode`, `VoiceTakeError` (code + status) and subclasses per contract code (incl. `InvalidVerificationError`, `ExportTooLargeError`); `SttError` base |
| `backend/app/services/recording_service.py` | `NewRecording` (+ `timings`, `language`, `initial_prompt`), `StoredAudio`, `RecordingStats`, `stored_language`, `timings_of`, `canvas_owned`, `snippet_in_canvas`, `bank_hint_words`, `create_snippet_with_recording`, `append_with_recording`, `list_recordings`, `get_recording`, `get_audio`, `delete_recording`, `set_verification`, `clear_verification`, `recording_stats`, `export_recordings`, `reassign_recordings` |
| `backend/app/services/snippet_content.py` | `compute_size`, `SnippetContent`, `content_for`, `derive_new_content` (shared with POST /snippets) |
| `backend/app/services/snippet_service.py` | `replace_with_merged(..., reparent=None)`, `Reparent` |
| `backend/app/api/merge.py` | passes `reparent=recording_service.reassign_recordings` |
| `backend/app/models/recording.py` | `SnippetRecording` (+ dataset columns, check constraint), `SplitMode`, `LANGUAGE_MAX_LENGTH` |
| `backend/alembic/versions/004_snippet_recordings.py` | table, enum type, index; downgrade drops all three |
| `backend/alembic/versions/007_recording_dataset.py` | six nullable columns + `ck_snippet_recordings_verified_pair`; downgrade drops them |
| `backend/app/config.py` | `stt_url`, `stt_timeout_seconds`, `voice_max_upload_bytes`, `voice_vocab_hints`, `voice_vocab_hint_max_chars`, `voice_export_max_bytes` |
| `backend/tests/voice/` | `test_line_assembly.py`, `test_line_spans.py` (seeded property tests), `test_append.py`, `test_audio.py`, `test_stt_client.py`, `test_stt_client_prompt.py`, `test_hints.py`, `test_timings.py`, `test_verification.py`, `test_dataset_export.py`, `test_voice_routes.py`, `test_dataset_routes.py`, `test_merge_recordings.py`, `test_recordings_db.py` / `test_recording_dataset_db.py` (real Postgres, `VOICE_TEST_DATABASE_URL`), `test_migration_007_db.py` (`TAG_TEST_DATABASE_URL`), `timeline.py` (fixtures, `FakeOracle`) |
| `deploy/helm/lyrics-canvas/` | `backend.voice.*` values, schema, `VOICE_*` env on the backend Deployment; `deploy/helm/tests/test_chart.py` `VoiceDatasetValuesTest` |
| `docs/api/openapi.json` | regenerated snapshot with the eight routes |

`test_recordings_db.py` runs only with `VOICE_TEST_DATABASE_URL` set to a throwaway database whose name
contains `test` (it migrates up, down and up, and writes rows); see its docstring.

### Clients and STT

Their specifics (modal gestures, silence auto-stop setting, permission handling, recordings section
UI, STT model/GPU deployment) are in the docs linked in the table at the top and summarised in the
end-to-end flow above.

## Invariants and constraints

- **Audio is never logged**, nor the transcript, checked-transcript, bank-word or prompt text: logs
  carry ids, byte/char counts, word/line counts, split mode, status codes and timings only. Validation
  messages list field paths, never values.
- **Owner checks everywhere**: every query joins `snippets -> canvases` and filters
  `canvases.owner_id`; foreign ids answer 404 (never 403), indistinguishable from missing ones.
- **One transaction per take**: the snippet insert/update and the recording insert commit together;
  any database error rolls back and answers 500 `persist_failed` with nothing changed.
- **Append never rewrites existing lines**: stored grid lines are copied verbatim; only new lines are
  syllabified; grid lines and non-blank text lines stay index-aligned for phonetics.
- Taps always win over the auto split; lines are never empty; a tap exactly halfway through a word
  splits before it; `split_mode = single` iff exactly one line came from the auto split.
- Recordings live and die with their snippet (FK cascade), not with a canvas or a placement, and
  survive merges (moved before an original is deleted; kept by an original that stays elsewhere).
- The STT service is called only after the request is validated and ownership is confirmed.
- Upload size is bounded by `VOICE_MAX_UPLOAD_BYTES` (and by nginx/ingress in front).
- `STT_URL` empty is a supported state (feature off), not an error at startup.
- `line_spans`, when stored, tile `words` in order and there is one span per `transcript` line;
  `" ".join(words[start:end].text)` is that line's text at take time.
- `verified_text` and `verified_at` are both null or both set (DB check constraint); verification
  never changes the snippet or `transcript`.
- `initial_prompt` is exactly what STT received; null means the field was not sent.
- The export never holds more than `VOICE_EXPORT_MAX_BYTES` of audio and never more than one
  recording's audio in memory at once; zip writes never run on the event loop.
- The line assembly runs synchronously in the request (about 50 ms for a typical take, about a
  second for a 2000-word one); no thread pool is mixed into the async request path.
