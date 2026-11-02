# Android Voice Capture

Dictate lyrics into the Android app: a Record button opens a full-screen recording modal; a single tap
anywhere marks "next line", a double tap stops, optional silence auto-stop. The take is uploaded, the
server transcribes it and either creates a new snippet (canvas / list mode) or appends the lines to the
snippet open in the editor. The audio is kept on the server and can be played back and deleted from the
editor's Recordings section.

This is the Android half of §3 of the shared voice contract; the backend routes, line assembly and STT
service are documented in [voice_capture.md](./voice_capture.md).
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
- Recordings section in the editor: date, duration, transcript preview, play / pause / resume, delete with
  confirmation.

## Non-scope

- Background recording (a foreground service). Leaving the app mid-take finishes the take and holds it
  for the user (Upload / Discard); nothing records while the app is stopped.
- Offline queueing: a held take lives only as long as the modal's ViewModel (the file is in the cache).
- Editing a recording or re-transcribing it; seeking within playback.
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
```

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
`empty_transcript`, `stt_disabled`, `stt_unavailable`, `stt_failed`, plus `recording_not_found` if the
server sends it), `SessionExpired` for 401, `Http` for anything else, `Network`, `Unexpected`. Each has
its own user text (`VoiceMessages.kt`).

## Files

Paths under `android/`.

| Module | File | Role / key exports |
|---|---|---|
| `:core:model` | `model/voice/Voice.kt` | `RecordingId`, `AudioMime` (`wire`, `extension`, `parse`), `SplitMode`, sealed `VoiceTakeTarget` (`New`, `Append`; discriminator `kind`), `VoiceTakeMeta` (only via `of`: breaks sorted, distinct, within `[0, duration]`), `Recording`, `VoiceTakeResponse` |
| | `model/voice/VoiceErrorCode.kt` | `VoiceErrorCode` (`wire`, `parse`) |
| | `model/error/ApiError.kt` | `ApiError.code` (structured detail), `errorCodeFromBody`; `detailFromBody` also reads a structured detail's `message` |
| `:core:logic` | `logic/voice/TapGesture.kt` | `DOUBLE_TAP_WINDOW_MS`, `TapState` (`down`, `tick`, `finish`, `lineNumber`), `TapStep` |
| | `logic/voice/SilenceVad.kt` | `SilenceTimeout`, `amplitudeToDbfs`, `levelFraction`, `VadConfig`, `VadState` (`sample`), `VadStep` |
| | `logic/voice/RecorderMachine.kt` | `AudioFile`, `CapturedTake` (`meta`), `PermissionStatus`, `RecorderFailure`, `HoldReason`, `AfterFinish`, sealed `RecorderState`, `RecorderEvent`, `RecorderEffect`, `RecorderTransition`, `reduceRecorder` |
| | `logic/voice/VoiceUploadError.kt` | sealed `VoiceUploadError`, `toVoiceUploadError(ApiError)` |
| `:core:data` | `data/ApiClient.kt` | `sendMultipart(path, MultipartBody, response)`, `download(path, target)` (to `.part`, then rename) |
| | `data/repository/VoiceRepository.kt` | `VoiceRepository` / `HttpVoiceRepository` (`uploadTake`, `recordings`, `downloadAudio`, `deleteRecording`) |
| `:app` | `voice/VoiceCaptureViewModel.kt` | `VoiceCaptureViewModel` (interprets `reduceRecorder`; `open`, `tap`, `stop`, `cancel`, `retry`, `discard`, `setSilence`, `hostStopped`, permission calls; `state`, `silence`, `saved`), `VoiceTakeSaved`, `VOICE_SAMPLE_INTERVAL_MS` |
| | `voice/VoiceSettings.kt` | `VoiceSettingsStore`, `DataStoreVoiceSettings` (DataStore `voice`, key `silence_auto_stop_seconds`) |
| | `voice/PermissionDecision.kt` | `PermissionAction`, `permissionAction`, `deniedPermanently` (pure) |
| | `voice/VoiceMessages.kt` | `userMessage()` for `VoiceUploadError`, `HoldReason`, `RecorderFailure`; `HoldReason.retryLabel()` |
| | `voice/recorder/AudioRecorder.kt` | `AudioRecorder`, `RecorderStart` |
| | `voice/recorder/MediaRecorderAudioRecorder.kt` | MediaRecorder implementation (OGG/Opus 48 kHz 48 kbps on API 29+, AAC 44.1 kHz 96 kbps below, mono) |
| | `voice/recordings/RecordingsViewModel.kt` | `RecordingsList`, `Playback`, `RecordingsUiState`, `RecordingsViewModel` (`refresh`, `add`, `togglePlay`, `stopPlayback`, `requestDelete`, `dismissDelete`, `confirmDelete`, `messages`) |
| | `voice/recordings/AudioPlayer.kt`, `MediaAudioPlayer.kt` | Playback interface and its MediaPlayer implementation |
| | `voice/recordings/RecordingFormat.kt` | `formatDuration`, `formatRecordingDate`, `transcriptPreview` (pure) |
| | `voice/recordings/RecordingsSection.kt` | Collapsible section composable with the delete confirmation |
| | `voice/ui/VoiceCaptureHost.kt` | `voiceCaptureViewModel(container, canvasId, key)`, `VoiceCaptureHost(vm)` (permission, settings intent, lifecycle) |
| | `voice/ui/VoiceCaptureDialog.kt` | `VoiceCaptureActions`, `VoiceCaptureDialog` (full-screen modal for every state) |
| | `canvas/CanvasViewModel.kt` | `voiceTarget()`, `onVoiceTakeSaved(response)` |
| | `canvas/CanvasUiState.kt` | `CanvasEvent.RevealSnippet` |
| | `canvas/CanvasScreen.kt`, `CanvasTopBars.kt`, `list/SnippetList.kt` | Record button, host, list scroll-to, body size reported in list mode |
| | `editor/GridEditorViewModel.kt` | `flushPendingEdits()`, `reloadFrom(snippet)` |
| | `editor/GridEditorRoute.kt` | Record button, voice + recordings ViewModels, `onSnippetChanged` |
| | `editor/ui/GridEditorScreen.kt`, `ui/EditorTopBar.kt` | `topBarActions`, `bottomPanel`, shared `snackbar` slots |
| | `di/AppContainer.kt` | `voice` (own OkHttp client: 60 s write, 150 s read), `voiceSettings`, `recordingsCacheDir`, `newRecorder()`, `newAudioPlayer()` |
| | `AndroidManifest.xml` | `RECORD_AUDIO`; `android.hardware.microphone` not required |

Tests: `core/model/.../voice/VoiceDtoTest`, `core/logic/.../voice/{TapGestureTest, SilenceVadTest,
RecorderMachineTest}`, `core/data/.../repository/VoiceRepositoryTest` (MockWebServer, multipart and
download), `app/.../voice/{VoiceCaptureViewModelTest, RecordingsViewModelTest, VoiceTextTest}` (fake
recorder, repository, settings, player; the test scheduler is the clock), `app/.../canvas/CanvasViewModelVoiceTest`,
`app/.../editor/GridEditorVoiceTest`.

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
