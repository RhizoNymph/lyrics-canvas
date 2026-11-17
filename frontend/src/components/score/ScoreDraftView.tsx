import type { CSSProperties, KeyboardEvent as ReactKeyboardEvent, RefObject } from 'react';
import { DRAFT_ISSUE_TEXT, METER_DENS, draftSummary } from '@/lib/scoreTools/draftForm';
import type { DraftSource } from '@/lib/scoreTools/draftForm';
import type { DraftDialogEvent, DraftDialogState } from '@/lib/scoreTools/draftDialog';
import { describeScoreApiError } from '@/lib/scoreTools/errors';
import { SCORE_SNAPS } from '@/types/score';
import type { ScoreMeterDen, ScoreSnap } from '@/types/score';

export interface ScoreDraftViewProps {
  state: DraftDialogState;
  onEvent: (event: DraftDialogEvent) => void;
  onSubmit: () => void;
  /** Use the shown draft. */
  onUse: () => void;
  onClose: () => void;
  surfaceRef?: RefObject<HTMLDivElement>;
  onKeyDown?: (e: ReactKeyboardEvent<HTMLDivElement>) => void;
}

const TITLE_ID = 'score-draft-title';

const SNAP_LABELS: Readonly<Record<ScoreSnap, string>> = {
  '1/8': 'Eighths',
  '1/16': 'Sixteenths',
  '1/32': 'Thirty-seconds',
  '1/8t': 'Eighth triplets',
  '1/16t': 'Sixteenth triplets',
};

const SOURCE_LABELS: Readonly<Record<DraftSource, string>> = { grid: 'From grid', recording: 'From recording' };

const button: CSSProperties = {
  border: '1px solid #3a3a5a',
  borderRadius: 6,
  padding: '6px 14px',
  fontSize: 13,
  cursor: 'pointer',
  background: '#2a2a4a',
  color: '#e0e0e0',
};
const primary: CSSProperties = { ...button, background: '#6366f1', borderColor: '#6366f1', color: '#fff' };
const input: CSSProperties = {
  background: '#2a2a4a',
  color: '#e0e0e0',
  border: '1px solid #3a3a5a',
  borderRadius: 4,
  padding: '4px 6px',
  fontSize: 13,
};
const label: CSSProperties = { display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12, color: '#a0a0c0' };
const alert: CSSProperties = { fontSize: 13, color: '#f87171' };

/**
 * The "Make score" dialog body (store-free): source switch, the fields of that source, issues, the
 * returned draft's summary, and the actions. `ScoreDraftDialog` owns the state and effects.
 */
