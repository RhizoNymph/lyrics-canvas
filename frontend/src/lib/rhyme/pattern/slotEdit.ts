/**
 * The slot-edit reducer of the pattern view (contract §2.2 "Editing slots"). Pure: every edit returns a new
 * state that keeps the §1.2 invariants (at most one member per line per slot, no overlapping members on a
 * line, the same slot order on every line), or a typed refusal and no change.
 */
import { PATTERN_MAX_SLOTS } from '@/types/rhymePattern';
import type { PatternLine, PatternSlot } from '@/types/rhymePattern';
import type { EditSlot, LineShape, Member, SlotEdit, SlotEditError, SlotEditResult, SlotEditState } from './types';

/** The edit state for a fresh detection: detected slots in order, `on` from `default_on`. */
export function initialEditState(lines: readonly PatternLine[], detected: readonly PatternSlot[]): SlotEditState {
  return {
    lines: lines.map((l) => ({ line: l.line_index, wordCount: l.words.length })),
    slots: detected.map((s) => ({
      id: s.id,
      on: s.default_on,
      members: sortMembers(s.members.map((p) => ({ line: p.line_index, start: p.word_start, end: p.word_end }))),
    })),
    nextUserId: 0,
  };
}

function sortMembers(members: readonly Member[]): Member[] {
  return [...members].sort((a, b) => a.line - b.line);
}

const overlaps = (a: Member, start: number, end: number) => a.start < end && start < a.end;

/** The slot and member holding word `word` of `line`, if any. */
export function memberAt(state: SlotEditState, line: number, word: number): { slotId: string; member: Member } | null {
  for (const slot of state.slots) {
    const member = slot.members.find((x) => x.line === line && word >= x.start && word < x.end);
    if (member !== undefined) return { slotId: slot.id, member };
  }
  return null;
}

/** The first line (in line order) where members are out of slot order or overlap, as an `order` error. */
export function slotOrderError(state: SlotEditState): SlotEditError | null {
  for (const { line } of state.lines) {
    let prevEnd = -1;
    for (const slot of state.slots) {
      const member = slot.members.find((x) => x.line === line);
      if (member === undefined) continue;
      if (member.start < prevEnd) return { kind: 'order', slotId: slot.id, line };
      prevEnd = member.end;
    }
  }
  return null;
}

function lineShape(state: SlotEditState, line: number): LineShape | undefined {
  return state.lines.find((l) => l.line === line);
}

function wordFits(state: SlotEditState, line: number, word: number): boolean {
  const shape = lineShape(state, line);
  return shape !== undefined && Number.isInteger(word) && word >= 0 && word < shape.wordCount;
}

/** Replace (or drop, with `null`) a slot's member on a line; a slot left without members is removed. */
function setMember(slots: readonly EditSlot[], slotId: string, line: number, member: Member | null): EditSlot[] {
  const out: EditSlot[] = [];
  for (const slot of slots) {
    if (slot.id !== slotId) {
      out.push(slot);
      continue;
    }
    const rest = slot.members.filter((x) => x.line !== line);
    const members = member === null ? rest : sortMembers([...rest, member]);
    if (members.length > 0) out.push({ ...slot, members });
  }
  return out;
}

function ownerOther(state: SlotEditState, slotId: string, line: number, start: number, end: number): string | null {
  for (const slot of state.slots) {
    if (slot.id === slotId) continue;
    if (slot.members.some((x) => x.line === line && overlaps(x, start, end))) return slot.id;
  }
  return null;
}

const fail = (error: SlotEditError): SlotEditResult => ({ ok: false, error });

/** Accept the new slot list when it keeps the order on every line; else refuse naming the edited slot. */
function commit(state: SlotEditState, slots: EditSlot[], slotId: string, line: number, nextUserId = state.nextUserId): SlotEditResult {
  const next: SlotEditState = { ...state, slots, nextUserId };
  if (slotOrderError(next) !== null) return fail({ kind: 'order', slotId, line });
  return { ok: true, state: next };
}

function relativePosition(state: SlotEditState, member: Member): number {
  const count = lineShape(state, member.line)?.wordCount ?? 1;
  return member.start / Math.max(1, count);
}

function meanRelative(state: SlotEditState, slot: EditSlot): number {
  const sum = slot.members.reduce((acc, x) => acc + relativePosition(state, x), 0);
  return sum / slot.members.length;
}

/**
 * Where a one-member slot goes: after every slot whose member on its line ends before it, before every
 * slot whose member there starts after it, and among the slots with no member on its line by mean relative
 * position (start word / line word count).
 */
