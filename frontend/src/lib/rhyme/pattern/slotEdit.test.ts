import { describe, it, expect } from 'vitest';
import { applySlotEdit, initialEditState, memberAt, slotOrderError } from './slotEdit';
import type { EditSlot, Member, SlotEditState } from './types';
import { pline, pslot } from './fixtures';

const m = (line: number, start: number, end = start + 1): Member => ({ line, start, end });
const slot = (id: string, members: Member[], on = true): EditSlot => ({ id, members, on });

/** Three lines of 8 words: s0 near the start, s1 in the middle, s2 at the end. */
function base(): SlotEditState {
  return {
    lines: [
      { line: 3, wordCount: 8 },
      { line: 4, wordCount: 8 },
      { line: 5, wordCount: 8 },
    ],
    slots: [
      slot('s0', [m(3, 3), m(4, 1), m(5, 0)]),
      slot('s1', [m(3, 6), m(4, 4, 6), m(5, 5)]),
      slot('s2', [m(3, 7), m(4, 7), m(5, 7)]),
    ],
    nextUserId: 0,
  };
}

function ok(state: SlotEditState, edit: Parameters<typeof applySlotEdit>[1]): SlotEditState {
  const r = applySlotEdit(state, edit);
  if (!r.ok) throw new Error(`expected ok, got ${JSON.stringify(r.error)}`);
  expect(slotOrderError(r.state)).toBeNull();
  return r.state;
}

function err(state: SlotEditState, edit: Parameters<typeof applySlotEdit>[1]) {
  const r = applySlotEdit(state, edit);
  if (r.ok) throw new Error('expected an error');
  return r.error;
}

const ids = (s: SlotEditState) => s.slots.map((x) => x.id);
const membersOf = (s: SlotEditState, id: string) => s.slots.find((x) => x.id === id)?.members;

describe('initialEditState', () => {
  it('takes the detected slots in order with on = default_on and members sorted by line', () => {
    const lines = [pline(0, 'ready or not'), pline(1, 'steady when the pressure on')];
    const slots = [
      pslot('s0', [[1, 0, 1], [0, 0, 1]], { default_on: true }),
      pslot('s1', [[0, 2, 3], [1, 4, 5]], { default_on: false }),
    ];
    const state = initialEditState(lines, slots);
    expect(state.lines).toEqual([{ line: 0, wordCount: 3 }, { line: 1, wordCount: 5 }]);
    expect(state.slots).toEqual([
      { id: 's0', on: true, members: [m(0, 0), m(1, 0)] },
      { id: 's1', on: false, members: [m(0, 2), m(1, 4)] },
    ]);
    expect(state.nextUserId).toBe(0);
  });

  it('accepts no slots', () => {
    expect(initialEditState([pline(0, 'a b'), pline(1, 'c d')], []).slots).toEqual([]);
  });
});

describe('add', () => {
  it('adds a member on a line the slot does not cover, kept in line order', () => {
    const s0 = { ...base(), slots: [slot('s0', [m(3, 3), m(5, 0)]), ...base().slots.slice(1)] };
    const next = ok(s0, { type: 'add', slotId: 's0', line: 4, word: 2 });
    expect(membersOf(next, 's0')).toEqual([m(3, 3), m(4, 2), m(5, 0)]);
  });

  it('replaces the slot member already on that line', () => {
    const next = ok(base(), { type: 'add', slotId: 's1', line: 4, word: 3 });
    expect(membersOf(next, 's1')).toEqual([m(3, 6), m(4, 3), m(5, 5)]);
  });

  it('shrinks a multi-word member to the tapped word', () => {
    const next = ok(base(), { type: 'add', slotId: 's1', line: 4, word: 5 });
    expect(membersOf(next, 's1')).toEqual([m(3, 6), m(4, 5), m(5, 5)]);
  });

  it('refuses a word another slot holds', () => {
    expect(err(base(), { type: 'add', slotId: 's0', line: 4, word: 4 })).toEqual({ kind: 'overlap', line: 4, ownerId: 's1' });
  });

  it('refuses an edit that breaks the slot order and leaves the state alone', () => {
    const state = base();
    // s2 before s1 on line 3.
    const e = err(state, { type: 'add', slotId: 's2', line: 3, word: 5 });
    expect(e).toEqual({ kind: 'order', slotId: 's2', line: 3 });
    expect(state).toEqual(base());
  });

  it('refuses a word outside the line or a line outside the range', () => {
    expect(err(base(), { type: 'add', slotId: 's0', line: 3, word: 8 })).toEqual({ kind: 'word_out_of_range', line: 3, word: 8 });
    expect(err(base(), { type: 'add', slotId: 's0', line: 9, word: 0 })).toEqual({ kind: 'word_out_of_range', line: 9, word: 0 });
    expect(err(base(), { type: 'add', slotId: 's0', line: 3, word: -1 })).toEqual({ kind: 'word_out_of_range', line: 3, word: -1 });
  });

  it('refuses an unknown slot', () => {
    expect(err(base(), { type: 'add', slotId: 'zz', line: 3, word: 0 })).toEqual({ kind: 'unknown_slot', slotId: 'zz' });
  });
});

