# Web Voice Capture

Record lyrics by voice in the web client. A Record button (toolbar, and the open inline editor) or
Alt+Shift+R opens a full-screen recording modal. A single tap/click anywhere (or Space / Enter) marks
"next line"; a double tap stops. Silence auto-stop is optional. After stop the audio is uploaded to
`POST /api/canvases/{id}/voice-takes`, transcribed server-side, and becomes a new snippet, or is
appended to the snippet open in the editor. The audio is kept and listed in the editor, where it can be
played, deleted and checked: a Check transcript modal lets the user listen and save what was actually
said as the recording's `verified_text`. The canvas list page has a "Voice dataset" dialog with stats and
a zip download of those checked takes. A switch in the recorder turns the word-bank transcription hints
on or off.

The voice dataset contract (hints, verification, stats, export) is in
[voice_dataset.md](./voice_dataset.md) and [voice_capture.md](./voice_capture.md); this document covers
its web client too.

The cross-cutting contract (STT service, backend endpoints, line assembly, Android behaviour) is in
[voice_capture.md](./voice_capture.md). This document covers the web client only; its tests mock
the HTTP layer.

## Scope

- Record button in the canvas page toolbar (canvas and list view) and in the inline editor header,
  disabled with the tooltip "Recording needs HTTPS" when `!window.isSecureContext` or there is no
  `navigator.mediaDevices` ("This browser cannot record audio" without `MediaRecorder`).
- Keyboard shortcut **Alt+Shift+R** (Option+Shift+R on macOS) on the canvas page, also while typing in
  the editor. It is matched on `KeyboardEvent.code` (Option changes `key` on macOS). It was chosen
  because it does not collide with browser shortcuts (Ctrl/Cmd+R and Ctrl/Cmd+Shift+R reload, and
  Ctrl+Alt+R is Firefox reader view on Linux) or with the app's own Ctrl/Cmd+Shift+L/V/H, Ctrl+Z, Ctrl+F
  and Delete.
- Recording modal: full-surface tap target, elapsed time, live level meter, "Line N", Stop, Cancel, and the
  silence auto-stop setting. It shows a spinner while finishing or uploading, and error states mapped
  from the contract error codes, with Retry (re-sends the same blob), Save audio (download the take) and
  Discard.
- Gestures: tap time is taken at pointerdown (or keydown). A second tap within 300 ms is a double tap: stop,
  and the pending break is dropped. Otherwise the break at the first tap's time is committed after
  300 ms. The Stop and Cancel buttons and the silence select are never taps. Breaks are ms since the
  `MediaRecorder` fired `start`.
- Silence auto-stop: energy VAD over the analyser RMS with an adaptive (minimum-statistics) noise floor.
  It arms only after speech was heard and stops after N s of continuous silence. N is per viewer in
  `localStorage` (`lyrics-canvas.voice.silenceAutoStopSeconds`): off / 2 / 3 / 5 / 8 / 10 s, default
  off, editable in the modal while recording.
- Container: `audio/webm;codecs=opus` preferred, then `audio/mp4` (Safari), then `audio/webm`,
  `audio/ogg;codecs=opus`, else the browser default.
- Targeting: if the editor is open on snippet S, the take is appended to S. The editor's pending edits are
  saved first, then the take is uploaded, then the editor rows are replaced from the returned snippet.
  Otherwise a new snippet goes at the centre of the current canvas viewport in canvas coordinates; in
  list view that is the last measured canvas viewport, or (0, 0) if the canvas was never shown in this
  session. The new snippet is selected, and in list view scrolled to. The canvas store takes the returned
  snippet directly, with no canvas reload.
