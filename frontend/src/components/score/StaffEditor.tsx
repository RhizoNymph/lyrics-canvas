/**
 * The staff editor of musical mode (contract §7): bars wrapped into rows, a one-line staff with beat
 * ticks, notes (head, length bar, accent, syllable), grid line boundaries, a tray of unplaced syllables
 * per line, drag from the tray or move a note (snapped), drag a note's right edge for its length,
 * keyboard nudge / accent / delete, meter and tempo changes at a bar, pickup, snap and playback.
 *
 * Every change goes through the pure operations of lib/score/edit.ts and is handed to `onCommit` as a
 * whole new valid score; the editor (InlineGridEditor) records history and applies the rest link.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { DragEvent as ReactDragEvent, KeyboardEvent as ReactKeyboardEvent, PointerEvent as ReactPointerEvent } from 'react';
import type { GridData } from '@/types';
import { SCORE_SNAPS, SCORE_SNAP_TICKS } from '@/types/score';
import type { Score, ScoreSnap } from '@/types/score';
import {
  beatUnit,
  cycleAccent,
  findNote,
  layoutStaff,
  lineStarts,
  meterBarLength,
  moveNote,
  noteSegments,
  noteWindow,
  placeNote,
  resizeNote,
  rowAtTick,
  setPickup,
  setSnap,
  snapTick,
  syllableTexts,
  unplaceNote,
  unplacedSyllables,
} from '@/lib/score';
import type { EditRefusal, EditResult } from '@/lib/score';
import StaffRowView from './StaffRowView';
import type { NoteGrab, NoteRef } from './StaffRowView';
import ScoreTray, { SYLLABLE_DRAG_TYPE, parseSyllableDrag } from './ScoreTray';
import BarChangeEditor from './BarChangeEditor';
import { DEFAULT_NOTE_TICKS, EDITOR_STAFF } from './staffGeometry';
import type { Playback } from './usePlayback';

interface Props {
  score: Score;
  grid: GridData | null;
  scale: number;
  playback: Playback;
  onCommit: (score: Score) => void;
  onRemoveScore: () => void;
}

const REFUSAL_TEXT: Record<EditRefusal, string> = {
  no_syllable: 'That syllable is not in the grid any more.',
  not_placed: 'That syllable is not placed.',
  no_room: 'No room: the neighbouring syllables leave no gap here.',
  first_bar_required: 'Bar 1 always has a meter and a tempo.',
  bar_out_of_range: 'Changes can sit at bars 1 to 2049.',
  too_many_changes: 'At most 256 meter and 256 tempo changes.',
  invalid_meter: 'A meter is 1-32 over 1, 2, 4, 8, 16 or 32.',
  invalid_tempo: 'A tempo is 20-400 bpm with at most two decimals.',
  invalid_pickup: 'The pickup must be shorter than the first bar.',
};

interface DragState {
  readonly note: NoteRef;
  readonly grab: NoteGrab;
  readonly startX: number;
  readonly origT: number;
  readonly origD: number;
}

function pickupOptions(score: Score): number[] {
  const step = SCORE_SNAP_TICKS[score.snap];
  const first = meterBarLength(score.meters[0]);
  const out: number[] = [];
  for (let t = 0; t < first; t += step) out.push(t);
  if (!out.includes(score.pickup)) out.push(score.pickup);
  return out.sort((a, b) => a - b);
}

/** A pickup length in quarter notes, e.g. "1/2 ♩", "1 ♩". */
function pickupLabel(ticks: number): string {
  if (ticks === 0) return 'none';
  const quarters = ticks / 48;
  const whole = Math.floor(quarters);
  const frac = quarters - whole;
  const fracText = frac === 0 ? '' : frac === 0.5 ? '½' : frac === 0.25 ? '¼' : frac === 0.75 ? '¾' : `+${Math.round(frac * 48)}t`;
  return `${whole > 0 ? whole : ''}${fracText || (whole > 0 ? '' : '0')} ♩`;
}

