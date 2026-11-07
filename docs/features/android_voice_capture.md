# Android Voice Capture

Dictate lyrics into the Android app: a Record button opens a full-screen recording modal; a single tap
anywhere marks "next line", a double tap stops, optional silence auto-stop. The take is uploaded, the
server transcribes it and either creates a new snippet (canvas / list mode) or appends the lines to the
snippet open in the editor. The audio is kept on the server and can be played back and deleted from the
editor's Recordings section. Each take can be opened in a Check transcript screen, listened to and
corrected to what was actually said; the result is saved on the recording (never the snippet) as
training data, and the canvas list's overflow menu shows the voice dataset stats.

This is the Android half of §3 of the shared voice contract, plus the Android parts (§4) of the voice
dataset contract (word-bank hints, transcript checking, stats); the backend routes, line assembly, hint
prompt, verification / stats / export endpoints and STT service are documented in
[voice_capture.md](./voice_capture.md) and [voice_dataset.md](./voice_dataset.md).
The web client implements the same behaviour.

## Scope

- Record entry points: canvas screen top bar (canvas and list mode) and the editor screen top bar.
- Targeting: editor open -> pending edits are saved first, the take is appended, the editor rows are
  replaced from the returned snippet; otherwise a new snippet at the viewport centre in canvas coordinates
  (list mode: the last canvas viewport, or the origin), which is then selected (and scrolled to in list mode).
- Recording modal: elapsed time, live level meter, "Line N", Stop, Cancel, tap / double-tap gestures,
  silence auto-stop setting (off / 2 / 3 / 5 / 8 / 10 s, persisted per device, default off).
- RECORD_AUDIO runtime permission with a rationale, a denied state, and a permanently-denied path to the
  app settings (re-checked on return).
- MediaRecorder: OGG / Opus on API 29+ (`audio/ogg`), AAC in MPEG-4 below (`audio/mp4`), mono, written to
  the app cache; amplitude polled every 50 ms with `getMaxAmplitude()`.
- Upload with a spinner; typed errors from the server's codes; Retry keeps the file, Discard deletes it.
- Recordings section in the editor: date, duration, transcript preview, a Checked / Not checked badge,
  play / pause / resume, Check transcript, delete with confirmation.
- "Use word bank as hints" switch in the recording modal (on by default, per device in DataStore key
  `voice_vocab_hints`), sent as `meta.vocabulary_hints` with every upload and retry.
- Check transcript screen (`CheckTranscriptRoute(snippetId, recordingId)`, full screen above the editor):
  player (play / pause, scrubber, `m:ss / m:ss`), the read-only Whisper reference line by line (with
  timings: each word tappable to seek there and play, the word being played highlighted; without: plain
  lines), a correction field prefilled with `verified_text` or the Whisper lines, the contract hint text,
  Save as checked (PUT, closes), Mark unchecked (DELETE, only when checked, stays open), Cancel / back /
  ✕ (confirm when the field differs from its prefill). Errors are inline and typed.
- Voice dataset info dialog: canvas list overflow (⋮) -> "Voice dataset" -> `GET /api/recordings/stats`
  as "12 of 15 takes checked · 4m 10s checked audio · 9 with word timings" and "Export the dataset from
  the web app".

## Non-scope

- Background recording (a foreground service). Leaving the app mid-take finishes the take and holds it
  for the user (Upload / Discard); nothing records while the app is stopped.
- Offline queueing: a held take lives only as long as the modal's ViewModel (the file is in the cache).
- Re-transcribing a recording; seeking within the editor list's playback (the Check transcript player
  seeks).
- Dataset export (web only; there is no export call on Android). Keyboard shortcuts of the web check
  modal.
- Editing the snippet from the check screen: the correction trains transcription and never changes the
  snippet.
- Line splitting / joining in the editor ([editor_line_split_join.md](./editor_line_split_join.md)).

## Flow