function insertionIndex(state: SlotEditState, slots: readonly EditSlot[], member: Member): number {
  let lo = 0;
  let hi = slots.length;
  slots.forEach((slot, i) => {
    const there = slot.members.find((x) => x.line === member.line);
    if (there === undefined) return;
    if (there.end <= member.start) lo = Math.max(lo, i + 1);
    else if (hi === slots.length) hi = i;
  });
  const rel = relativePosition(state, member);
  let p = lo;
  while (p < hi && meanRelative(state, slots[p]) <= rel) p++;
  return p;
}

export function applySlotEdit(state: SlotEditState, edit: SlotEdit): SlotEditResult {
  const find = (id: string) => state.slots.find((s) => s.id === id);
  switch (edit.type) {
    case 'add': {
      if (find(edit.slotId) === undefined) return fail({ kind: 'unknown_slot', slotId: edit.slotId });
      if (!wordFits(state, edit.line, edit.word)) return fail({ kind: 'word_out_of_range', line: edit.line, word: edit.word });
      const owner = ownerOther(state, edit.slotId, edit.line, edit.word, edit.word + 1);
      if (owner !== null) return fail({ kind: 'overlap', line: edit.line, ownerId: owner });
      const member = { line: edit.line, start: edit.word, end: edit.word + 1 };
      return commit(state, setMember(state.slots, edit.slotId, edit.line, member), edit.slotId, edit.line);
    }
    case 'extend': {
      const slot = find(edit.slotId);
      if (slot === undefined) return fail({ kind: 'unknown_slot', slotId: edit.slotId });
      if (!wordFits(state, edit.line, edit.word)) return fail({ kind: 'word_out_of_range', line: edit.line, word: edit.word });
      const current = slot.members.find((x) => x.line === edit.line);
      if (current === undefined) return fail({ kind: 'no_member', slotId: edit.slotId, line: edit.line });
      const start = Math.min(current.start, edit.word);
      const end = Math.max(current.end, edit.word + 1);
      const owner = ownerOther(state, edit.slotId, edit.line, start, end);
      if (owner !== null) return fail({ kind: 'overlap', line: edit.line, ownerId: owner });
      return commit(state, setMember(state.slots, edit.slotId, edit.line, { line: edit.line, start, end }), edit.slotId, edit.line);
    }
    case 'move': {
      const from = find(edit.fromSlotId);
      if (from === undefined) return fail({ kind: 'unknown_slot', slotId: edit.fromSlotId });
      if (find(edit.toSlotId) === undefined) return fail({ kind: 'unknown_slot', slotId: edit.toSlotId });
      if (edit.fromSlotId === edit.toSlotId) return fail({ kind: 'same_slot', slotId: edit.toSlotId });
      const member = from.members.find((x) => x.line === edit.line);
      if (member === undefined) return fail({ kind: 'no_member', slotId: edit.fromSlotId, line: edit.line });
      const without = setMember(state.slots, edit.fromSlotId, edit.line, null);
      return commit(state, setMember(without, edit.toSlotId, edit.line, member), edit.toSlotId, edit.line);
    }
    case 'remove': {
      const slot = find(edit.slotId);
      if (slot === undefined) return fail({ kind: 'unknown_slot', slotId: edit.slotId });
      if (!slot.members.some((x) => x.line === edit.line)) return fail({ kind: 'no_member', slotId: edit.slotId, line: edit.line });
      return commit(state, setMember(state.slots, edit.slotId, edit.line, null), edit.slotId, edit.line);
    }
    case 'new_slot': {
      if (!wordFits(state, edit.line, edit.word)) return fail({ kind: 'word_out_of_range', line: edit.line, word: edit.word });
      const held = memberAt(state, edit.line, edit.word);
      const member = held?.member ?? { line: edit.line, start: edit.word, end: edit.word + 1 };
      const rest = held === null ? [...state.slots] : setMember(state.slots, held.slotId, edit.line, null);
      if (rest.length >= PATTERN_MAX_SLOTS) return fail({ kind: 'too_many_slots' });
      const id = `u${state.nextUserId}`;
      const at = insertionIndex(state, rest, member);
      const slots = [...rest.slice(0, at), { id, on: true, members: [member] }, ...rest.slice(at)];
      return commit(state, slots, id, edit.line, state.nextUserId + 1);
    }
    case 'drop': {
      if (find(edit.slotId) === undefined) return fail({ kind: 'unknown_slot', slotId: edit.slotId });
      return { ok: true, state: { ...state, slots: state.slots.filter((s) => s.id !== edit.slotId) } };
    }
    case 'toggle': {
      if (find(edit.slotId) === undefined) return fail({ kind: 'unknown_slot', slotId: edit.slotId });
      return { ok: true, state: { ...state, slots: state.slots.map((s) => (s.id === edit.slotId ? { ...s, on: !s.on } : s)) } };
    }
  }
}
