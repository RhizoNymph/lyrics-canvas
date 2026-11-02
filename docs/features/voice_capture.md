# Voice Capture

Record lyrics by voice on the web or Android client. The modal records from the microphone; a single
tap anywhere means "next line", a double tap stops (optionally silence stops it too). The audio is
uploaded, transcribed by a self-hosted speech-to-text service, broken into lines (at the taps, or by
an automatic split when there were none) and turned into a **new snippet** (canvas or list view) or
**appended** after the last line of the snippet open in the editor. The audio is kept, attached to
the snippet, and can be played back or deleted from the editor.

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

## Scope

- `POST /api/canvases/{canvas_id}/voice-takes`: multipart upload of one recording plus a `meta` JSON
  document (tap breaks, duration, target). Transcribes it through the STT service, assembles lines,
  and in **one transaction** creates a new freestyle snippet or appends to an existing one, and stores
  the recording.
- Line assembly: a tap in a pause splits at that pause, a tap inside a word splits before it (first
  half, a late tap) or after it (second half); without taps an automatic split by
  pause length, syllable balance and end rhyme (phonetic engine), or a single line.
- Append keeps the existing (possibly hand-arranged) grid lines verbatim, syllabifies only the new
  lines, reuses a blank trailing line, recomputes phonetics and size, bumps `content_updated_at`.
- Recordings: list per snippet (newest first, no audio), stream one recording's audio, delete one.
- Recordings follow their snippet: deleted with it (database cascade, also when the canvas is
  deleted), moved onto the merged snippet when snippets are merged.
- Feature switch: `STT_URL` empty (the default) answers 503 `stt_disabled`.

## Non-scope

- Live/streaming transcription or partial results while recording; one upload per take.
- Choosing the language or a prompt from the client (the STT service auto-detects; the backend sends
  neither `language` nor `initial_prompt`).
- Re-transcribing or re-splitting a stored recording; editing lines happens in the editor (Enter
  splits, Backspace joins, see [editor_line_split_join.md](./editor_line_split_join.md)).
- Copying recordings when a canvas is forked, exporting them, or showing them on shared (public)
  canvases.
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
  SttClient.transcribe(audio, mime) ---- POST {STT_URL}/v1/transcribe (multipart audio) ---->  STT service
        <---- 200 {language, duration_ms, words:[{text,start_ms,end_ms,probability}]} --------  (faster-whisper,
     STT_URL empty -> 503 stt_disabled; connect error / timeout / 503 -> 503 stt_unavailable;      word timestamps,
     413 -> 413 audio_too_large; anything else -> 502 stt_failed; no words -> 422 empty_transcript   VAD filter)
  assemble_lines(words, breaks_ms, EngineOracle())  -> lines + split_mode (taps | auto | single)
  recording_service.create_snippet_with_recording | append_with_recording   ONE transaction  500 persist_failed
        |
        v
  201 {snippet: SnippetResponse, recording: RecordingResponse, lines, split_mode, created}
client
  new: insert the snippet, select it (scroll to it in list view); append: replace the editor rows from
  the returned snippet; the editor's Recordings section lists GET /api/snippets/{id}/recordings and
  plays GET /api/recordings/{id}/audio (fetched with the bearer token into a blob/file)
  errors: switch on detail.error; the audio stays in memory for retry until success or discard
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
  "target": {"kind": "new", "position_x": 120.5, "position_y": -40.0}
         // | {"kind": "append", "snippet_id": "<uuid>"}
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
| 404 | `snippet_not_found` | append target missing, not the caller's, or in another canvas |
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
                     "split_mode", "created_at"}   // created_at: str(datetime), like SnippetResponse
