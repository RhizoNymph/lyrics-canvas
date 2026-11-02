import { describe, it, expect } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import VoiceCaptureView from './VoiceCaptureView';
import type { RecorderState } from '@/lib/voice/recorder';
import { CaptureError, VoiceError } from '@/lib/voice/types';
import type { Take } from '@/lib/voice/types';
import type { CaptureTarget } from '@/lib/voice/target';

const noop = () => undefined;
const take: Take = { blob: new Blob(['x']), mimeType: 'audio/webm', durationMs: 65_000, breaksMs: [] };

function render(recorder: RecorderState, extra: { tapPending?: boolean; elapsedMs?: number; level?: number; target?: CaptureTarget } = {}) {
  return renderToStaticMarkup(
    <VoiceCaptureView
      snapshot={{ recorder, tapPending: extra.tapPending ?? false, elapsedMs: extra.elapsedMs ?? 0, level: extra.level ?? 0 }}
      target={extra.target ?? { kind: 'new', position: { x: 0, y: 0 } }}
      silenceSeconds={3}
      onTap={noop}
      onStop={noop}
      onCancel={noop}
      onRetry={noop}
      onDiscard={noop}
      onSaveAudio={noop}
      onSilenceChange={noop}
    />,
  );
}

describe('VoiceCaptureView', () => {
  it('is an accessible modal dialog that keeps the editor open', () => {
    const html = render({ kind: 'requesting_permission' });
    expect(html).toContain('role="dialog"');
    expect(html).toContain('aria-modal="true"');
    expect(html).toContain('aria-labelledby="voice-capture-title"');
    expect(html).toContain('data-keep-editor-open');
    expect(html).toContain('Waiting for the microphone');
  });

  it('shows elapsed time, line number, level meter, Stop, Cancel and the silence setting while recording', () => {
    const html = render({ kind: 'recording', startedAt: 0, breaksMs: [1000, 2000] }, { elapsedMs: 75_400, level: 0.42 });
    expect(html).toContain('1:15');
    expect(html).toContain('Line 3');
    expect(html).toContain('role="meter"');
    expect(html).toContain('aria-valuenow="42"');
    expect(html).toMatch(/<button[^>]*>Stop<\/button>/);
    expect(html).not.toMatch(/<button[^>]*disabled[^>]*>Stop/);
    expect(html).toMatch(/<button[^>]*>Cancel<\/button>/);
    expect(html).toContain('Auto-stop after silence');
    expect(html).toContain('<option value="0">Off</option>');
    expect(html).toContain('<option value="3" selected="">3 s</option>');
    expect(html).toContain('<option value="10">10 s</option>');
  });

  it('counts a pending tap in the line number', () => {
    expect(render({ kind: 'recording', startedAt: 0, breaksMs: [] }, { tapPending: true })).toContain('Line 2');
  });

  it('names the append target', () => {
    expect(render({ kind: 'requesting_permission' }, { target: { kind: 'append', snippetId: 's' } })).toContain(
      'Appending to the open snippet',
    );
  });

  it('shows a spinner with Cancel while uploading; Stop is disabled', () => {
    const html = render({ kind: 'uploading', take, attempt: 2 });
    expect(html).toContain('Uploading and transcribing');
    expect(html).toContain('Attempt 2');
    expect(html).toContain('lc-voice-spin');
    expect(html).toMatch(/<button[^>]*disabled=""[^>]*>Stop<\/button>/);
    expect(html).toMatch(/>Cancel<\/button>/);
  });

  it('maps a retryable upload error to Retry, Save audio and Discard and says the take is kept', () => {
    const html = render({ kind: 'upload_failed', take, error: new VoiceError('stt_unavailable', 'model loading', 503), attempt: 1 });
    expect(html).toContain('role="alert"');
    expect(html).toContain('The transcription service is not ready');
    expect(html).toContain('model loading');
    expect(html).toContain('(1:05) is kept');
    expect(html).toMatch(/>Retry<\/button>/);
    expect(html).toMatch(/>Save audio<\/button>/);
    expect(html).toMatch(/>Discard<\/button>/);
    expect(html).not.toMatch(/>Stop<\/button>/);
  });

  it('offers no Retry for a permanent error like an empty transcript', () => {
    const html = render({ kind: 'upload_failed', take, error: new VoiceError('empty_transcript', 'x', 422), attempt: 1 });
    expect(html).toContain('No words were recognised');
    expect(html).not.toMatch(/>Retry<\/button>/);
    expect(html).toMatch(/>Discard<\/button>/);
  });

  it('shows capture errors without Save audio', () => {
    const html = render({ kind: 'capture_failed', error: new CaptureError('permission_denied', 'Permission denied') });
    expect(html).toContain('Microphone access was blocked');
    expect(html).toMatch(/>Retry<\/button>/);
    expect(html).not.toContain('Save audio');
  });
});