- Recordings section in the inline editor: collapsed "Recordings (n)" line, rows with date, duration,
  transcript preview, a **Checked** / **Not checked** badge, a **Check** button (labelled "Check
  transcript"), play/pause (audio fetched with the bearer token into a blob URL) and delete with an
  inline confirmation. It is hidden when the snippet has no recordings or the list request fails.
- "Use word bank as hints" switch in the recorder (below the silence select, `role="switch"`), per viewer
  in `localStorage` (`lyrics-canvas.voice.vocabHints`, `"true"`/`"false"`), default on. Its value is read
  when the upload starts (so a change during the take or before Retry applies) and sent as
  `meta.vocabulary_hints`.
- Check transcript modal, opened from a recording row:
  - Player for that recording (the existing audio endpoint, bearer token, blob URL): play/pause, a
    scrubber (range input in ms) and `m:ss / m:ss`. The position is sampled every animation frame
    while playing.
  - Read-only Whisper reference, one row per line. With timings (`has_timings` and spans that tile
    the words) every word is a button: a tap seeks to its `start_ms` and plays, and the word being
    played is highlighted (`aria-current`). Words below 50 % probability are amber. Without timings
    the lines are plain text.
  - Correction textarea, prefilled with `verified_text` if set, else `lines.join("\n")` (else the
    transcript), with the hint "Write exactly what was said: this trains transcription, not your lyrics.
    Editing here does not change the snippet."
  - **Save as checked** validates locally with the server's normalization (empty or over 10 000 code
    points is refused inline without a request), PUTs the normalized text and closes. **Mark unchecked**
    (only when checked) DELETEs and stays open. **Cancel** closes, or asks "Discard your changes?"
    inline when the field differs from the prefill. Request errors are shown inline from the typed
    `VoiceError`.
  - Keys: Ctrl/Cmd+Enter saves; **Ctrl+Space** toggles playback; Alt+Left / Alt+Right seek 3 s
    (clamped); Escape cancels (or dismisses the discard question); Tab is trapped. Ctrl+Space was chosen
    over Alt+Space because Alt+Space opens the window menu on Windows and most Linux desktops before the
    page sees it, and Option+Space types a non-breaking space on macOS. Ctrl+Space works in Chrome and
    Firefox on Windows/Linux; on macOS the system takes it only when several input sources are enabled.
    Alt+Left/Right are prevented so they do not navigate history; on macOS that replaces Option+Arrow
    word jumps inside the textarea (Alt+Shift+Arrow selection stays native).
- Voice dataset dialog, from the "Voice dataset" button in the canvas list page header: the stats line
  ("12 of 15 takes checked · 4m 10s checked audio · 9 with word timings"), an "Include unchecked takes"
  checkbox (default off; a warning when there are no checked takes and it is off) and "Download dataset
  (.zip)". The download is an authenticated axios GET into a blob, saved through an object URL under the
  `Content-Disposition` file name.

## Non-scope

- Editing a transcript before it lands (fix it in the editor afterwards).
- Pausing/resuming a recording, or recording several takes into one upload.
- Choosing a microphone or language, or editing the prompt (the server auto-detects the language; the
  hints switch only turns the server-built word-bank prompt on or off).
- Showing the prompt or the detected language of a take.
- Checking transcripts on the canvas list page, or a dataset export on Android (web only).
- Cutting or segmenting audio for the dataset (the server ships original audio plus timings).
- Undoing a take on the server. Undo/redo stays local-snapshot based, as for every other snippet action.
- Showing a new snippet that is hidden by the active tag filter: it is created and selected, but stays
  hidden until the filter changes.
- Offline queueing: a take lives in memory until it is uploaded or discarded. Closing the tab loses it.

## Data and control flow