```

### `GET /api/recordings/{recording_id}/audio`

200 with the raw bytes, `Content-Type` = the stored MIME type (parameters included),
`Cache-Control: private, max-age=0`. 404 `recording_not_found`.

### `DELETE /api/recordings/{recording_id}`

204; 404 `recording_not_found`; 500 `persist_failed`.

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

1. Lock the snippet row (`SELECT ... FOR UPDATE`, joined to the caller's canvas).
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

Index `ix_snippet_recordings_snippet_created (snippet_id, created_at)` serves the newest-first list.

There is deliberately no ORM relationship on `Snippet`: loading snippets never touches recordings.
Deletion is the database's job (snippet delete, and canvas delete via the ORM cascade to snippets).
**Merging** calls `snippet_service.replace_with_merged(..., reparent=recording_service.reassign_recordings)`:
after the merged snippet is flushed and before the originals are deleted, one `UPDATE` moves every
recording of the originals onto the merged snippet, inside the same transaction (a failure rolls the
whole merge back).

## Backend data / control flow

```
app/api/voice.py create_voice_take(canvas_id: UUID, audio: UploadFile, meta: str | None, user, db, transcriber)
  _voice_take
    parse_meta(meta)                         VoiceTakeMeta.model_validate_json -> InvalidMetaError
    accepted_mime(audio.content_type)        audio.py -> UnsupportedAudioTypeError
    read_bounded(audio, VOICE_MAX_UPLOAD_BYTES)   1 MiB chunks; AudioTooLargeError as soon as the sum exceeds
    recording_service.canvas_owned / snippet_in_canvas   -> CanvasNotFoundError / SnippetNotFoundError
    empty bytes                              -> EmptyTranscriptError
    transcriber.transcribe(bytes, mime)      stt_client.SttClient (Depends(get_transcriber)) -> Transcript
    assemble_lines(words, breaks_ms, EngineOracle())   line_assembly + engine_oracle
    NewRecording(mime, duration, bytes, "\n".join(lines), split_mode)
    match target:
      NewSnippetTarget -> create_snippet_with_recording(db, canvas, owner, position, lines, recording)
                            canvas owned again; derive_new_content("\n".join(lines))  (same as POST /snippets)
                            Snippet(freestyle, auto grid mode, position, grid, phonetics, size)
                            add -> flush -> add recording -> commit (rollback -> VoicePersistError)
      AppendTarget     -> append_with_recording(db, canvas, owner, snippet_id, lines, recording)
                            SELECT ... FOR UPDATE -> append_lines -> content_for -> content_updated_at = now()
                            add recording -> commit (rollback -> VoicePersistError)
    re-fetch snippet with tags (populate_existing) -> VoiceTakeResponse
  except VoiceTakeError -> HTTPException(exc.status, {"error": exc.code, "message": exc.message})
