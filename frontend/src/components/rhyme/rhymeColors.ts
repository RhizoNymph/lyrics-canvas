import type { Provenance } from '@/types/rhyme';

/** Header colours per provenance. Distinct from SNIPPET_TYPE_COLORS so candidates read as ephemeral. */
export const PROVENANCE_COLORS: Record<Provenance, string> = {
  note: '#0ea5e9',       // sky blue (verse is #3b82f6)
  dictionary: '#14b8a6', // teal
  datamuse: '#a855f7',   // reserved
  generated: '#71717a',  // zinc grey (freestyle is #6b7280)
};

export const PROVENANCE_LABELS: Record<Provenance, string> = {
  note: 'NOTE',
  dictionary: 'DICT',
  datamuse: 'DATAMUSE',
  generated: 'TEXT',
};

/** Opacity for candidates that do not fit the slot. */
export const NON_FIT_ALPHA = 0.45;

export function hexToNumber(hex: string): number {
  return parseInt(hex.replace('#', ''), 16);
}

/** Colour for an alignment score in [0, 1]: red (0) through amber to green (1). */
export function alignmentColor(total: number): string {
  const t = Math.max(0, Math.min(1, Number.isFinite(total) ? total : 0));
  const hue = Math.round(t * 120);
  return `hsl(${hue}, 65%, 42%)`;
}
