/**
 * The tuple tray (contract §2.2): one pick per slot, built client-side from the independent slot lists
 * (no Cartesian product). Off slots keep their pick but leave the tray and the snippet line.
 */
import type { TrayEntry, TuplePick, TuplePicks } from './types';

export const EMPTY_PICKS: TuplePicks = new Map();

/** Pick `pick` for the slot; picking the slot's current candidate again clears it. */
export function togglePick(picks: TuplePicks, slotId: string, pick: TuplePick): TuplePicks {
  const next = new Map(picks);
  if (picks.get(slotId)?.candidateId === pick.candidateId) next.delete(slotId);
  else next.set(slotId, pick);
  return next;
}

/** Drop picks of slots no longer in the edit state; the same map when nothing goes. */
export function prunePicks(picks: TuplePicks, slotIds: ReadonlySet<string>): TuplePicks {
  if ([...picks.keys()].every((id) => slotIds.has(id))) return picks;
  return new Map([...picks].filter(([id]) => slotIds.has(id)));
}

export function trayEntries(slots: ReadonlyArray<{ id: string; letter: string; on: boolean }>, picks: TuplePicks): TrayEntry[] {
  return slots.filter((s) => s.on).map((s) => ({ slotId: s.id, letter: s.letter, text: picks.get(s.id)?.text ?? null }));
}

export const TRAY_GAP = '___';

/** `steady · ___ · strong` */
export function trayLabel(entries: readonly TrayEntry[]): string {
  return entries.map((e) => e.text ?? TRAY_GAP).join(' · ');
}

export function pickTexts(entries: readonly TrayEntry[]): string[] {
  return entries.flatMap((e) => (e.text === null ? [] : [e.text]));
}

/** "Make snippet": the picks in slot order joined by spaces, gaps skipped. */
export function tupleText(entries: readonly TrayEntry[]): string {
  return pickTexts(entries).join(' ');
}
