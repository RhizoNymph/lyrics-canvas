/**
 * Display helpers over the edit state: slot letters and legend data, the word picker's actions (each one
 * pre-checked against the reducer so a refused edit shows its reason instead of failing on tap), and
 * user-facing messages for refused edits.
 */
import { PATTERN_MAX_SLOTS } from '@/types/rhymePattern';
import type { PatternSlot } from '@/types/rhymePattern';
import { applySlotEdit, memberAt } from './slotEdit';
import type { Member, SlotEdit, SlotEditError, SlotEditState } from './types';

export function slotLetter(index: number): string {
  return String.fromCharCode(65 + index);
}

export interface SlotView {
  readonly id: string;
  /** Position in slot order; drives the letter and the colour. */
  readonly index: number;
  readonly letter: string;
  readonly on: boolean;
  /** Members in the edit state (lines covered). */
  readonly coverage: number;
  /** Pattern lines with words. */
  readonly lineCount: number;
  readonly full: boolean;
  /** Rhyme key of the last member as the server last computed it; null before the server has seen the slot. */
  readonly key: string | null;
}

export function slotViews(state: SlotEditState, server: ReadonlyMap<string, PatternSlot>): SlotView[] {
  const lineCount = state.lines.filter((l) => l.wordCount > 0).length;
  return state.slots.map((slot, index) => {
    const members = server.get(slot.id)?.members ?? [];
    const last = members.length === 0 ? undefined : members[members.length - 1];
    return {
      id: slot.id,
      index,
      letter: slotLetter(index),
      on: slot.on,
      coverage: slot.members.length,
      lineCount,
      full: slot.members.length === lineCount,
      key: last === undefined ? null : last.rhyme_key.join(' '),
    };
  });
}

/** One picker entry; `disabled` holds the refusal message when the edit would be refused. */
export interface WordAction {
  readonly label: string;
  readonly edit: SlotEdit;
  readonly disabled: string | null;
}

/**
 * The picker for word `word` of `line`. A word in a slot: move to each other slot, new slot from its member,
 * remove. A free word: add to each slot, extend each slot with a member on the line, new slot.
 * `textOf` renders a member's words for the "replaces" / "from" hints.
 */
export function wordActions(state: SlotEditState, line: number, word: number, textOf: (m: Member) => string): WordAction[] {
  const letterOf = letterLookup(state);
  const action = (label: string, edit: SlotEdit): WordAction => {
    const r = applySlotEdit(state, edit);
    return { label, edit, disabled: r.ok ? null : describeSlotEditError(r.error, letterOf) };
  };
  const held = memberAt(state, line, word);
  if (held !== null) {
    const moves = state.slots
      .filter((s) => s.id !== held.slotId)
      .map((s) => action(`Move to ${letterOf(s.id)}`, { type: 'move', fromSlotId: held.slotId, toSlotId: s.id, line }));
    return [
      ...moves,
      action(`New slot from "${textOf(held.member)}"`, { type: 'new_slot', line, word }),
      action(`Remove from ${letterOf(held.slotId)}`, { type: 'remove', slotId: held.slotId, line }),
    ];
  }
  const adds = state.slots.map((s) => {
    const there = s.members.find((x) => x.line === line);
    const hint = there === undefined ? '' : ` (replaces "${textOf(there)}")`;
    return action(`Add to ${letterOf(s.id)}${hint}`, { type: 'add', slotId: s.id, line, word });
  });
  const extends_ = state.slots
    .filter((s) => s.members.some((x) => x.line === line))
    .map((s) => action(`Extend ${letterOf(s.id)} to here`, { type: 'extend', slotId: s.id, line, word }));
  return [...adds, ...extends_, action('New slot', { type: 'new_slot', line, word })];
}

/** Slot id -> letter for the current order ("?" for an id no longer present). */
export function letterLookup(state: SlotEditState): (id: string) => string {
  const index = new Map(state.slots.map((s, i) => [s.id, i]));
  return (id) => {
    const i = index.get(id);
    return i === undefined ? '?' : slotLetter(i);
  };
}

/** Line numbers are shown 1-based, as on the anchor chip. */
export function describeSlotEditError(error: SlotEditError, letterOf: (id: string) => string): string {
  switch (error.kind) {
    case 'order':
      return `That would put slot ${letterOf(error.slotId)} out of order on line ${error.line + 1}.`;
    case 'overlap':
      return `Those words already belong to slot ${letterOf(error.ownerId)}.`;
    case 'too_many_slots':
      return `At most ${PATTERN_MAX_SLOTS} slots.`;
    case 'no_member':
      return `Slot ${letterOf(error.slotId)} has no word on line ${error.line + 1}.`;
    case 'unknown_slot':
      return 'That slot is gone.';
    case 'same_slot':
      return `Already in slot ${letterOf(error.slotId)}.`;
    case 'word_out_of_range':
      return 'That word is not on the line.';
  }
}