describe('extend', () => {
  it('grows the member to cover the word on either side', () => {
    expect(membersOf(ok(base(), { type: 'extend', slotId: 's0', line: 3, word: 1 }), 's0')?.[0]).toEqual(m(3, 1, 4));
    expect(membersOf(ok(base(), { type: 'extend', slotId: 's0', line: 3, word: 5 }), 's0')?.[0]).toEqual(m(3, 3, 6));
  });

  it('refuses growing over another slot member', () => {
    expect(err(base(), { type: 'extend', slotId: 's0', line: 3, word: 6 })).toEqual({ kind: 'overlap', line: 3, ownerId: 's1' });
  });

  it('needs a member on that line', () => {
    const state = { ...base(), slots: [slot('s0', [m(3, 3)]), ...base().slots.slice(1)] };
    expect(err(state, { type: 'extend', slotId: 's0', line: 4, word: 2 })).toEqual({ kind: 'no_member', slotId: 's0', line: 4 });
  });
});

describe('move', () => {
  it('moves the member to the other slot, replacing its member there', () => {
    const state = { ...base(), slots: [slot('s0', [m(3, 3), m(4, 1), m(5, 0)]), slot('s1', [m(3, 6), m(5, 5)]), base().slots[2]] };
    const next = ok(state, { type: 'move', fromSlotId: 's0', toSlotId: 's1', line: 4 });
    expect(membersOf(next, 's0')).toEqual([m(3, 3), m(5, 0)]);
    expect(membersOf(next, 's1')).toEqual([m(3, 6), m(4, 1), m(5, 5)]);
  });

  it('drops the source slot when it loses its last member', () => {
    const state = { ...base(), slots: [slot('s0', [m(4, 0)]), ...base().slots.slice(1)] };
    const next = ok(state, { type: 'move', fromSlotId: 's0', toSlotId: 's1', line: 4 });
    expect(ids(next)).toEqual(['s1', 's2']);
    expect(membersOf(next, 's1')).toEqual([m(3, 6), m(4, 0), m(5, 5)]);
  });

  it('refuses a move that breaks the order', () => {
    // s2's end-of-line member on line 3 into s0 (which sits before s1 there).
    expect(err(base(), { type: 'move', fromSlotId: 's2', toSlotId: 's0', line: 3 })).toEqual({ kind: 'order', slotId: 's0', line: 3 });
  });

  it('refuses a move to the same slot, a missing member and unknown slots', () => {
    expect(err(base(), { type: 'move', fromSlotId: 's0', toSlotId: 's0', line: 3 })).toEqual({ kind: 'same_slot', slotId: 's0' });
    const state = { ...base(), slots: [slot('s0', [m(3, 3)]), ...base().slots.slice(1)] };
    expect(err(state, { type: 'move', fromSlotId: 's0', toSlotId: 's1', line: 4 })).toEqual({ kind: 'no_member', slotId: 's0', line: 4 });
    expect(err(base(), { type: 'move', fromSlotId: 's0', toSlotId: 'x', line: 3 })).toEqual({ kind: 'unknown_slot', slotId: 'x' });
  });
});

describe('remove', () => {
  it('removes the member', () => {
    expect(membersOf(ok(base(), { type: 'remove', slotId: 's1', line: 4 }), 's1')).toEqual([m(3, 6), m(5, 5)]);
  });

  it('drops a slot left without members', () => {
    const state = { ...base(), slots: [slot('s0', [m(3, 3)]), ...base().slots.slice(1)] };
    expect(ids(ok(state, { type: 'remove', slotId: 's0', line: 3 }))).toEqual(['s1', 's2']);
  });

  it('needs a member on that line', () => {
    const state = { ...base(), slots: [slot('s0', [m(3, 3)]), ...base().slots.slice(1)] };
    expect(err(state, { type: 'remove', slotId: 's0', line: 5 })).toEqual({ kind: 'no_member', slotId: 's0', line: 5 });
  });
});