export default function StaffEditor({ score, grid, scale: s, playback, onCommit, onRemoveScore }: Props) {
  const [selected, setSelected] = useState<NoteRef | null>(null);
  const [armed, setArmed] = useState<NoteRef | null>(null);
  const [preview, setPreview] = useState<Score | null>(null);
  const [barEditor, setBarEditor] = useState<number | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const rootRef = useRef<HTMLDivElement>(null);
  const drag = useRef<DragState | null>(null);
  const previewRef = useRef<Score | null>(null);
  const showPreview = (next: Score | null) => {
    previewRef.current = next;
    setPreview(next);
  };

  const shown = preview ?? score;
  const snapTicks = SCORE_SNAP_TICKS[score.snap];
  const labels = useMemo(() => syllableTexts(grid), [grid]);
  const layout = useMemo(() => layoutStaff(shown, { width: EDITOR_STAFF.rowWidth, pxPerTick: EDITOR_STAFF.pxPerTick }), [shown]);
  const segments = useMemo(() => noteSegments(shown, layout), [shown, layout]);
  const marksByRow = useMemo(() => {
    const out = layout.rows.map(() => [] as { line: number; tick: number }[]);
    for (const mark of lineStarts(shown)) out[rowAtTick(layout, mark.tick)].push(mark);
    return out;
  }, [shown, layout]);
  const tray = useMemo(() => unplacedSyllables(score, grid), [score, grid]);
  const playheadRow = playback.playheadTick === null ? -1 : rowAtTick(layout, playback.playheadTick);

  // The latest values for the stable handlers below.
  const latest = useRef({ score, grid, snapTicks, armed, onCommit });
  latest.current = { score, grid, snapTicks, armed, onCommit };

  useEffect(() => {
    if (message === null) return;
    const id = setTimeout(() => setMessage(null), 3500);
    return () => clearTimeout(id);
  }, [message]);

  const apply = useCallback((result: EditResult): boolean => {
    if (!result.ok) {
      setMessage(REFUSAL_TEXT[result.reason]);
      return false;
    }
    if (result.score !== latest.current.score) latest.current.onCommit(result.score);
    return true;
  }, []);

  const placeAt = useCallback(
    (note: NoteRef, tick: number) => {
      const { score: sc, grid: gr, snapTicks: snap } = latest.current;
      const t = snapTick(sc, tick, snap);
      if (apply(placeNote(sc, gr, note.line, note.s, t, Math.max(DEFAULT_NOTE_TICKS, snap)))) {
        setSelected(note);
        setArmed(null);
      }
    },
    [apply],
  );

  const onPointerMove = useCallback((e: PointerEvent) => {
    const d = drag.current;
    if (!d) return;
    const { score: sc, grid: gr, snapTicks: snap } = latest.current;
    const dx = (e.clientX - d.startX) / (EDITOR_STAFF.pxPerTick * s);
    let result: EditResult;
    if (d.grab === 'move') {
      result = moveNote(sc, gr, d.note.line, d.note.s, snapTick(sc, d.origT + dx, snap));
    } else {
      const end = snapTick(sc, d.origT + d.origD + dx, snap);
      result = resizeNote(sc, gr, d.note.line, d.note.s, end - d.origT > 0 ? end - d.origT : snap);
    }
    if (result.ok) showPreview(result.score);
  }, [s]);

  const onPointerUp = useCallback(() => {
    window.removeEventListener('pointermove', onPointerMove);
    window.removeEventListener('pointerup', onPointerUp);
    drag.current = null;
    const p = previewRef.current;
    showPreview(null);
    if (p !== null && p !== latest.current.score) latest.current.onCommit(p);
  }, [onPointerMove]);

  useEffect(
    () => () => {
      window.removeEventListener('pointermove', onPointerMove);
      window.removeEventListener('pointerup', onPointerUp);
    },
    [onPointerMove, onPointerUp],
  );

  const interactive = useMemo(
    () => ({
      onNotePointerDown: (e: ReactPointerEvent<SVGElement>, note: NoteRef, grab: NoteGrab) => {
        e.stopPropagation();
        e.preventDefault();
        const current = findNote(latest.current.score, note.line, note.s);
        if (!current) return;
        setSelected(note);
        setArmed(null);
        rootRef.current?.focus();
        drag.current = { note, grab, startX: e.clientX, origT: current.t, origD: current.d };
        window.addEventListener('pointermove', onPointerMove);
        window.addEventListener('pointerup', onPointerUp);
      },
      onBarClick: (bar: number) => setBarEditor(bar),
      onStaffClick: (tick: number) => {
        const target = latest.current.armed;
        if (target) placeAt(target, tick);
        else setSelected(null);
        rootRef.current?.focus();
      },
      onDropSyllable: (tick: number, e: ReactDragEvent<SVGSVGElement>) => {
        const note = parseSyllableDrag(e.dataTransfer.getData(SYLLABLE_DRAG_TYPE));
        if (note) placeAt(note, tick);
      },
    }),
    [onPointerMove, onPointerUp, placeAt],
  );

  const selectNeighbour = (direction: 1 | -1) => {
    const all = score.lines.flatMap((line, li) => line.notes.map((n) => ({ line: li, s: n.s })));
    if (all.length === 0) return;
    const at = selected ? all.findIndex((n) => n.line === selected.line && n.s === selected.s) : -1;
    const next = at < 0 ? (direction === 1 ? 0 : all.length - 1) : Math.min(Math.max(at + direction, 0), all.length - 1);
    setSelected(all[next]);
  };

  const onKeyDown = (e: ReactKeyboardEvent) => {
    const tag = e.target instanceof HTMLElement ? e.target.tagName : '';
    if (tag === 'INPUT' || tag === 'SELECT' || tag === 'TEXTAREA') return;
    const handled = () => {
      e.preventDefault();
      e.stopPropagation();
    };
    if (e.key === ' ' && !e.ctrlKey && !e.metaKey) {
      handled();
      if (playback.playing) playback.stop();
      else playback.play(selected ? (findNote(score, selected.line, selected.s)?.t ?? 0) : 0);
      return;
    }
    if (e.key === 'Tab') {
      handled();
      selectNeighbour(e.shiftKey ? -1 : 1);
      return;
    }
    if (armed && e.key === 'Enter') {
      handled();
      const { lo } = noteWindow(score, armed.line, armed.s);
      const t = Math.ceil(lo / snapTicks) * snapTicks;
      placeAt(armed, t);
      return;
    }
    if (e.key === 'Escape') {
      if (selected || armed || barEditor !== null) {
        handled();
        setSelected(null);
        setArmed(null);
        setBarEditor(null);
      }
      return;
    }
    if (!selected) return;
    const note = findNote(score, selected.line, selected.s);
    if (!note) return;
    const step = e.shiftKey ? beatUnit(score, note.t) : snapTicks;
    switch (e.key) {
      case 'ArrowLeft':
      case 'ArrowRight': {
        handled();
        const dir = e.key === 'ArrowRight' ? 1 : -1;
        if (e.altKey) apply(resizeNote(score, grid, selected.line, selected.s, Math.max(1, note.d + dir * step)));
        else apply(moveNote(score, grid, selected.line, selected.s, note.t + dir * step));
        break;
      }
      case 'a':
      case 'A':
        handled();
        apply(cycleAccent(score, grid, selected.line, selected.s));
        break;
      case 'Delete':
      case 'Backspace':
        handled();
        if (apply(unplaceNote(score, grid, selected.line, selected.s))) setSelected(null);
        break;
    }
  };

  const playFrom = () => (selected ? (findNote(score, selected.line, selected.s)?.t ?? 0) : 0);
  const button = {
    background: 'rgba(0,0,0,0.3)',
    color: '#fff',
    border: '1px solid rgba(255,255,255,0.25)',
    borderRadius: 4,
    padding: `${1 * s}px ${6 * s}px`,
    fontSize: 11 * s,
    cursor: 'pointer',
  } as const;
  const selectStyle = { ...button, padding: `${1 * s}px ${2 * s}px` };

  const selectedNote = selected ? findNote(score, selected.line, selected.s) : null;

  return (
    <div ref={rootRef} tabIndex={0} onKeyDown={onKeyDown} style={{ outline: 'none' }} data-testid="staff-editor">
      <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 6 * s, marginBottom: 6 * s, fontSize: 11 * s, color: '#a0a0c0' }}>
        <button type="button" style={button} onClick={() => (playback.playing ? playback.stop() : playback.play(playFrom()))}>
          {playback.playing ? '■ Stop' : '▶ Play'}
        </button>
        <label>
          Snap{' '}
          <select
            value={score.snap}
            style={selectStyle}
            onChange={(e) => onCommit(setSnap(score, e.target.value as ScoreSnap))}
          >
            {SCORE_SNAPS.map((snap) => (
              <option key={snap} value={snap}>
                {snap.replace('t', ' triplet')}
              </option>
            ))}
          </select>
        </label>
        <label>
          Pickup{' '}
          <select value={score.pickup} style={selectStyle} onChange={(e) => apply(setPickup(score, grid, Number(e.target.value)))}>
            {pickupOptions(score).map((t) => (
              <option key={t} value={t}>
                {pickupLabel(t)}
              </option>
            ))}
          </select>
        </label>
        <button type="button" style={button} onClick={() => setBarEditor(barEditor === null ? 0 : null)} title="Meter and tempo changes (or click a bar number)">
          Meter / tempo…
        </button>
        <span style={{ flex: 1 }} />
        <button
          type="button"
          style={{ ...button, borderColor: 'rgba(248,113,113,0.5)' }}
          onClick={() => {
            if (window.confirm('Remove the score? The grid and text stay as they are.')) onRemoveScore();
          }}
        >
          Remove score
        </button>
      </div>

      {barEditor !== null && (
        <BarChangeEditor score={score} grid={grid} bar={barEditor} scale={s} onBar={setBarEditor} onResult={apply} onClose={() => setBarEditor(null)} />
      )}

      <div style={{ overflowX: 'auto' }}>
        {layout.rows.map((row, r) => (
          <div key={r} style={{ background: r % 2 === 0 ? 'rgba(36, 36, 72, 0.35)' : 'transparent', padding: `0 ${4 * s}px` }}>
            <StaffRowView
              row={row}
              geometry={EDITOR_STAFF}
              scale={s}
              segments={segments[r]}
              labels={labels}
              lineMarks={marksByRow[r]}
              selected={selected && segments[r].some((g) => g.line === selected.line && g.s === selected.s) ? selected : null}
              playhead={r === playheadRow ? playback.playheadTick : null}
              interactive={interactive}
            />
          </div>
        ))}
      </div>

      <ScoreTray tray={tray} labels={labels} armed={armed} scale={s} onArm={(note) => setArmed((a) => (a && a.line === note.line && a.s === note.s ? null : note))} />

      <div style={{ paddingTop: 4 * s, fontSize: 9 * s, fontFamily: 'monospace', color: message ? '#fca5a5' : '#4a4a6a', minHeight: 12 * s }}>
        {message ??
          (selectedNote
            ? `${labels[selected!.line]?.[selected!.s] ?? ''}: tick ${selectedNote.t}, ${selectedNote.d} long · ←/→ nudge (shift: a beat) · alt+←/→ length · a accent · del to tray · tab next · space play`
            : armed
              ? 'click the staff to place it · enter: right after the previous syllable · esc'
              : 'drag a syllable from the tray onto the staff (or click it, then the staff) · drag a note to move, its right edge for length · click a bar number for meter/tempo · space play')}
      </div>
    </div>
  );
}

