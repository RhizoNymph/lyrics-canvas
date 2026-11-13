/** Test fixtures for the pattern view: wire-shaped lines, slots, candidates and line matches. */
import type { GridLine } from '@/types';
import type { RhymeUnit } from '@/types/rhyme';
import type { LineMatch, LineMatchHit, PatternCandidate, PatternLine, PatternSlot, PatternSpan } from '@/types/rhymePattern';
import { lineWords } from '@/lib/grid/restMarks';

/** One cell per word (stress 1), word indexes in order. */
export function gridOf(text: string): GridLine {
  const words = lineWords(text);
  return {
    cells: words.map((w, i) => ({ syllable: w, stress: 1, word_index: i })),
    syllable_count: words.length,
    raw_text: text,
  };
}

export function pline(lineIndex: number, text: string): PatternLine {
  const words = lineWords(text);
  return {
    line_index: lineIndex,
    text: words.join(' '),
    words: words.map((w, i) => ({ word_index: i, text: w, syllable_count: 1, stress: '1' })),
    grid_line: gridOf(text),
  };
}

export function pspan(line: number, start: number, end: number, text = `w${line}.${start}`, key: string[] = ['EH', 'D', 'IY']): PatternSpan {
  return {
    line_index: line,
    word_start: start,
    word_end: end,
    text,
    syllable_count: 2,
    stress: '10',
    rhyme_key: key,
    syllables: [],
  };
}

export function pslot(id: string, members: Array<[number, number, number]>, over: Partial<PatternSlot> = {}): PatternSlot {
  return {
    id,
    members: members.map(([l, s, e]) => pspan(l, s, e)),
    coverage: members.length,
    full: true,
    default_on: true,
    k: 2,
    cohesion: 0.9,
    syllable_count: 2,
    stress: '10',
    ...over,
  };
}

export function punit(id: string, text: string, over: Partial<RhymeUnit> = {}): RhymeUnit {
  return {
    id,
    text,
    provenance: id.startsWith('dict:') ? 'dictionary' : 'note',
    ephemeral: true,
    syllable_count: 2,
    stress: '10',
    rhyme_key: ['EH', 'D', 'IY'],
    syllables: [],
    grid_line: gridOf(text),
    tags: [],
    ...over,
  };
}

export function pcand(id: string, text: string, score: number, over: Partial<PatternCandidate> = {}): PatternCandidate {
  return {
    ...punit(id, text),
    score,
    match_depth: 2,
    best_anchor_id: 'pattern:sn:0:0-1',
    fits_slot: true,
    alignment: [],
    source: id.startsWith('dict:') ? 'dictionary' : 'notes',
    member_scores: [score, score],
    ...over,
  };
}

export function phit(slotId: string, start: number, end: number, text: string, score = 0.8): LineMatchHit {
  return { slot_id: slotId, word_start: start, word_end: end, text, score };
}

export function pmatch(id: string, text: string, hits: LineMatchHit[], score = 0.8): LineMatch {
  return { unit: punit(id, text), hits, slots_hit: hits.length, score };
}
