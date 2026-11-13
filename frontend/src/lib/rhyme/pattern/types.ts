/**
 * Client-side state types of the rhyme pattern view (contract §2). Wire shapes live in
 * `@/types/rhymePattern`; these are the editable, invariant-carrying forms the reducers work on.
 */

/** The pattern range of one snippet on one canvas: `[lineStart, lineEnd)`, at least 2 lines with words. */
export interface PatternSession {
  readonly canvasId: string;
  readonly snippetId: string;
  readonly lineStart: number;
  /** Exclusive. */
  readonly lineEnd: number;
}

/** A line range picked on a chip, `[lineStart, lineEnd)`. */
export interface LineRange {
  readonly lineStart: number;
  readonly lineEnd: number;
}

/** One line's span in a slot: words `[start, end)` of absolute snippet line `line`. */
export interface Member {
  readonly line: number;
  readonly start: number;
  /** Exclusive, > start. */
  readonly end: number;
}

/**
 * An editable slot. Invariants (kept by `applySlotEdit`): at least one member, members sorted by line with
 * at most one per line; across the slot list no two members on a line overlap and, on every line, members
 * appear in slot order.
 */
export interface EditSlot {
  /** "s0".. from detection, "u0".. for slots the user started; never reused within a session. */
  readonly id: string;
  readonly members: readonly Member[];
  /** Off slots are kept and shown dimmed but not sent to suggest. */
  readonly on: boolean;
}

/** A pattern line as the edit reducer needs it. */
export interface LineShape {
  readonly line: number;
  readonly wordCount: number;
}

export interface SlotEditState {
  readonly lines: readonly LineShape[];
  /** In slot order. */
  readonly slots: readonly EditSlot[];
  /** Next "u" id number. */
  readonly nextUserId: number;
}

export type SlotEdit =
  /** Put the single word in the slot; replaces the slot's member on that line. */
  | { readonly type: 'add'; readonly slotId: string; readonly line: number; readonly word: number }
  /** Grow the slot's member on that line to cover the word. */
  | { readonly type: 'extend'; readonly slotId: string; readonly line: number; readonly word: number }
  /** Move `fromSlotId`'s member on the line to `toSlotId` (replacing its member there). */
  | { readonly type: 'move'; readonly fromSlotId: string; readonly toSlotId: string; readonly line: number }
  | { readonly type: 'remove'; readonly slotId: string; readonly line: number }
  /** Start a slot from the word (or from the member holding it, which leaves its slot). Placed in order. */
  | { readonly type: 'new_slot'; readonly line: number; readonly word: number }
  | { readonly type: 'drop'; readonly slotId: string }
  | { readonly type: 'toggle'; readonly slotId: string };

export type SlotEditError =
  | { readonly kind: 'unknown_slot'; readonly slotId: string }
  | { readonly kind: 'word_out_of_range'; readonly line: number; readonly word: number }
  /** The word (or the grown span) is taken by another slot's member. */
  | { readonly kind: 'overlap'; readonly line: number; readonly ownerId: string }
  /** The edit would put the slot out of order on the line. */
  | { readonly kind: 'order'; readonly slotId: string; readonly line: number }
  | { readonly kind: 'no_member'; readonly slotId: string; readonly line: number }
  | { readonly kind: 'same_slot'; readonly slotId: string }
  | { readonly kind: 'too_many_slots' };

export type SlotEditResult =
  | { readonly ok: true; readonly state: SlotEditState }
  | { readonly ok: false; readonly error: SlotEditError };

/** One rhyme candidate picked for a slot in the tuple tray. */
export interface TuplePick {
  readonly candidateId: string;
  readonly text: string;
}

export type TuplePicks = ReadonlyMap<string, TuplePick>;

/** A tray position: the slot and its pick, or a gap. */
export interface TrayEntry {
  readonly slotId: string;
  readonly letter: string;
  readonly text: string | null;
}
