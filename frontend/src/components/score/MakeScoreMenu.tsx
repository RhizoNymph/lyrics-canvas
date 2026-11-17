/**
 * "Make score" for a snippet without one: a first score from the grid, locally (draftFromGrid) with a
 * note length, meter and tempo. The recording draft lives in feat/web-score-tools' `ScoreDraftDialog`
 * (frontend/src/components/score/ScoreDraftDialog.tsx, prop `onDraft(score)`); see DRAFT_DIALOG_MOUNT.
 */
import { useState } from 'react';
import type { GridData } from '@/types';
import { SCORE_SNAPS } from '@/types/score';
import type { Score, ScoreSnap } from '@/types/score';
import { DENS, draftFromGrid, ScoreInvalid } from '@/lib/score';

export const SCORE_DRAFT_DEFAULTS = { note: '1/8' as ScoreSnap, num: 4, den: 4, bpm: 90 };

/**
 * Mount point for the sibling branch's draft dialog. When feat/web-score-tools is merged, render
 * `<ScoreDraftDialog snippetId={...} onDraft={onDraft} onClose={...} />` here (its "From grid" path may
 * then replace the local form below).
 */
export const DRAFT_DIALOG_MOUNT = 'make-score-draft-dialog';

interface Props {
  grid: GridData | null;
  scale: number;
  onDraft: (score: Score) => void;
  onCancel: () => void;
}

export default function MakeScoreMenu({ grid, scale: s, onDraft, onCancel }: Props) {
  const [note, setNote] = useState<ScoreSnap>(SCORE_DRAFT_DEFAULTS.note);
  const [num, setNum] = useState(String(SCORE_DRAFT_DEFAULTS.num));
  const [den, setDen] = useState(String(SCORE_DRAFT_DEFAULTS.den));
  const [bpm, setBpm] = useState(String(SCORE_DRAFT_DEFAULTS.bpm));
  const [error, setError] = useState<string | null>(null);

  const make = () => {
    try {
      onDraft(draftFromGrid(grid, { num: Number(num), den: Number(den) }, Number(bpm), note));
    } catch (err) {
      if (!(err instanceof ScoreInvalid)) throw err;
      setError(
        err.reason === 'meter_invalid'
          ? 'The meter is 1-32 over 1, 2, 4, 8, 16 or 32.'
          : err.reason === 'tempo_invalid'
            ? 'The tempo is 20-400 bpm with at most two decimals.'
            : 'The grid is too long for a score.',
      );
    }
  };

  const field = {
    background: 'rgba(0,0,0,0.3)',
    color: '#fff',
    border: '1px solid rgba(255,255,255,0.25)',
    borderRadius: 3,
    fontSize: 11 * s,
    padding: `${1 * s}px ${3 * s}px`,
  } as const;

  return (
    <div
      data-keep-editor-open
      style={{
        padding: `${6 * s}px ${8 * s}px`,
        margin: `${4 * s}px 0`,
        background: 'rgba(99, 102, 241, 0.1)',
        border: '1px solid rgba(99, 102, 241, 0.35)',
        borderRadius: 4,
        fontSize: 11 * s,
        color: '#c0c0e0',
        display: 'flex',
        flexWrap: 'wrap',
        alignItems: 'center',
        gap: 8 * s,
      }}
    >
      <strong>Make score from the grid</strong>
      <label>
        each syllable{' '}
        <select value={note} style={field} onChange={(e) => setNote(e.target.value as ScoreSnap)}>
          {SCORE_SNAPS.map((snap) => (
            <option key={snap} value={snap}>
              {snap.replace('t', ' triplet')}
            </option>
          ))}
        </select>
      </label>
      <label>
        meter <input type="number" min={1} max={32} value={num} onChange={(e) => setNum(e.target.value)} style={{ ...field, width: 38 * s }} /> /{' '}
        <select value={den} style={field} onChange={(e) => setDen(e.target.value)}>
          {DENS.map((d) => (
            <option key={d} value={d}>
              {d}
            </option>
          ))}
        </select>
      </label>
      <label>
        tempo <input type="number" min={20} max={400} step={0.01} value={bpm} onChange={(e) => setBpm(e.target.value)} style={{ ...field, width: 52 * s }} /> bpm
      </label>
      <button type="button" style={{ ...field, cursor: 'pointer' }} onClick={make}>
        Make score
      </button>
      <button type="button" style={{ ...field, cursor: 'pointer' }} onClick={onCancel}>
        Cancel
      </button>
      {/* DRAFT_DIALOG_MOUNT: ScoreDraftDialog (from a recording) goes here once feat/web-score-tools is merged. */}
      <span data-mount={DRAFT_DIALOG_MOUNT} style={{ display: 'none' }} />
      {error && <span style={{ color: '#fca5a5', width: '100%' }}>{error}</span>}
    </div>
  );
}