```
Record (canvas)  -> CanvasViewModel.voiceTarget()  = New(viewportCenter(viewport, viewSize))
Record (editor)  -> GridEditorViewModel.flushPendingEdits()   (PUT only if rows differ from the baseline)
                    -> onSnippetChanged(saved) -> Append(snippet.id)      | failure: snackbar, no modal
  -> VoiceCaptureViewModel.open(target)                                   RecorderState.Permission(REQUESTING, 1)
VoiceCaptureHost: permissionAction(granted, shouldShowRationale, firstAsk)
     PROCEED -> permissionGranted()       SHOW_RATIONALE -> Permission(RATIONALE) -- Allow --> REQUESTING(2) -> system dialog
     LAUNCH_REQUEST -> system dialog -> granted | PermissionDenied(permanently = !shouldShowRationale)
     PERMANENTLY_DENIED -- Open settings --> ON_RESUME re-check -> permissionGranted()
  -> Starting + StartRecorder -> AudioRecorder.start() -> RecorderStarted(audio, clock(), silence)
  -> Recording; every 50 ms Sample(clock(), maxAmplitude()):
        elapsed, level = levelFraction(amp), TapState.tick(elapsed), VadState.sample(...)
     pointer down on the tap surface -> Tap(clock()) -> TapState.down(t - startedAt)
        second down < 300 ms after the first -> stop (pending break dropped)
     Stop button -> Stop (a pending single tap is kept)       VAD stop -> same as Stop
     ON_STOP -> Interrupt -> finish, then Held(Interrupted)    Cancel -> abort + delete file
  -> Finishing + FinishRecorder -> AudioRecorder.finish() -> RecorderFinished | RecorderFinishFailed(NO_AUDIO)
  -> Uploading + Upload -> VoiceRepository.uploadTake(canvasId, file, mime, VoiceTakeMeta.of(breaks, duration, target))
        201 -> Done (local file deleted) -> VoiceTakeSaved emitted, Acknowledge -> Idle
        error -> Held(UploadFailed(toVoiceUploadError(apiError)))  -- Retry --> Uploading (same file) | Discard
Canvas:  saved -> CanvasViewModel.onVoiceTakeSaved: insert + select; list mode -> RevealSnippet -> animateScrollToItem
Editor:  saved -> GridEditorViewModel.reloadFrom(snippet), RecordingsViewModel.add(recording), onSnippetChanged(snippet)
Recordings: RecordingsViewModel.refresh -> GET /api/snippets/{id}/recordings
   Play -> cache/recordings/recording-<id>.<ext> exists ? play : GET /api/recordings/{id}/audio (bearer) -> file -> MediaPlayer
   Delete -> confirm dialog -> DELETE /api/recordings/{id} -> remove row + cached file
   Check transcript -> RecordingsViewModel.stopPlayback -> nav CheckTranscriptRoute(snippetId, recordingId)
```

### Vocabulary hints

`VoiceCaptureViewModel.vocabularyHints` is `VoiceSettingsStore.vocabularyHints` (DataStore `voice`, key
`voice_vocab_hints`, absent = true) as a StateFlow; the switch calls `setVocabularyHints`, which only
persists. Each `Upload` effect builds `take.meta(target, vocabularyHints.value)`, so a Retry sends the
current value. `VoiceTakeMeta` always encodes `vocabulary_hints` (`encodeDefaults`). Whether a prompt is
actually sent is the server's decision (bank words, `VOICE_VOCAB_HINTS`).

### Check transcript

