import { describe, it, expect } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import CheckTranscriptView from './CheckTranscriptView';
import type { CheckTranscriptViewProps } from './CheckTranscriptView';
import { referenceOf } from '@/lib/voice/recordingView';
import { VoiceError } from '@/lib/voice/types';
import type { RecordingResponse } from '@/lib/voice/types';

const noop = () => undefined;

const recording: RecordingResponse = {
  id: 'r1',
  snippet_id: 's1',
  mime_type: 'audio/webm',
  duration_ms: 65_000,
  byte_size: 10,
  transcript: 'hello world\nagain',
  split_mode: 'taps',
  created_at: '2026-10-31T18:05:00Z',
  lines: ['hello world', 'again'],
  has_timings: true,
  words: [
    { text: ' hello', start_ms: 0, end_ms: 400, probability: 0.95 },
    { text: ' world', start_ms: 450, end_ms: 900, probability: 0.3 },
    { text: ' again', start_ms: 1500, end_ms: 1900, probability: null },
  ],
  line_spans: [
    [0, 2],
    [2, 3],
  ],
  language: 'en',
  initial_prompt: null,
  verified_text: null,
  verified_at: null,
};

function render(over: Partial<CheckTranscriptViewProps> = {}) {
  const r = over.recording ?? recording;
  return renderToStaticMarkup(
    <CheckTranscriptView
      recording={r}
      checked={false}
      reference={referenceOf(r)}
      activeWord={null}
      player={{ kind: 'ready', playing: false, positionMs: 12_000, durationMs: 65_000 }}
      text={'hello world\nagain'}
      check={{ kind: 'editing' }}
      onTextChange={noop}
      onWord={noop}
      onTogglePlay={noop}
      onSeek={noop}
      onSave={noop}
      onUncheck={noop}
      onCancel={noop}
      onDiscard={noop}
      onKeepEditing={noop}
      locale="en-GB"
      {...over}
    />,
  );
}

describe('CheckTranscriptView', () => {
  it('is a modal dialog that keeps the editor open', () => {
    const html = render();
    expect(html).toContain('role="dialog"');
    expect(html).toContain('aria-modal="true"');
    expect(html).toContain('data-keep-editor-open');
    expect(html).toContain('Check transcript');
  });

  it('has a player with play, scrubber and time', () => {
    const html = render();
    expect(html).toContain('aria-label="Play"');
    expect(html).toContain('type="range"');
    expect(html).toContain('max="65000"');
    expect(html).toContain('value="12000"');
    expect(html).toContain('0:12 / 1:05');
    expect(render({ player: { kind: 'ready', playing: true, positionMs: 0, durationMs: 1000 } })).toContain('aria-label="Pause"');
    expect(render({ player: { kind: 'failed', message: 'Audio unavailable: Not found' } })).toContain('Audio unavailable');
  });

  it('shows timed words as buttons and highlights the active one', () => {
    const html = render({ activeWord: 1 });
    expect(html).toContain('data-reference="timed"');
    expect((html.match(/data-word="/g) ?? []).length).toBe(3);
    expect(html).toMatch(/data-word="1" aria-current="true"/);
    expect((html.match(/aria-current="true"/g) ?? []).length).toBe(1);
    expect(html).toContain('>world</button>');
  });

  it('shows plain lines without timings', () => {
    const plain = { ...recording, has_timings: false, words: null, line_spans: null };
    const html = render({ recording: plain, reference: referenceOf(plain) });
    expect(html).toContain('data-reference="untimed"');
    expect(html).not.toContain('data-word=');
    expect(html).toContain('>hello world</li>');
  });

  it('has the correction field with the contract hint', () => {
    const html = render();
    expect(html).toContain('<textarea');
    expect(html).toContain('hello world\nagain</textarea>');
    expect(html).toContain('Write exactly what was said: this trains transcription, not your lyrics. Editing here does not change the snippet.');
  });

  it('shows Mark unchecked only for a checked recording', () => {
    expect(render()).not.toContain('Mark unchecked');
    expect(render({ checked: true })).toContain('Mark unchecked');
    expect(render()).toContain('Save as checked');
    expect(render()).toContain('>Cancel<');
  });

  it('disables actions while saving', () => {
    const html = render({ check: { kind: 'saving' } });
    expect(html).toContain('Saving…');
    expect(html).toMatch(/disabled=""[^>]*>Saving…/);
  });

  it('shows typed errors inline', () => {
    const html = render({
      check: { kind: 'failed', action: 'save', error: { kind: 'server', error: new VoiceError('invalid_verification', 'x', 422) } },
    });
    expect(html).toContain('role="alert"');
    expect(html).toContain('The transcript cannot be saved');
    expect(render({ check: { kind: 'failed', action: 'save', error: { kind: 'local', reason: 'empty' } } })).toContain('The transcript is empty');
  });

  it('asks before discarding a dirty edit', () => {
    const html = render({ check: { kind: 'confirm_discard' } });
    expect(html).toContain('Discard your changes');
    expect(html).toContain('>Discard<');
    expect(html).toContain('>Keep editing<');
    expect(html).not.toContain('Save as checked');
  });

  it('documents the shortcuts', () => {
    expect(render()).toContain('Ctrl/Cmd+Enter save · Ctrl+Space play/pause · Alt+←/→ 3 s');
  });
});
