import { describe, it, expect } from 'vitest';
import type { Score } from '@/types/score';
import type { GridJson } from './gridView';
import {
  cycleAccent,
  moveNote,
  noteWindow,
  placeNote,
  resizeNote,
  setMeterAt,
  setPickup,
  setSnap,
  setTempoAt,
  snapTick,
  unplaceNote,
  unplacedSyllables,
} from './edit';

const cell = (syllable: string, word_index: number) => ({ syllable, stress: 0, word_index, word: syllable });
const grid: GridJson = {
  lines: [
    { cells: [cell('a', 0), cell('b', 1), cell('c', 2)], raw_text: 'a b c' },
    { cells: [cell('d', 0), { rest: true }, cell('e', 1)], raw_text: 'd _ e' },
  ],
};

function score(lines: Score['lines'], extra: Partial<Score> = {}): Score {
  return { v: 1, ppq: 48, pickup: 0, meters: [{ bar: 0, num: 4, den: 4 }], tempos: [{ bar: 0, bpm: 90 }], snap: '1/16', lines, ...extra };
}

const two = score([{ notes: [{ s: 0, t: 0, d: 24, a: 0 }, { s: 2, t: 96, d: 24, a: 1 }] }, { notes: [{ s: 1, t: 240, d: 12, a: 0 }] }]);

function ok(result: ReturnType<typeof placeNote>): Score {
  if (!result.ok) throw new Error(`refused: ${result.reason}`);
  return result.score;
}

describe('noteWindow', () => {
  it('spans from the previous note end to the next note start, across lines', () => {
    expect(noteWindow(two, 0, 1)).toEqual({ lo: 24, hi: 96 });
    expect(noteWindow(two, 1, 0)).toEqual({ lo: 120, hi: 240 });
    expect(noteWindow(two, 0, 0)).toEqual({ lo: 0, hi: 96 });
    expect(noteWindow(two, 1, 1)).toEqual({ lo: 120, hi: 393216 });
  });
});

describe('placeNote', () => {
  it('inserts a note for an unplaced syllable in syllable order', () => {
    const out = ok(placeNote(two, grid, 0, 1, 48, 24));
    expect(out.lines[0].notes.map((n) => [n.s, n.t, n.d])).toEqual([
      [0, 0, 24],
      [1, 48, 24],
      [2, 96, 24],
    ]);
  });

  it('clamps into the free window and shortens a note that does not fit', () => {
    const early = ok(placeNote(two, grid, 0, 1, 0, 24));
    expect(early.lines[0].notes[1]).toMatchObject({ s: 1, t: 24, d: 24 });
    const long = ok(placeNote(two, grid, 0, 1, 30, 200));
    expect(long.lines[0].notes[1]).toMatchObject({ s: 1, t: 24, d: 72 });
  });

  it('refuses when there is no room between the neighbours', () => {
    const tight = score([{ notes: [{ s: 0, t: 0, d: 24, a: 0 }, { s: 2, t: 24, d: 24, a: 0 }] }, { notes: [] }]);
    expect(placeNote(tight, grid, 0, 1, 24, 12)).toEqual({ ok: false, reason: 'no_room' });
  });

  it('refuses a syllable that does not exist', () => {
    expect(placeNote(two, grid, 0, 7, 0, 12)).toEqual({ ok: false, reason: 'no_syllable' });
    expect(placeNote(two, grid, 5, 0, 0, 12)).toEqual({ ok: false, reason: 'no_syllable' });
  });

  it('gives a new note the default accent from the grid stress', () => {
    const stressed: GridJson = { lines: [{ cells: [{ syllable: 'x', stress: 1, word_index: 0 }] }] };
    const out = ok(placeNote(score([{ notes: [] }]), stressed, 0, 0, 0, 12));
    expect(out.lines[0].notes[0].a).toBe(1);
  });
});

describe('moveNote / resizeNote', () => {
  it('moves within the window, keeping duration, accent and extra keys', () => {
    const withExtra = score([{ notes: [{ s: 0, t: 0, d: 24, a: 2, p: 60 }] }, { notes: [] }]);
    const out = ok(moveNote(withExtra, grid, 0, 0, 36));
    expect(out.lines[0].notes[0]).toEqual({ s: 0, t: 36, d: 24, a: 2, p: 60 });
    expect(ok(moveNote(two, grid, 0, 0, 500)).lines[0].notes[0].t).toBe(72);
    expect(ok(moveNote(two, grid, 0, 2, -40)).lines[0].notes[1].t).toBe(24);
  });

  it('resizes between 1 and the next start', () => {
    expect(ok(resizeNote(two, grid, 0, 0, 0)).lines[0].notes[0].d).toBe(1);
    expect(ok(resizeNote(two, grid, 0, 0, 1000)).lines[0].notes[0].d).toBe(96);
  });

  it('refuses an unplaced syllable', () => {
    expect(moveNote(two, grid, 0, 1, 10)).toEqual({ ok: false, reason: 'not_placed' });
    expect(resizeNote(two, grid, 0, 1, 10)).toEqual({ ok: false, reason: 'not_placed' });
  });
});

