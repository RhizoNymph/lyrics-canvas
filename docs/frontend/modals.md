# Modals

Full-screen overlay dialogs for export, the voice capture recorder, the check transcript
view and the voice dataset dialog.

## VoiceCaptureModal

**Files:** `src/components/voice/VoiceCaptureModal.tsx` (store-connected, owns a `VoiceSession`),
`src/components/voice/VoiceCaptureView.tsx` (presentational), `src/components/voice/useFocusTrap.ts`

Shown while `voiceStore.request` is set. The whole overlay is the tap target while recording
(pointerdown or Space/Enter = next line, double tap = stop). It shows the elapsed time, a level meter
(`role="meter"`), "Line N" (live region), Stop, Cancel and the silence auto-stop select, then a spinner
while uploading, then typed error states with Retry / Save audio / Discard. It is `role="dialog"` and
`aria-modal`, traps focus (Tab wraps, focus is restored on close), Escape cancels, and it stops key
propagation so page shortcuts cannot act behind it. See
[web_voice_capture.md](../features/web_voice_capture.md). Below the silence select a "Use word bank as
hints" switch (`role="switch"`) sets `voiceStore.vocabHints`.

## CheckTranscriptModal

**Files:** `src/components/voice/check/CheckTranscriptModal.tsx` (requests via `reduceCheck`, keys),
`CheckTranscriptView.tsx` (presentational), `AudioPlayerBar.tsx`, `TranscriptReferenceView.tsx`,
`useRecordingPlayer.ts`

Opened by a recording's Check button in the editor's Recordings section, rendered in a portal on
`document.body` with `data-keep-editor-open`. Player (play/pause, scrubber, time), read-only Whisper
reference (tappable words with the playing word highlighted when the recording has timings), correction
textarea with the contract hint, Save as checked / Mark unchecked (checked only) / Cancel (asks when
dirty), inline typed errors. Keys: Ctrl/Cmd+Enter save, Ctrl+Space play/pause, Alt+Left/Right seek 3 s,
Escape cancel, Tab trapped; it stops key propagation. See
[web_voice_capture.md](../features/web_voice_capture.md).

## VoiceDatasetDialog

**Files:** `src/components/voice/dataset/VoiceDatasetDialog.tsx`, `VoiceDatasetView.tsx`, `saveBlob.ts`

Opened by the canvas list page's "Voice dataset" header button. Shows the stats line from
`GET /api/recordings/stats`, an "Include unchecked takes" checkbox and "Download dataset (.zip)", which
fetches the export with the bearer token into a blob and saves it under the server's file name. Escape,
Close or a backdrop press close it and abort pending requests.

The former `SearchModal` (Ctrl+F, `GET /api/canvases/{id}/search`) is gone: the in-canvas search box replaces
it ([web_rests_and_search.md](../features/web_rests_and_search.md)). The backend endpoint stays.

## ExportDialog

**File:** `src/components/modals/ExportDialog.tsx`

Export format selection dialog with download trigger.

### State

| State | Type | Purpose |
|-------|------|---------|
| `format` | string | Selected format (default: 'markdown') |
| `loading` | boolean | Export in progress |

### Formats

| Format | Extension | Description |
|--------|-----------|-------------|
| Markdown | .md | Formatted with `##` headers and `#tag` suffixes |
| Plain Text | .txt | Simple text, one section per snippet |
| PDF | .pdf | Printable with Helvetica font layout |

### Export Flow

```
User selects format -> Clicks "Export"
  -> POST /api/canvases/{canvasId}/export { format }
    -> Response blob received
      -> Create temporary <a> element with blob URL
        -> Trigger download with filename: "{canvasTitle}.{ext}"
          -> Revoke object URL
            -> Close dialog
```

### Store Dependencies

| Store | State/Actions Used |
|-------|-------------------|
| uiStore | `exportDialogOpen`, `setExportDialogOpen` |
| canvasStore | `canvasId`, `canvasTitle` |

### Styling

- Selected format: #2a2a5a background, #6366f1 border
- Unselected format: default dark background
