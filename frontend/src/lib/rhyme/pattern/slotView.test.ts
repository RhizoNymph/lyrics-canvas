import { describe, it, expect } from 'vitest';
import { describeSlotEditError, slotLetter, slotViews, wordActions } from './slotView';
import type { SlotEditState } from './types';
import { pslot, pspan } from './fixtures';

const state: SlotEditState = {
  lines: [
    { line: 0, wordCount: 6 },
    { line: 1, wordCount: 6 },
    { line: 2, wordCount: 0 },
  ],
  slots: [
    { id: 's0', on: true, members: [{ line: 0, start: 0, end: 1 }, { line: 1, start: 0, end: 1 }] },
    { id: 'u0', on: false, members: [{ line: 1, start: 3, end: 5 }] },
  ],
  nextUserId: 1,
};

describe('slotLetter', () => {
  it('letters slots A..', () => {
    expect([0, 1, 7].map(slotLetter)).toEqual(['A', 'B', 'H']);
  });
});

describe('slotViews', () => {
  it('derives letter, colour index, coverage over lines with words, and the last member key from the server slot', () => {
    const server = new Map([['s0', { ...pslot('s0', [[0, 0, 1], [1, 0, 1]]), members: [pspan(0, 0, 1, 'ready', ['EH', 'D']), pspan(1, 0, 1, 'steady', ['EH', 'D', 'IY'])] }]]);
    expect(slotViews(state, server)).toEqual([
      { id: 's0', index: 0, letter: 'A', on: true, coverage: 2, lineCount: 2, full: true, key: 'EH D IY' },
      { id: 'u0', index: 1, letter: 'B', on: false, coverage: 1, lineCount: 2, full: false, key: null },
    ]);
  });
});

describe('wordActions', () => {
  const textOf = (m: { line: number; start: number }) => `w${m.line}.${m.start}`;
  const labels = (line: number, word: number) => wordActions(state, line, word, textOf).map((a) => [a.label, a.disabled]);

  it('offers add to each slot, extend where the slot has a member on the line, and a new slot for a free word', () => {
    expect(labels(1, 5)).toEqual([
      ['Add to A (replaces "w1.0")', 'That would put slot A out of order on line 2.'],
      ['Add to B (replaces "w1.3")', null],
      ['Extend A to here', 'Those words already belong to slot B.'],
      ['Extend B to here', null],
      ['New slot', null],
    ]);
  });

  it('offers move, a new slot from the member, and remove for a word in a slot', () => {
    expect(labels(0, 0)).toEqual([
      ['Move to B', null],
      ['New slot from "w0.0"', null],
      ['Remove from A', null],
    ]);
  });

  it('carries the edit to apply', () => {
    const acts = wordActions(state, 0, 3, textOf);
    expect(acts.map((a) => a.label)).toEqual(['Add to A (replaces "w0.0")', 'Add to B', 'Extend A to here', 'New slot']);
    expect(acts[1].edit).toEqual({ type: 'add', slotId: 'u0', line: 0, word: 3 });
    expect(acts[3].edit).toEqual({ type: 'new_slot', line: 0, word: 3 });
  });
});

describe('describeSlotEditError', () => {
  const letter = (id: string) => (id === 's0' ? 'A' : 'B');
  it('explains every refusal', () => {
    expect(describeSlotEditError({ kind: 'order', slotId: 's0', line: 1 }, letter)).toBe('That would put slot A out of order on line 2.');
    expect(describeSlotEditError({ kind: 'overlap', line: 0, ownerId: 'u0' }, letter)).toBe('Those words already belong to slot B.');
    expect(describeSlotEditError({ kind: 'too_many_slots' }, letter)).toBe('At most 8 slots.');
    expect(describeSlotEditError({ kind: 'no_member', slotId: 's0', line: 0 }, letter)).toBe('Slot A has no word on line 1.');
    expect(describeSlotEditError({ kind: 'unknown_slot', slotId: 'x' }, letter)).toBe('That slot is gone.');
    expect(describeSlotEditError({ kind: 'same_slot', slotId: 's0' }, letter)).toBe('Already in slot A.');
    expect(describeSlotEditError({ kind: 'word_out_of_range', line: 0, word: 9 }, letter)).toBe('That word is not on the line.');
  });
});
