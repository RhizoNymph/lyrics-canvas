/**
 * Slot derivation from a snippet line's beat grid, and the client-side fit rule
 * (mirrors the server's `fits_slot` semantics for the panel and tests).
 */
import type { GridLine, Snippet } from '@/types';
import { STRESS_PATTERN } from '@/types/rhyme';
import type { Slot } from '@/types/rhyme';

/** One character per cell; rests and missing stress are "0". */
export function slotFromGridLine(line: GridLine): Slot | null {
  if (line.cells.length === 0) return null;
  const stress = line.cells.map((c) => (c.rest ? '0' : String(c.stress ?? 0))).join('');
  return { syllable_count: line.cells.length, stress };
}

export function lastNonEmptyLineIndex(snippet: Snippet): number | null {
  const lines = snippet.grid_data?.lines ?? [];
  for (let i = lines.length - 1; i >= 0; i--) {
    if (lines[i].cells.length > 0) return i;
  }
  return null;
}

/** `lineIndex === null` selects the last non-empty line, matching the API's anchor semantics. */
export function slotFromSnippet(snippet: Snippet, lineIndex: number | null): Slot | null {
  const lines = snippet.grid_data?.lines ?? [];
  const idx = lineIndex === null ? lastNonEmptyLineIndex(snippet) : lineIndex;
  if (idx === null || idx < 0 || idx >= lines.length) return null;
  return slotFromGridLine(lines[idx]);
}

/**
 * A stress string fits a slot when it has the slot's length and no stressed
 * syllable (1 or 2) lands on a slot "0".
 */
export function fits(stress: string, slot: Slot): boolean {
  if (stress.length !== slot.stress.length) return false;
  for (let i = 0; i < stress.length; i++) {
    if (stress[i] !== '0' && slot.stress[i] === '0') return false;
  }
  return true;
}

export type ManualSlotResult = { ok: true; slot: Slot } | { ok: false; reason: 'count' | 'stress' };

/** Validate the panel's manual slot inputs. */
export function parseManualSlot(countText: string, stress: string): ManualSlotResult {
  if (!STRESS_PATTERN.test(stress)) return { ok: false, reason: 'stress' };
  const count = Number(countText);
  if (!Number.isInteger(count) || count <= 0 || count !== stress.length) return { ok: false, reason: 'count' };
  return { ok: true, slot: { syllable_count: count, stress } };
}