```

Shared snippet derivation: `app/services/snippet_content.py` (`derive_new_content`, `content_for`,
`compute_size`) is used by `POST /api/canvases/{id}/snippets`, the voice take, and (via
`app.api.snippets._compute_size`) the merge route.

### STT client

`app/services/voice/stt_client.py`: `SttClient(base_url, timeout_seconds, transport=None)` opens one
`httpx.AsyncClient` per call (a take is rare and heavy), posts `files={"audio": (take.<ext>, bytes,
mime)}` to `{STT_URL}/v1/transcribe` with the configured timeout for connect/read/write/pool, and
parses the body with pydantic. Words with blank text are dropped.

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
| `backend/app/api/voice.py` | Routes `create_voice_take`, `list_recordings`, `get_recording_audio`, `delete_recording`; `get_transcriber` dependency; `parse_meta`; error mapping |
| `backend/app/schemas/voice.py` | `VoiceTakeMeta`, `NewSnippetTarget`, `AppendTarget`, `VoiceTakeTarget` (discriminated union), `RecordingResponse`, `VoiceTakeResponse`, `VoiceErrorBody`, `VoiceErrorResponse`, `MAX_BREAKS` |
| `backend/app/services/voice/line_assembly.py` | Pure: `TimedWord`, `AssembledLines`, `WordOracle`, `AutoSplitParams`, `DEFAULT_PARAMS`, scoring constants, `tap_boundaries`, `lines_from_boundaries`, `pause_strengths`, `auto_boundaries`, `assemble_lines`, `LineAssemblyError` |
| `backend/app/services/voice/engine_oracle.py` | `EngineOracle` (syllables via `syllabify_word`, rhyme via `phonetics.score` k=2 with the dialect map), `RHYME_DEPTH` |
| `backend/app/services/voice/append.py` | Pure: `append_lines`, `AppendedContent`, `AppendError` |
| `backend/app/services/voice/audio.py` | `ACCEPTED_AUDIO_TYPES`, `accepted_mime`, `read_bounded` |
| `backend/app/services/voice/stt_client.py` | `SttClient`, `Transcriber`, `Transcript`, `audio_filename`, `TRANSCRIBE_PATH` |
| `backend/app/services/voice/errors.py` | `VoiceErrorCode`, `VoiceTakeError` (code + status) and subclasses per contract code; `SttError` base |
| `backend/app/services/recording_service.py` | `NewRecording`, `StoredAudio`, `canvas_owned`, `snippet_in_canvas`, `create_snippet_with_recording`, `append_with_recording`, `list_recordings`, `get_audio`, `delete_recording`, `reassign_recordings` |
| `backend/app/services/snippet_content.py` | `compute_size`, `SnippetContent`, `content_for`, `derive_new_content` (shared with POST /snippets) |
| `backend/app/services/snippet_service.py` | `replace_with_merged(..., reparent=None)`, `Reparent` |
| `backend/app/api/merge.py` | passes `reparent=recording_service.reassign_recordings` |
| `backend/app/models/recording.py` | `SnippetRecording`, `SplitMode` |
| `backend/alembic/versions/004_snippet_recordings.py` | table, enum type, index; downgrade drops all three |
| `backend/app/config.py` | `stt_url`, `stt_timeout_seconds`, `voice_max_upload_bytes` |
| `backend/tests/voice/` | `test_line_assembly.py`, `test_append.py`, `test_audio.py`, `test_stt_client.py`, `test_voice_routes.py`, `test_merge_recordings.py`, `test_recordings_db.py` (real Postgres, opt-in), `timeline.py` (fixtures, `FakeOracle`) |
| `docs/api/openapi.json` | regenerated snapshot with the four routes |

`test_recordings_db.py` runs only with `VOICE_TEST_DATABASE_URL` set to a throwaway database whose name
contains `test` (it migrates up, down and up, and writes rows); see its docstring.

### Clients and STT

Their specifics (modal gestures, silence auto-stop setting, permission handling, recordings section
UI, STT model/GPU deployment) are in the docs linked in the table at the top and summarised in the
end-to-end flow above.

## Invariants and constraints

- **Audio is never logged**, nor the transcript text: logs carry ids, byte counts, word/line counts,
  split mode, status codes and timings only. Validation messages list field paths, never values.
- **Owner checks everywhere**: every query joins `snippets -> canvases` and filters
  `canvases.owner_id`; foreign ids answer 404 (never 403), indistinguishable from missing ones.
- **One transaction per take**: the snippet insert/update and the recording insert commit together;
  any database error rolls back and answers 500 `persist_failed` with nothing changed.
- **Append never rewrites existing lines**: stored grid lines are copied verbatim; only new lines are
  syllabified; grid lines and non-blank text lines stay index-aligned for phonetics.
- Taps always win over the auto split; lines are never empty; a tap exactly halfway through a word
  splits before it; `split_mode = single` iff exactly one line came from the auto split.
- Recordings live and die with their snippet (FK cascade) and survive merges (moved before the
  originals are deleted).
- The STT service is called only after the request is validated and ownership is confirmed.
- Upload size is bounded by `VOICE_MAX_UPLOAD_BYTES` (and by nginx/ingress in front).
- `STT_URL` empty is a supported state (feature off), not an error at startup.
- The line assembly runs synchronously in the request (about 50 ms for a typical take, about a
  second for a 2000-word one); no thread pool is mixed into the async request path.