```
Record button / Alt+Shift+R (CanvasPage keydown)
  -> voiceStore.openRecorder()
       recordingSupport(browserRecordingEnvironment())          refuses insecure / unsupported
       resolveCaptureTarget({ editingSnippetId, viewport, canvasViewSize })
         editor open      -> { kind: 'append', snippetId }
         else             -> { kind: 'new', position: viewportCenter(viewport, canvasViewSize) | (0,0) }
       set request = { canvasId, target }                        target fixed for this modal
  -> CanvasPage renders <VoiceCaptureModal/> -> VoiceCaptureDialog
       useEffect: new VoiceSession(deps); session.start()        (StrictMode-safe: one per mount)

VoiceSession (lib/voice/session.ts), drives pure reducers:
  start   -> recorder: idle -> requesting_permission
             deps.startCapture() = startMediaCapture()           getUserMedia -> MediaRecorder.start()
             resolves at recorder 'start' with startedAt = performance.now()
          -> recorder: recording(startedAt); 100 ms ticker starts
  ticker  -> rms = handle.rms() (AnalyserNode time-domain RMS)
             stepVad(vad, { rms, at }, silenceSeconds() * 1000)  setting read live from voiceStore
             snapshot.level = meterLevel(rms); snapshot.elapsedMs
             vad.stop -> session.stop()
  tap     -> reduceGesture(gesture, { type: 'tap', at: now })
             effects: arm_timer(300) / cancel_timer / commit_break(at) -> recorder 'break'
                      stop -> finish()
  Stop    -> gesture 'flush' (commits a pending tap) -> finish()
  finish  -> recorder: stopping; handle.stop() -> { blob, mimeType, durationMs }; mic released
          -> recorder: uploading(take, attempt 1) (breaks >= duration dropped)
  upload  -> deps.beforeUpload()                                 append: editorBridge(S).flush() (PUT)
                 rejects -> upload_failed(editor_save_failed), nothing uploaded
             deps.upload(take, signal) = voiceApi.createTake(canvasId, take, toWireTarget(target), signal)
                 multipart: audio (Blob, part type = recorder mime) + meta JSON
                 { breaks_ms, duration_ms, target }
             ok  -> recorder: done(result); deps.onDone(result) = voiceStore.completeTake(result)
             err -> recorder: upload_failed(take, toVoiceError(err), attempt)
  Retry   -> upload_failed: uploading(same take, attempt + 1) | capture_failed: requesting_permission
  Cancel  -> any state: generation++, gesture reset, ticker stop, handle.cancel() (mic released),
             upload aborted, recorder idle; late callbacks of the old attempt are dropped
  dispose -> cancel + no further notifications (modal unmount, leaving the page)

voiceStore.completeTake(result)       (ignored if result.snippet.canvas_id is not the loaded canvas)
  canvasStore.applySnippet(snippet)                              upsert + undo snapshot, no reload
  created  -> canvasStore.setSelection({id}); list view: revealSnippetId = id
              ListView effect scrolls the row into view and focuses it, then clearReveal(id)
  appended -> editorBridge(id).reload(snippet)                   editor rows = initEditorState(server text, grid)
  recordingsRevision++                                           open RecordingsSection refetches
VoiceCaptureDialog sees recorder 'done' -> voiceStore.closeRecorder()

RecordingsSection (in InlineGridEditor, below the grid body)
  mount / recordingsRevision -> voiceApi.listRecordings(snippetId) (newest first)
  play   -> reducePlayback(toggle) -> load_and_play: voiceApi.fetchAudio(id) (axios, bearer token,
            responseType blob) -> URL.createObjectURL -> one shared <audio> element; URL cached per id
  delete -> inline "Delete? Yes / No" -> voiceApi.deleteRecording(id) -> drop row, revoke its URL
             (recording_not_found or a bare 404: "already deleted", row dropped)
  check  -> pause list playback; checkingId = id
            createPortal(<CheckTranscriptModal recording onUpdated onClose/>, document.body)
            onUpdated(updated) -> rows = withVerification(rows, updated)   badge follows the server
  unmount -> pause, detach source, revoke every URL

Recorder hints switch
  voiceStore.vocabHints = loadVocabHints(localStorage)                    default true, guarded
  switch click -> voiceStore.setVocabHints(v) -> saveVocabHints (failure logged) + set
  VoiceSession upload dep -> voiceApi.createTake(..., signal, { vocabularyHints: voiceStore.vocabHints })
                             meta = { breaks_ms, duration_ms, target, vocabulary_hints }

CheckTranscriptModal (components/voice/check/)
  reference = referenceOf(recording)     timed { words, lines[{text, words: indices}] } | untimed { lines }
  initial   = correctionPrefill(recording)  (taken once per open; responses never reset the field)
  useRecordingPlayer(id, duration_ms)
    voiceApi.fetchAudio(id) -> object URL -> owned Audio element
    play -> rAF loop: state.positionMs = currentTime * 1000; pause/ended/seeked -> snapshot
    durationMs = audio.duration when finite, else recording.duration_ms (MediaRecorder WebM)
  activeWord = wordIndexAt(reference.words, positionMs)     binary search, null in gaps
  word tap   -> player.playFrom(words[i].start_ms)
  keydown (root, propagation stopped) -> checkModalKeyAction(e)
       save | toggle_play | seek_back/forward (clampSeekMs +-3000) | cancel | native
  reduceCheck(state, event) -> { state, effect }
       save(text)    -> checkVerificationText: local failure | saving + put(normalized)
       put           -> voiceApi.saveVerification(id, text) -> onUpdated -> saved -> close
       uncheck       -> unchecking + delete -> voiceApi.clearVerification(id) -> onUpdated -> editing
       cancel(dirty) -> close | confirm_discard -> discard (close) | keep_editing
       request_failed(VoiceError) -> failed { action, error } (inline alert, actions enabled again)

VoiceDatasetDialog (components/voice/dataset/, CanvasListPage header button)
  open     -> voiceApi.datasetStats() -> formatDatasetStats(stats) | failed alert
  download -> voiceApi.exportDataset({ includeUnverified })
                GET /api/recordings/export?include_unverified=, responseType blob
                filename = filenameFromContentDisposition(header) ?? fallbackExportFilename(now)
                error: Blob body decoded as JSON; toVoiceError(err, { 413: 'export_too_large' })
           -> saveBlobAs(blob, filename): object URL, hidden <a download> click, revoke after 60 s
  close (Escape, Close, backdrop) -> abort stats and export requests
```

