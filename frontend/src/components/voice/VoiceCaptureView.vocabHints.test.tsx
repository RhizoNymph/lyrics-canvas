import { describe, it, expect } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import VoiceCaptureView from './VoiceCaptureView';
import type { RecorderState } from '@/lib/voice/recorder';

const noop = () => undefined;

function render(recorder: RecorderState, enabled: boolean | null) {
  return renderToStaticMarkup(
    <VoiceCaptureView
      snapshot={{ recorder, tapPending: false, elapsedMs: 0, level: 0 }}
      target={{ kind: 'new', position: { x: 0, y: 0 } }}
      silenceSeconds={0}
      onTap={noop}
      onStop={noop}
      onCancel={noop}
      onRetry={noop}
      onDiscard={noop}
      onSaveAudio={noop}
      onSilenceChange={noop}
      vocabHints={enabled === null ? undefined : { enabled, onChange: noop }}
    />,
  );
}

const recording: RecorderState = { kind: 'recording', startedAt: 0, breaksMs: [] };

describe('VoiceCaptureView word bank hints switch', () => {
  it('shows a labelled switch reflecting the setting while recording', () => {
    const on = render(recording, true);
    expect(on).toContain('Use word bank as hints');
    expect(on).toMatch(/role="switch"[^>]*aria-checked="true"|aria-checked="true"[^>]*role="switch"/);
    const off = render(recording, false);
    expect(off).toMatch(/aria-checked="false"/);
  });

  it('is shown while waiting for the microphone too', () => {
    expect(render({ kind: 'requesting_permission' }, true)).toContain('Use word bank as hints');
  });

  it('is absent without the prop', () => {
    expect(render(recording, null)).not.toContain('Use word bank as hints');
  });
});