describe('accent and unplace', () => {
  it('cycles 0 -> 1 -> 2 -> 0', () => {
    const a1 = ok(cycleAccent(two, grid, 0, 0));
    const a2 = ok(cycleAccent(a1, grid, 0, 0));
    const a0 = ok(cycleAccent(a2, grid, 0, 0));
    expect([a1, a2, a0].map((s) => s.lines[0].notes[0].a)).toEqual([1, 2, 0]);
  });

  it('unplaces a note back to the tray', () => {
    const out = ok(unplaceNote(two, grid, 0, 2));
    expect(out.lines[0].notes.map((n) => n.s)).toEqual([0]);
    expect(unplaceNote(out, grid, 0, 2)).toEqual({ ok: false, reason: 'not_placed' });
  });
});

describe('meter, tempo, pickup, snap', () => {
  it('adds, replaces and removes a meter change at a bar', () => {
    const added = ok(setMeterAt(two, grid, 2, { num: 3, den: 4 }));
    expect(added.meters).toEqual([
      { bar: 0, num: 4, den: 4 },
      { bar: 2, num: 3, den: 4 },
    ]);
    const replaced = ok(setMeterAt(added, grid, 2, { num: 6, den: 8 }));
    expect(replaced.meters[1]).toEqual({ bar: 2, num: 6, den: 8 });
    expect(ok(setMeterAt(replaced, grid, 2, null)).meters).toEqual([{ bar: 0, num: 4, den: 4 }]);
    expect(ok(setMeterAt(two, grid, 0, { num: 7, den: 8 })).meters).toEqual([{ bar: 0, num: 7, den: 8 }]);
  });

  it('refuses removing bar 0, bad meters and bars past 2048', () => {
    expect(setMeterAt(two, grid, 0, null)).toEqual({ ok: false, reason: 'first_bar_required' });
    expect(setMeterAt(two, grid, 1, { num: 0, den: 4 })).toEqual({ ok: false, reason: 'invalid_meter' });
    expect(setMeterAt(two, grid, 1, { num: 4, den: 3 })).toEqual({ ok: false, reason: 'invalid_meter' });
    expect(setMeterAt(two, grid, 2049, { num: 4, den: 4 })).toEqual({ ok: false, reason: 'bar_out_of_range' });
  });

  it('drops the pickup when a new first meter makes it too long', () => {
    const picked = score([{ notes: [] }, { notes: [] }], { pickup: 120 });
    expect(ok(setMeterAt(picked, grid, 0, { num: 2, den: 4 })).pickup).toBe(0);
    expect(ok(setMeterAt(picked, grid, 0, { num: 3, den: 4 })).pickup).toBe(120);
  });

  it('sets and removes tempo changes', () => {
    const t = ok(setTempoAt(two, grid, 1, 120.5));
    expect(t.tempos).toEqual([
      { bar: 0, bpm: 90 },
      { bar: 1, bpm: 120.5 },
    ]);
    expect(ok(setTempoAt(t, grid, 1, null)).tempos).toEqual([{ bar: 0, bpm: 90 }]);
    expect(setTempoAt(two, grid, 1, 10)).toEqual({ ok: false, reason: 'invalid_tempo' });
    expect(setTempoAt(two, grid, 1, 90.123)).toEqual({ ok: false, reason: 'invalid_tempo' });
    expect(setTempoAt(two, grid, 0, null)).toEqual({ ok: false, reason: 'first_bar_required' });
  });

  it('sets a pickup inside the first bar, keeping note ticks', () => {
    const p = ok(setPickup(two, grid, 48));
    expect(p.pickup).toBe(48);
    expect(p.lines).toEqual(two.lines);
    expect(setPickup(two, grid, 192)).toEqual({ ok: false, reason: 'invalid_pickup' });
    expect(setPickup(two, grid, -1)).toEqual({ ok: false, reason: 'invalid_pickup' });
  });

  it('remembers the snap choice', () => {
    expect(setSnap(two, '1/8t').snap).toBe('1/8t');
  });
});

describe('snapTick', () => {
  it('snaps to the grid of the bar holding the tick', () => {
    expect(snapTick(two, 17, 12)).toBe(12);
    expect(snapTick(two, 19, 12)).toBe(24);
    expect(snapTick(two, -5, 12)).toBe(0);
  });

  it('snaps relative to bar starts after a pickup and a meter change', () => {
    const odd = score([{ notes: [] }, { notes: [] }], { pickup: 20, meters: [{ bar: 0, num: 5, den: 8 }] });
    // Bar 0 starts at 20; bar 1 at 140.
    expect(snapTick(odd, 33, 12)).toBe(32);
    expect(snapTick(odd, 145, 24)).toBe(140);
    // In the pickup the grid counts back from bar 0.
    expect(snapTick(odd, 9, 12)).toBe(8);
  });
});

describe('unplacedSyllables', () => {
  it('lists the syllables of each line without a note', () => {
    expect(unplacedSyllables(two, grid)).toEqual([[1], [0]]);
  });
});