```
CheckTranscriptViewModel(snippetId, recordingId, voice, cacheDir, player: SeekableAudioPlayer, updates)
  init -> GET /api/snippets/{snippetId}/recordings -> find recordingId
        absent -> LoadFailed(Rejected(recording_not_found, 404))     failure -> LoadFailed(toVoiceUploadError) -- Retry
        found  -> Ready(recording, transcriptReference(recording), text = baseline = initialCorrectionText(recording),
                        player = Loading)
               -> ensureRecordingAudio (cache/recordings/recording-<id>.<ext>, else GET audio) -> PlayerUi.Ready(0, duration_ms) | Failed(Download)
  Play   -> not loaded: player.play(file, onComplete) (false -> Failed(Unplayable), cached file deleted), seekTo(position) if > 0
            loaded + paused: resume                     playing: pause
            while playing: every 100 ms position = player.positionMs() clamped to [0, duration]
  Scrubber released / seekTo(ms) -> clamp; loaded ? player.seekTo : remembered for the next play
  Word tap (Timed only) -> seekTo(word.start_ms), and play when not playing
  currentWord = wordIndexAt(words, position)  (the word with start <= position < end; null in gaps)
  Save   -> validateVerification(text): Invalid(Empty | TooLong) -> inline, no request
            Valid -> SAVING -> PUT /api/recordings/{id}/verification {text} -> updates.publish(recording), Close
                                                                       | failure -> inline CheckError.Server(VoiceUploadError)
  Mark unchecked (checked only) -> CLEARING -> DELETE .../verification -> recording replaced, updates.publish; field kept
  Cancel / back / ✕ -> dirty (text != baseline) ? confirm (Discard -> Close | Keep editing) : Close (pauses first)
  screen ON_STOP -> pause          ViewModel cleared -> player.stop()
Editor: RecordingsViewModel(updates = container.recordingUpdates.updates) replaces the row with the same id
```

The screen loads the recording by ids rather than receiving it, so the route survives process death and
stays small; `words` come with the list endpoint. The text sent is the field as typed; the server
normalises it (`normalizeVerification` mirrors that rule only for the local empty / length check).

`transcriptReference` uses timings only when `has_timings` is set, `words` and `line_spans` are present,
there is one span per reference line, and the spans are contiguous `[start, end)` pairs from 0 covering
every word; anything else shows plain lines. A blank line has an empty span and shows as an empty line.

### Dataset stats

`VoiceDatasetViewModel` (canvas list entry): `open()` -> `Loading` -> `GET /api/recordings/stats` ->
`Loaded(stats)` | `Failed(VoiceUploadError)` (Retry = `open()`); `close()` cancels and returns to `Closed`,
so a late answer is dropped. Opening again always refetches. `datasetSummary` / `formatAudioLength` format
the line.

All times come from one monotonic clock (`SystemClock.elapsedRealtime`) read on the main thread: the
recorder start right after `MediaRecorder.start()` returns, each tap at its pointer down, each sample at
its poll. Breaks are therefore ms since the recorder actually started.

### Gestures

The modal's tap surface is a full-size layer *below* the text, chips and buttons. Compose delivers a
touch to the topmost sibling that handles pointer input, so Stop, Cancel and the auto-stop chips never
count as taps; plain text and the level meter have no pointer input, so touches there reach the surface.
The surface reacts to the down (`awaitFirstDown`), not the up. For accessibility it also has an
`onClick` semantics action "Next line".

### Silence VAD

`VadState.sample` works on dBFS of each 50 ms peak amplitude. The noise floor starts at the first sample
(never below -65 dBFS), falls 30 % of the gap per sample toward quieter input and rises at 2 dB/s toward
louder background, but never during speech. Speech = 15 dB above the floor; silence = less than 9 dB
above. It arms after one speech run of at least 150 ms, then stops after N s of continuous silence; a
non-silent blip shorter than 200 ms does not break the run, speech does. N = 0 (off) never stops.

### Errors

The server's `{"detail": {"error": code, "message": ...}}` is parsed by `ApiError.fromHttp` into
`ApiError.code` / `message` (plain and pydantic details are unchanged). `toVoiceUploadError` maps it to
`VoiceUploadError.Rejected(VoiceErrorCode, status, message)` for the contract codes
(`canvas_not_found`, `snippet_not_found`, `audio_too_large`, `unsupported_audio_type`, `invalid_meta`,
`empty_transcript`, `stt_disabled`, `stt_unavailable`, `stt_failed`, `recording_not_found`,
`invalid_verification`, `export_too_large`), `SessionExpired` for 401, `Http` for anything else, `Network`,
`Unexpected`. Each has its own user text (`VoiceMessages.kt`). The same type is used for the
verification, stats and check-screen load calls. The check screen wraps it in `CheckError.Server`, beside
`CheckError.Input(VerificationInputError.Empty | TooLong)` for a correction refused before sending;
player problems are `PlayerFailure.Download(VoiceUploadError) | Unplayable` (`check/CheckMessages.kt`).