export default function ScoreDraftView(props: ScoreDraftViewProps) {
  const { state, onEvent } = props;
  const { form, recordings, phase } = state;
  const drafting = phase.kind === 'drafting';
  const edit = (patch: Partial<typeof form>) => onEvent({ type: 'edit', patch });

  return (
    <div
      data-keep-editor-open
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) props.onClose();
      }}
      style={{
        position: 'fixed',
        inset: 0,
        zIndex: 3000,
        background: 'rgba(10, 10, 28, 0.8)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: 16,
      }}
    >
      <div
        ref={props.surfaceRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={TITLE_ID}
        tabIndex={-1}
        onKeyDown={props.onKeyDown}
        style={{
          width: 'min(440px, 100%)',
          background: '#1c1c38',
          border: '1px solid #3a3a5a',
          borderRadius: 10,
          padding: 20,
          boxSizing: 'border-box',
          color: '#e0e0e0',
          display: 'flex',
          flexDirection: 'column',
          gap: 14,
          outline: 'none',
          fontFamily: 'system-ui, sans-serif',
        }}
      >
        <h2 id={TITLE_ID} style={{ margin: 0, fontSize: 16, fontWeight: 600 }}>
          Make score
        </h2>

        <div role="radiogroup" aria-label="Draft from" style={{ display: 'flex', gap: 6 }}>
          {(['grid', 'recording'] as const).map((source) => (
            <button
              key={source}
              type="button"
              role="radio"
              aria-checked={form.source === source}
              onClick={() => edit({ source })}
              style={{ ...(form.source === source ? primary : button), padding: '4px 10px', fontSize: 12 }}
            >
              {SOURCE_LABELS[source]}
            </button>
          ))}
        </div>

        {form.source === 'grid' ? (
          <p style={{ margin: 0, fontSize: 12, color: '#8a8aaa', lineHeight: 1.5 }}>
            Every syllable gets the same note length, each rest one beat, and each line starts on a new bar.
          </p>
        ) : (
          <p style={{ margin: 0, fontSize: 12, color: '#8a8aaa', lineHeight: 1.5 }}>
            Places each syllable where you sang it in a take (quantized to sixteenths). Leave the tempo empty to
            estimate it from the take.
          </p>
        )}

        {form.source === 'recording' && (
          <div>
            {recordings.kind === 'loading' && <div style={{ fontSize: 13, color: '#a0a0c0' }}>Loading takes…</div>}
            {recordings.kind === 'failed' && <div role="alert" style={alert}>{`Takes unavailable: ${recordings.message}`}</div>}
            {recordings.kind === 'loaded' && recordings.choices.length === 0 && (
              <div style={{ fontSize: 13, color: '#a0a0c0' }}>This snippet has no recordings yet.</div>
            )}
            {recordings.kind === 'loaded' && recordings.choices.length > 0 && (
              <label style={label}>
                Take
                <select
                  data-field="recording"
                  value={form.recordingId ?? ''}
                  disabled={drafting}
                  onChange={(e) => edit({ recordingId: e.target.value === '' ? null : e.target.value })}
                  style={input}
                >
                  {form.recordingId === null && <option value="">Pick a take</option>}
                  {recordings.choices.map((c) => (
                    <option key={c.id} value={c.id} disabled={!c.usable}>
                      {c.usable ? c.label : `${c.label} (no word timings)`}
                    </option>
                  ))}
                </select>
              </label>
            )}
          </div>
        )}

        <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'flex-end' }}>
          {form.source === 'grid' && (
            <label style={label}>
              Note length
              <select
                data-field="note"
                value={form.note}
                disabled={drafting}
                onChange={(e) => edit({ note: e.target.value as ScoreSnap })}
                style={input}
              >
                {SCORE_SNAPS.map((snap) => (
                  <option key={snap} value={snap}>
                    {`${SNAP_LABELS[snap]} (${snap})`}
                  </option>
                ))}
              </select>
            </label>
          )}
          <div style={label}>
            Meter
            <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
              <input
                data-field="meter-num"
                aria-label="Beats per bar"
                inputMode="numeric"
                value={form.meterNum}
                disabled={drafting}
                onChange={(e) => edit({ meterNum: e.target.value })}
                style={{ ...input, width: 44 }}
              />
              <span>/</span>
              <select
                data-field="meter-den"
                aria-label="Beat unit"
                value={form.meterDen}
                disabled={drafting}
                onChange={(e) => edit({ meterDen: Number(e.target.value) as ScoreMeterDen })}
                style={input}
              >
                {METER_DENS.map((den) => (
                  <option key={den} value={den}>
                    {den}
                  </option>
                ))}
              </select>
            </div>
          </div>
          <label style={label}>
            Tempo (bpm)
            <input
              data-field="bpm"
              inputMode="decimal"
              value={form.bpm}
              placeholder={form.source === 'recording' ? 'estimate' : undefined}
              disabled={drafting}
              onChange={(e) => edit({ bpm: e.target.value })}
              style={{ ...input, width: 80 }}
            />
          </label>
        </div>

        {phase.kind === 'editing' && phase.issues.length > 0 && (
          <div role="alert" style={alert}>
            {phase.issues.map((issue) => (
              <div key={issue}>{DRAFT_ISSUE_TEXT[issue]}</div>
            ))}
          </div>
        )}

        {phase.kind === 'failed' && (
          <div role="alert" style={alert}>
            {describeScoreApiError(phase.error).title}
            {describeScoreApiError(phase.error).hint !== null && (
              <div style={{ fontSize: 12, color: '#c0c0d8' }}>{describeScoreApiError(phase.error).hint}</div>
            )}
          </div>
        )}

        {phase.kind === 'drafted' && (
          <div data-draft-summary role="status" style={{ fontSize: 13, color: '#c0c0d8', lineHeight: 1.5 }}>
            {draftSummary(phase.draft).map((line) => (
              <div key={line}>{line}</div>
            ))}
          </div>
        )}

        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
          <button type="button" style={button} onClick={props.onClose}>
            Cancel
          </button>
          {phase.kind === 'drafted' ? (
            <>
              <button type="button" style={button} onClick={props.onSubmit}>
                Draft again
              </button>
              <button type="button" style={primary} onClick={props.onUse}>
                Use score
              </button>
            </>
          ) : (
            <button type="button" style={primary} disabled={drafting} onClick={props.onSubmit}>
              {drafting ? 'Drafting…' : 'Draft'}
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