The editor stays open during a take. The click-outside handler in `InlineGridEditor` (save and close on
mousedown outside) ignores targets inside `[data-keep-editor-open]`, which the Record buttons and the
modal carry. While the modal is open, `CanvasPage`'s global shortcuts are ignored and the modal stops
key propagation, so Delete, Backspace and Ctrl+Z cannot act on the canvas behind it.

### Error mapping

`toVoiceError` reads `detail.error` from `{"detail": {"error", "message"}}`. When the body has no contract
code, it falls back on the status: 413 means `audio_too_large` (an nginx HTML 413 included), 415
`unsupported_audio_type`, 404 `not_found`, 502/503/504 `server_unavailable`, and anything else `http`.
No response at all is `network`, and a cancel is `aborted`.

| Kind | Modal title | Retry |
|------|-------------|-------|
| `stt_unavailable` | The transcription service is not ready | yes |
| `stt_failed` | Transcription failed | yes |
| `server_unavailable`, `network`, `http`, `aborted` | not responding / could not reach / failed / cancelled | yes |
| `editor_save_failed` | Saving the open editor failed (nothing uploaded) | yes |
| `stt_disabled` | Transcription is turned off on this server | no |
| `empty_transcript` | No words were recognised | no |
| `audio_too_large` | The recording is too long to upload | no |
| `unsupported_audio_type`, `invalid_meta` | cannot read this format / upload rejected | no |
| `snippet_not_found`, `canvas_not_found`, `not_found` | deleted / gone | no |
| `recording_not_found` | This recording no longer exists | no |
| `invalid_verification` | The transcript cannot be saved (empty or over 10 000 characters) | no |
| `export_too_large` | The dataset is too large to export (export only checked takes) | no |

The check and dataset views show the same titles inline. Export requests use `responseType: 'blob'`, so
their error body arrives as a Blob; `exportDataset` decodes it as JSON first, and maps a bare 413 (no
contract code, e.g. nginx) to `export_too_large` instead of the upload's `audio_too_large`.

Every upload error also offers Save audio and Discard. Capture errors (`permission_denied`,
`no_microphone`, `microphone_busy`, `recorder_failed`) offer Retry, which asks for the mic again;
`insecure_context` and `unsupported` do not.

## Files