## Files

Paths under `android/`.

| Module | File | Role / key exports |
|---|---|---|
| `:core:model` | `model/voice/Voice.kt` | `RecordingId`, `AudioMime` (`wire`, `extension`, `parse`), `SplitMode`, sealed `VoiceTakeTarget` (`New`, `Append`; discriminator `kind`), `VoiceTakeMeta` (only via `of`: breaks sorted, distinct, within `[0, duration]`; `vocabularyHints`, default true), `Recording` (dataset fields nullable / defaulted; `verification`, `isChecked`, `referenceLines`), `VoiceTakeResponse` |
| | `model/voice/VoiceDataset.kt` | `TimedWord`, `Verification` (text + verified_at, only from both fields), `VerificationRequest`, `RecordingStats` |
| | `model/voice/VoiceErrorCode.kt` | `VoiceErrorCode` (`wire`, `parse`; includes `INVALID_VERIFICATION`, `EXPORT_TOO_LARGE`) |
| | `model/error/ApiError.kt` | `ApiError.code` (structured detail), `errorCodeFromBody`; `detailFromBody` also reads a structured detail's `message` |
| `:core:logic` | `logic/voice/TapGesture.kt` | `DOUBLE_TAP_WINDOW_MS`, `TapState` (`down`, `tick`, `finish`, `lineNumber`), `TapStep` |
| | `logic/voice/SilenceVad.kt` | `SilenceTimeout`, `amplitudeToDbfs`, `levelFraction`, `VadConfig`, `VadState` (`sample`), `VadStep` |
| | `logic/voice/RecorderMachine.kt` | `AudioFile`, `CapturedTake` (`meta`), `PermissionStatus`, `RecorderFailure`, `HoldReason`, `AfterFinish`, sealed `RecorderState`, `RecorderEvent`, `RecorderEffect`, `RecorderTransition`, `reduceRecorder` |
| | `logic/voice/VoiceUploadError.kt` | sealed `VoiceUploadError`, `toVoiceUploadError(ApiError)` |
| | `logic/voice/TranscriptReference.kt` | `IndexedWord`, `TimedLine`, sealed `TranscriptReference` (`Plain`, `Timed`), `transcriptReference(recording)`, `wordIndexAt(words, positionMs)` |
| | `logic/voice/VerificationText.kt` | `MAX_VERIFICATION_CODE_POINTS`, `VerificationInputError`, `VerificationInput`, `initialCorrectionText`, `normalizeVerification`, `validateVerification` |
| `:core:data` | `data/ApiClient.kt` | `sendMultipart(path, MultipartBody, response)`, `download(path, target)` (to `.part`, then rename) |
| | `data/repository/VoiceRepository.kt` | `VoiceRepository` / `HttpVoiceRepository` (`uploadTake`, `recordings`, `downloadAudio`, `deleteRecording`, `saveVerification` PUT, `clearVerification` DELETE, `stats`) |
| `:app` | `voice/VoiceCaptureViewModel.kt` | `VoiceCaptureViewModel` (interprets `reduceRecorder`; `open`, `tap`, `stop`, `cancel`, `retry`, `discard`, `setSilence`, `hostStopped`, permission calls; `state`, `silence`, `saved`), `VoiceTakeSaved`, `VOICE_SAMPLE_INTERVAL_MS` |
| | `voice/VoiceSettings.kt` | `VoiceSettingsStore`, `DataStoreVoiceSettings` (DataStore `voice`, keys `silence_auto_stop_seconds`, `voice_vocab_hints`) |
| | `voice/PermissionDecision.kt` | `PermissionAction`, `permissionAction`, `deniedPermanently` (pure) |
| | `voice/VoiceMessages.kt` | `userMessage()` for `VoiceUploadError`, `HoldReason`, `RecorderFailure`; `HoldReason.retryLabel()` |
| | `voice/recorder/AudioRecorder.kt` | `AudioRecorder`, `RecorderStart` |
| | `voice/recorder/MediaRecorderAudioRecorder.kt` | MediaRecorder implementation (OGG/Opus 48 kHz 48 kbps on API 29+, AAC 44.1 kHz 96 kbps below, mono) |
| | `voice/recordings/RecordingsViewModel.kt` | `RecordingsList`, `Playback`, `RecordingsUiState`, `RecordingsViewModel` (`refresh`, `add`, `togglePlay`, `stopPlayback`, `requestDelete`, `dismissDelete`, `confirmDelete`, `messages`) |
| | `voice/recordings/AudioPlayer.kt`, `SeekableAudioPlayer.kt`, `MediaAudioPlayer.kt` | Playback interface, its seekable extension (`positionMs`, `seekTo`) and the MediaPlayer implementation of both |
| | `voice/recordings/RecordingCache.kt` | `recordingCacheFile`, `ensureRecordingAudio` (shared by the list and the check screen) |
| | `voice/recordings/RecordingUpdates.kt` | `RecordingUpdates` (`publish`, `updates`): broadcast of recordings changed by the check screen |
| | `voice/recordings/RecordingFormat.kt` | `formatDuration`, `formatRecordingDate`, `transcriptPreview` (pure) |
| | `voice/recordings/RecordingsSection.kt` | Collapsible section composable (`onCheckTranscript`), Checked badge, delete confirmation |
| | `voice/check/CheckTranscriptUiState.kt` | `PlayerFailure`, `PlayerUi`, `CheckAction`, `CheckError`, sealed `CheckTranscriptUiState` (`Loading`, `LoadFailed`, `Ready` with `dirty`, `checked`, `busy`, `currentWord`), `CheckTranscriptEvent` |
| | `voice/check/CheckTranscriptViewModel.kt` | `CheckTranscriptViewModel` (`retryLoad`, `editText`, `save`, `markUnchecked`, `cancel`, `confirmDiscard`, `keepEditing`, `togglePlay`, `pause`, `seekTo`, `seekToWord`), `CHECK_POSITION_POLL_MS` |
| | `voice/check/CheckMessages.kt` | `userMessage()` for `CheckError`, `PlayerFailure`; `CORRECTION_HINT` |
| | `voice/check/CheckTranscriptScreen.kt`, `CheckPlayerBar.kt`, `TranscriptReferenceView.kt` | The screen (top bar ✕, back = Cancel, discard dialog), player row, reference view |
| | `voice/dataset/VoiceDatasetViewModel.kt` | `DatasetStatsState`, `VoiceDatasetViewModel` (`open`, `close`) |
| | `voice/dataset/DatasetFormat.kt`, `VoiceDatasetDialog.kt` | `formatAudioLength`, `datasetSummary`, `EXPORT_ON_WEB`; the info dialog |
| | `voice/ui/VocabularyHintsSwitch.kt` | The "Use word bank as hints" row |
| | `voice/ui/VoiceCaptureHost.kt` | `voiceCaptureViewModel(container, canvasId, key)`, `VoiceCaptureHost(vm)` (permission, settings intent, lifecycle) |
| | `voice/ui/VoiceCaptureDialog.kt` | `VoiceCaptureActions`, `VoiceCaptureDialog` (full-screen modal for every state) |
| | `canvas/CanvasViewModel.kt` | `voiceTarget()`, `onVoiceTakeSaved(response)` |
| | `canvas/CanvasUiState.kt` | `CanvasEvent.RevealSnippet` |
| | `canvas/CanvasScreen.kt`, `CanvasTopBars.kt`, `list/SnippetList.kt` | Record button, host, list scroll-to, body size reported in list mode |
| | `editor/GridEditorViewModel.kt` | `flushPendingEdits()`, `reloadFrom(snippet)` |
| | `editor/GridEditorRoute.kt` | Record button, voice + recordings ViewModels, `onSnippetChanged`, `onCheckTranscript(RecordingId)` |
| | `nav/Routes.kt`, `nav/AppNavHost.kt` | `CheckTranscriptRoute(snippetId, recordingId)` and its destination |
| | `canvaslist/CanvasListScreen.kt` | Overflow menu (⋮) with "Voice dataset" |
| | `editor/ui/GridEditorScreen.kt`, `ui/EditorTopBar.kt` | `topBarActions`, `bottomPanel`, shared `snackbar` slots |
| | `di/AppContainer.kt` | `voice` (own OkHttp client: 60 s write, 150 s read), `voiceSettings`, `recordingsCacheDir`, `newRecorder()`, `newAudioPlayer()` (seekable), `recordingUpdates` |
| | `AndroidManifest.xml` | `RECORD_AUDIO`; `android.hardware.microphone` not required |

