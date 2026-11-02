import { useMemo } from 'react';
import type { CSSProperties } from 'react';
import { RECORD_SHORTCUT_LABEL } from '@/lib/voice/keys';
import { browserRecordingEnvironment, recordingSupport } from '@/lib/voice/support';
import type { RecordingSupport } from '@/lib/voice/support';
import { useVoiceStore } from '@/stores/voiceStore';

export type RecordButtonVariant = 'toolbar' | 'editor';

interface Props {
  variant: RecordButtonVariant;
  style: CSSProperties;
  /** Overrides the browser probe (tests). */
  support?: RecordingSupport;
}

export function recordButtonTitle(variant: RecordButtonVariant, support: RecordingSupport): string {
  if (!support.ok) return support.message;
  return variant === 'editor'
    ? `Record by voice and append to this snippet (${RECORD_SHORTCUT_LABEL})`
    : `Record by voice: a new snippet, or appended to the open editor (${RECORD_SHORTCUT_LABEL})`;
}

/**
 * Opens the voice capture modal. Disabled with an explanatory tooltip when the page cannot record
 * (no secure context / mediaDevices / MediaRecorder). The wrapper carries the tooltip because disabled
 * buttons do not get pointer events in every browser. `data-keep-editor-open` stops the inline
 * editor's click-outside save-and-close, so a take can target the open editor.
 */
export default function RecordButton({ variant, style, support: supportOverride }: Props) {
  const support = useMemo(() => supportOverride ?? recordingSupport(browserRecordingEnvironment()), [supportOverride]);
  const openRecorder = useVoiceStore((s) => s.openRecorder);
  const title = recordButtonTitle(variant, support);
  const disabled = !support.ok;

  return (
    <span title={title} data-keep-editor-open style={{ display: 'inline-flex' }}>
      <button
        type="button"
        onClick={(e) => {
          e.stopPropagation();
          openRecorder();
        }}
        onMouseDown={(e) => e.stopPropagation()}
        disabled={disabled}
        aria-label={variant === 'editor' ? 'Record and append to this snippet' : 'Record by voice'}
        aria-keyshortcuts="Alt+Shift+R"
        style={{ ...style, ...(disabled ? { opacity: 0.5, cursor: 'not-allowed' } : {}) }}
      >
        <span aria-hidden style={{ color: disabled ? 'inherit' : '#ef4444', marginRight: 4 }}>
          ●
        </span>
        {variant === 'editor' ? 'Rec' : 'Record'}
      </button>
    </span>
  );
}