| File | Role | Key exports |
|------|------|-------------|
| `frontend/src/lib/voice/types.ts` | Contract wire types and typed errors | `VoiceTakeTarget`, `VoiceTakeMeta`, `RecordingResponse` (dataset fields optional + nullable), `TimedWord`, `LineSpan`, `VoiceDatasetStats`, `DatasetDownload`, `VoiceTakeResponse`, `SplitMode`, `VOICE_SERVER_ERROR_CODES`, `VoiceServerErrorCode`, `VoiceErrorKind`, `VoiceError`, `CaptureErrorKind`, `CaptureError`, `Take` |
| `frontend/src/lib/voice/errors.ts` | axios / DOMException -> typed errors; modal copy and retryability | `toVoiceError(err, statusKinds?)`, `StatusKinds`, `isRecordingGone`, `captureErrorFromException`, `describeVoiceError`, `describeCaptureError`, `ErrorDescription` |
| `frontend/src/lib/voice/vocabHintsSetting.ts` | Guarded per-viewer hints switch | `VOCAB_HINTS_STORAGE_KEY`, `DEFAULT_VOCAB_HINTS`, `parseVocabHints`, `loadVocabHints`, `saveVocabHints`, `SaveVocabHintsResult` |
| `frontend/src/lib/voice/recordingView.ts` | Wire recording -> checked/unchecked, timed/untimed reference, prefill | `verificationOf`, `Verification`, `whisperLines`, `correctionPrefill`, `referenceOf`, `TranscriptReference`, `TimedLine`, `withVerification` |
| `frontend/src/lib/voice/timing.ts` | Playback time -> word, seek clamping | `wordIndexAt`, `clampSeekMs`, `SEEK_STEP_MS` |
| `frontend/src/lib/voice/verification.ts` | Client mirror of the server normalization and limits | `normalizeVerificationText`, `checkVerificationText`, `MAX_VERIFICATION_CODE_POINTS` |
| `frontend/src/lib/voice/checkTranscript.ts` | Pure request/confirm state of the check view | `reduceCheck`, `CheckState`, `CheckEvent`, `CheckEffect`, `CheckError`, `checkErrorMessage`, `CHECK_HINT`, `EDITING` |
| `frontend/src/lib/voice/checkKeys.ts` | Check view shortcuts | `checkModalKeyAction`, `CheckKeyAction`, `PLAYBACK_TOGGLE_LABEL` |
| `frontend/src/lib/voice/dataset.ts` | Stats wording, export file names | `formatDatasetStats`, `formatAudioTotal`, `filenameFromContentDisposition`, `fallbackExportFilename` |
| `frontend/src/lib/voice/recorder.ts` | Pure recorder state machine | `RecorderState`, `RecorderEvent`, `reduceRecorder`, `IDLE_RECORDER`, `lineNumber`, `canCancel` |
| `frontend/src/lib/voice/gesture.ts` | Pure tap / double-tap reducer | `reduceGesture`, `GestureState`, `GestureEvent`, `GestureEffect`, `DOUBLE_TAP_WINDOW_MS` |
| `frontend/src/lib/voice/vad.ts` | Pure silence detector | `stepVad`, `VadState`, `INITIAL_VAD`, `speechThreshold`, `MIN_SPEECH_RMS`, `SPEECH_RATIO`, `MIN_SPEECH_MS`, `FLOOR_WINDOW_MS` |
| `frontend/src/lib/voice/level.ts` | RMS and dB meter mapping | `rmsOf`, `meterLevel` |
| `frontend/src/lib/voice/silenceSetting.ts` | Guarded per-viewer setting | `SILENCE_CHOICES`, `SilenceSeconds`, `loadSilenceSeconds`, `saveSilenceSeconds`, `parseSilenceSeconds`, `silenceLabel`, `SILENCE_STORAGE_KEY` |
| `frontend/src/lib/voice/support.ts` | Secure-context / API probe, mime preference, part filename | `recordingSupport`, `browserRecordingEnvironment`, `pickMimeType`, `MIME_PREFERENCE`, `uploadFileName`, `HTTPS_REQUIRED_MESSAGE` |
| `frontend/src/lib/voice/target.ts` | Append vs new-at-viewport-centre | `resolveCaptureTarget`, `toWireTarget`, `CaptureTarget` |
| `frontend/src/lib/voice/keys.ts` | Shortcut, modal keys, focus-trap index | `isRecordShortcut`, `RECORD_SHORTCUT_LABEL`, `modalKeyAction`, `trapFocusIndex` |
| `frontend/src/lib/voice/playback.ts` | Pure play/pause state for the recordings list | `reducePlayback`, `PlaybackState`, `PlaybackEvent`, `PlaybackEffect` |
| `frontend/src/lib/voice/format.ts` | Durations, dates, transcript previews | `formatDuration`, `formatRecordedAt`, `transcriptPreview` |
| `frontend/src/lib/voice/session.ts` | Orchestrator over the reducers, capture and upload (no DOM) | `VoiceSession`, `SessionDeps`, `SessionSnapshot`, `CaptureHandle`, `RecordedAudio` |
| `frontend/src/lib/voice/mediaCapture.ts` | Thin browser adapter: getUserMedia, MediaRecorder, AnalyserNode | `startMediaCapture`, `browserCaptureEnvironment`, `CaptureEnvironment` |
| `frontend/src/lib/voice/index.ts` | Barrel | |
| `frontend/src/api/voice.ts` | Endpoints over an injected axios instance; rejects with `VoiceError` only | `createVoiceApi`, `VoiceApi` (`createTake`, `listRecordings`, `fetchAudio`, `deleteRecording`, `saveVerification`, `clearVerification`, `datasetStats`, `exportDataset`), `TakeOptions`, `ExportOptions`, `buildTakeForm`, `buildTakeMeta` |
| `frontend/src/api/client.ts` | Binds the API to the authenticated instance | `voiceApi` |
| `frontend/src/stores/voiceStore.ts` | Modal request, silence and hints settings, take hand-off | `useVoiceStore` (`openRecorder`, `closeRecorder`, `setSilenceSeconds`, `vocabHints`, `setVocabHints`, `completeTake`, `clearReveal`, `revealSnippetId`, `recordingsRevision`), `RecorderRequest`, `OpenRecorderResult` |
| `frontend/src/stores/canvasStore.ts` | `canvasViewSize` / `setCanvasViewSize`, `applySnippet` | |
| `frontend/src/components/canvas/editorBridge.ts` | Flush / reload handle the open editor registers | `registerEditorBridge`, `editorBridge`, `EditorBridge` |
| `frontend/src/components/voice/VoiceCaptureModal.tsx` | Store-connected dialog; owns the session | default |
| `frontend/src/components/voice/VoiceCaptureView.tsx` | Presentational modal (tap surface, states, controls) | default, `VoiceCaptureViewProps` |
| `frontend/src/components/voice/useFocusTrap.ts` | Tab wrap and focus restore | `useFocusTrap` |
| `frontend/src/components/voice/RecordButton.tsx` | Toolbar / editor button with the support tooltip | default, `recordButtonTitle` |
| `frontend/src/components/voice/RecordingsSection.tsx` | Editor recordings: fetch, play, delete, open the check modal, apply its responses | default |
| `frontend/src/components/voice/RecordingsListView.tsx` | Presentational recordings list (badge, Check) | default, `RecordingsListViewProps` |
| `frontend/src/components/voice/check/CheckTranscriptModal.tsx` | Check view: requests, keys, focus | default |
| `frontend/src/components/voice/check/CheckTranscriptView.tsx` | Presentational check view | default, `CheckTranscriptViewProps` |
| `frontend/src/components/voice/check/AudioPlayerBar.tsx` | Play/pause, scrubber, time | default, `AudioPlayerBarProps` |
| `frontend/src/components/voice/check/TranscriptReferenceView.tsx` | Whisper lines / tappable words with highlight | default, `TranscriptReferenceViewProps` |
| `frontend/src/components/voice/check/useRecordingPlayer.ts` | Owned audio element, blob URL, rAF position | `useRecordingPlayer`, `PlayerState`, `RecordingPlayer` |
| `frontend/src/components/voice/dataset/VoiceDatasetDialog.tsx` | Stats load, export download, abort on close | default |
| `frontend/src/components/voice/dataset/VoiceDatasetView.tsx` | Presentational dataset dialog | default, `VoiceDatasetViewProps`, `StatsState`, `DownloadState` |
| `frontend/src/components/voice/dataset/saveBlob.ts` | Object URL + `<a download>` save | `saveBlobAs` |
| `frontend/src/components/pages/CanvasListPage.tsx` | "Voice dataset" header button | |
| `frontend/src/components/canvas/InlineGridEditor.tsx` | Registers the bridge, Rec button, recordings section, click-outside exemption | |
| `frontend/src/components/canvas/CanvasViewport.tsx` | Reports its size to `canvasStore.setCanvasViewSize` | |
| `frontend/src/components/list/ListView.tsx` | Scrolls to `revealSnippetId` | |
| `frontend/src/components/toolbar/Toolbar.tsx` | Record button | |
| `frontend/src/components/pages/CanvasPage.tsx` | Alt+Shift+R, modal mount, shortcut guard, close on leave | |