Tests: `core/model/.../voice/{VoiceDtoTest, VoiceDatasetDtoTest}`, `core/logic/.../voice/{TapGestureTest,
SilenceVadTest, RecorderMachineTest, CapturedTakeHintsTest, TranscriptReferenceTest (+ WordIndexAtTest),
VerificationTextTest}`, `core/data/.../repository/{VoiceRepositoryTest, VoiceDatasetRepositoryTest}`
(MockWebServer, multipart, download, PUT / DELETE verification, stats), `app/.../voice/{VoiceCaptureViewModelTest,
VocabularyHintsTest, RecordingsViewModelTest, RecordingUpdatesTest, CheckTranscriptViewModelTest,
CheckMessagesTest, VoiceDatasetTest, VoiceTextTest}` (fake recorder, repository, settings, players; the test
scheduler is the clock), `app/.../canvas/CanvasViewModelVoiceTest`, `app/.../editor/GridEditorVoiceTest`.

## Invariants

- Breaks are ms since the recorder started, ascending, distinct and within `[0, duration_ms]`
  (`VoiceTakeMeta.of`); a double tap never adds a break; Stop / Cancel / chip touches are never taps.
- Every state that owns a file on disk carries it, and every path out of such a state either uploads it
  or emits `DeleteFile`; a recorder that starts after Cancel is aborted and its file deleted.