describe('new_slot', () => {
  it('starts a slot from a free word, placed in order, with a fresh u id', () => {
    const next = ok(base(), { type: 'new_slot', line: 4, word: 2 });
    expect(ids(next)).toEqual(['s0', 'u0', 's1', 's2']);
    expect(membersOf(next, 'u0')).toEqual([m(4, 2)]);
    expect(next.slots[1].on).toBe(true);
    expect(next.nextUserId).toBe(1);
  });

  it('places a slot first or last when the word comes before or after every member on its line', () => {
    const state = { ...base(), slots: [slot('s1', [m(3, 6), m(4, 4, 6)])] };
    expect(ids(ok(state, { type: 'new_slot', line: 3, word: 0 }))).toEqual(['u0', 's1']);
    expect(ids(ok(state, { type: 'new_slot', line: 4, word: 7 }))).toEqual(['s1', 'u0']);
  });

  it('places by relative position among slots with no member on its line', () => {
    // Neither slot touches line 5; s0 sits early, s2 late.
    const state = { ...base(), slots: [slot('s0', [m(3, 1), m(4, 1)]), slot('s2', [m(3, 7), m(4, 7)])] };
    expect(ids(ok(state, { type: 'new_slot', line: 5, word: 4 }))).toEqual(['s0', 'u0', 's2']);
    expect(ids(ok(state, { type: 'new_slot', line: 5, word: 0 }))).toEqual(['u0', 's0', 's2']);
  });

  it('takes the whole member when the word is in one, leaving its slot', () => {
    const next = ok(base(), { type: 'new_slot', line: 4, word: 5 });
    expect(membersOf(next, 's1')).toEqual([m(3, 6), m(5, 5)]);
    expect(membersOf(next, 'u0')).toEqual([m(4, 4, 6)]);
    expect(slotOrderError(next)).toBeNull();
  });

  it('never reuses an id after a drop', () => {
    let state = ok(base(), { type: 'new_slot', line: 4, word: 2 });
    state = ok(state, { type: 'drop', slotId: 'u0' });
    state = ok(state, { type: 'new_slot', line: 4, word: 2 });
    expect(ids(state)).toContain('u1');
    expect(ids(state)).not.toContain('u0');
  });

  it('refuses a ninth slot, unless the word moves a sole member out of its slot', () => {
    const lines = [{ line: 0, wordCount: 10 }, { line: 1, wordCount: 10 }];
    const slots = Array.from({ length: 8 }, (_, i) => slot(`s${i}`, i === 7 ? [m(0, i)] : [m(0, i), m(1, i)]));
    const state: SlotEditState = { lines, slots, nextUserId: 0 };
    expect(err(state, { type: 'new_slot', line: 0, word: 9 })).toEqual({ kind: 'too_many_slots' });
    const next = ok(state, { type: 'new_slot', line: 0, word: 7 });
    expect(next.slots).toHaveLength(8);
    expect(ids(next)).not.toContain('s7');
  });

  it('refuses a word outside the line', () => {
    expect(err(base(), { type: 'new_slot', line: 4, word: 99 })).toEqual({ kind: 'word_out_of_range', line: 4, word: 99 });
  });
});

describe('drop and toggle', () => {
  it('drops a slot', () => {
    expect(ids(ok(base(), { type: 'drop', slotId: 's1' }))).toEqual(['s0', 's2']);
  });

  it('toggles a slot on and off', () => {
    const off = ok(base(), { type: 'toggle', slotId: 's1' });
    expect(off.slots[1].on).toBe(false);
    expect(ok(off, { type: 'toggle', slotId: 's1' }).slots[1].on).toBe(true);
  });

  it('refuses unknown slots', () => {
    expect(err(base(), { type: 'drop', slotId: 'q' })).toEqual({ kind: 'unknown_slot', slotId: 'q' });
    expect(err(base(), { type: 'toggle', slotId: 'q' })).toEqual({ kind: 'unknown_slot', slotId: 'q' });
  });
});

describe('memberAt', () => {
  it('finds the slot holding a word', () => {
    expect(memberAt(base(), 4, 5)).toEqual({ slotId: 's1', member: m(4, 4, 6) });
    expect(memberAt(base(), 4, 2)).toBeNull();
  });
});

describe('slotOrderError', () => {
  it('reports overlapping and crossing members', () => {
    const overlap = { ...base(), slots: [slot('a', [m(3, 0, 3)]), slot('b', [m(3, 2)])] };
    expect(slotOrderError(overlap)).toEqual({ kind: 'order', slotId: 'b', line: 3 });
    const crossing = { ...base(), slots: [slot('a', [m(3, 5), m(4, 1)]), slot('b', [m(3, 6), m(4, 0)])] };
    expect(slotOrderError(crossing)).toEqual({ kind: 'order', slotId: 'b', line: 4 });
    expect(slotOrderError(base())).toBeNull();
  });
});