Tests: `lib/voice/*.test.ts` (reducers, VAD, session under fake timers, adapter against fake
MediaRecorder / AudioContext), `api/voice.test.ts` (mock axios adapter; proves the multipart body is not
JSON-encoded by the instance's JSON default), `stores/voiceStore.test.ts`,
`components/canvas/editorBridge.test.ts`, `components/voice/*.test.tsx` (static markup). Dataset
features: `lib/voice/{vocabHintsSetting,recordingView,timing,verification,checkTranscript,checkKeys,dataset,errors.dataset}.test.ts`,
`api/voiceDataset.test.ts` (verification, stats, export with headers and Blob error bodies),
`stores/voiceStore.vocabHints.test.ts`, `components/voice/check/CheckTranscriptView.test.tsx`,
`components/voice/dataset/VoiceDatasetView.test.tsx`, `components/voice/RecordingsListView.check.test.tsx`,
`components/voice/VoiceCaptureView.vocabHints.test.tsx`.

## Invariants and constraints

- A recorder state carries exactly the data it needs: `uploading` and `upload_failed` always hold the
  take, and `recording` holds `startedAt` and the ascending breaks. Events that do not apply to a state are
  ignored (the same object is returned).
- Breaks are whole ms since the recorder's `start` event, strictly ascending and below the take's duration.
  Taps before the recorder started are ignored.
- The pending tap of a double tap is never committed. Stop by button or silence commits a pending single
  tap. Cancel drops it.
- Every exit path (stop, cancel, failure, unmount, leaving the page) stops all mic tracks and closes the
  `AudioContext` exactly once. A permission prompt that resolves after cancel is released immediately.
- The editor's pending edits are saved (PUT) before every append upload attempt. If that save fails,
  nothing is uploaded.
- After an append, the open editor's rows come from the server's snippet. Without this, closing the
  editor would save the pre-take rows over the appended lines.
- A take's blob stays in memory until success, Discard or Cancel. Retry re-sends the same blob.
- The target is fixed when the modal opens and is never re-read mid-take.
- The VAD stops only after speech was heard, and only when `silenceMs > 0`. Silence must be below the
  floor-relative threshold and at most a third of the loudest sustained level, so a loud steady signal
  (a fan turning on) is never treated as silence.
- `localStorage` access is guarded. Without storage the setting defaults to off and changes apply for the
  session.
- Blob URLs for playback are revoked on delete and when the editor closes.
- The app always sends `meta.vocabulary_hints` (a boolean); only a caller without options omits it.
- A recording is checked iff `verified_text` is a string (`verificationOf`); the badge, the prefill and
  Mark unchecked all derive from that one function. After a PUT/DELETE the row is replaced by the server
  response, never patched locally.
- Word interaction is offered only when `has_timings` is true and `line_spans` are contiguous,
  non-overlapping and cover every word; otherwise the reference falls back to plain lines (logged).
- `wordIndexAt` returns the word with `start_ms <= t < end_ms` (the latest started one on overlap), null
  in gaps; it assumes service order (ascending `start_ms`).
- At most one verification request is in flight; while it runs every action is ignored and the field is
  read-only. The check modal never writes to the snippet.
- The check modal stops keydown propagation and carries `data-keep-editor-open`, so the editor stays open
  and page shortcuts (Delete, Ctrl+Z) never act behind it. Its audio element, frame loop and blob URL
  are released on close.
- The dataset export never goes through a plain link: it needs the bearer token, so it is an axios blob
  download. Its object URL is revoked 60 s after the click.
- Multipart uploads set `Content-Type: multipart/form-data` explicitly. The instance's JSON default would
  otherwise make axios serialise the FormData to JSON. In the browser axios then drops the header so the
  boundary is set.

## Known limitations

- Cancel during upload aborts the request, but if the server already finished, the snippet and the
  recording exist and appear on the next canvas load.
- Safari and iOS behaviour (the `audio/mp4` path, `webkitAudioContext`, autoplay rules for the analyser)
  is implemented but has not been verified on a device.
- Without Web Audio the recording still works, but the meter reads 0 and silence auto-stop never fires.
- MediaRecorder WebM has no duration in its header (`audio.duration` is Infinity); the scrubber then uses
  the server's `duration_ms`. Seeking such a file relies on the browser scanning the blob, which has not
  been verified across browsers.
- The export is fully buffered in memory as a Blob before it is saved (no streaming to disk); the server
  caps it at `VOICE_EXPORT_MAX_BYTES` of audio (default 1 GiB).
- With a cross-origin `VITE_API_URL`, the server must expose `Content-Disposition`
  (`Access-Control-Expose-Headers`) or the client-stamped fallback name is used. The deployed app is
  same-origin.
- The check view, the hints switch and the dataset dialog are covered by unit and static-markup tests
  only; they have not been exercised in a real browser against the new backend.