- The microphone is released on finish, cancel, interruption (screen `ON_STOP`) and ViewModel clear.
- A failed upload never loses the take: it is held until Retry succeeds or the user discards it.
- An append is only uploaded after the editor's pending edits are on the server; the editor rows are then
  replaced by the server's snippet, which is also the new "nothing to save" baseline.
- Nothing reaches the canvas until the server has stored the take (`Done`).
- At most one recording plays; deleting the playing one stops it first.
- Nothing throws out of a ViewModel: errors are `RecorderState`, snackbar messages or list state.
- Every upload carries `vocabulary_hints`; the device setting defaults to on.
- A recording is checked only when both `verified_text` and `verified_at` are set (`Recording.verification`).
- The check screen never writes to the snippet; only `PUT` / `DELETE /api/recordings/{id}/verification`.
- At most one check request is in flight (`CheckAction`); Save closes only after the server stored it, and
  a failed save keeps the field and shows the typed error.
- A correction that is empty or over 10 000 code points after the server's normalisation is never sent.
- Cancel never loses edits silently: a field that differs from its prefill asks first.
- The check player position stays within `[0, duration_ms]`; a seek before the first play is applied when
  it starts; the player is paused on `ON_STOP` and released when the screen's ViewModel is cleared.
- Word taps and highlighting exist only for a reference whose spans keep the contract.
- Android never calls the export endpoint.
