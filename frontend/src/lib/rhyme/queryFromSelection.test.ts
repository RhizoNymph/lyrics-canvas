import { describe, it, expect } from 'vitest';
import { anchorsFromSelection, anchorSnippets, selectedSnippets } from './queryFromSelection';
import type { Snippet } from '@/types';

const snip = (id: string, raw_text: string): Snippet => ({ id, raw_text } as unknown as Snippet);
const snippets = new Map<string, Snippet>([
  ['b', snip('b', 'orange door hinge')],
  ['a', snip('a', 'four inch')],
  ['empty', snip('empty', '   ')],
]);

describe('anchorsFromSelection', () => {
  it('is empty with nothing selected and no text', () => {
    expect(anchorsFromSelection(new Set(), snippets, '')).toEqual([]);
    expect(anchorsFromSelection(new Set(), snippets, '   ')).toEqual([]);
  });

  it('builds snippet anchors (last line) in id order and skips unknown or blank snippets', () => {
    const out = anchorsFromSelection(new Set(['b', 'missing', 'empty', 'a']), snippets, '');
    expect(out).toEqual([
      { kind: 'snippet', snippet_id: 'a', line_index: null },
      { kind: 'snippet', snippet_id: 'b', line_index: null },
    ]);
  });

  it('appends a trimmed text anchor after snippet anchors', () => {
    const out = anchorsFromSelection(new Set(['a']), snippets, '  door hinge ');
    expect(out).toEqual([
      { kind: 'snippet', snippet_id: 'a', line_index: null },
      { kind: 'text', text: 'door hinge' },
    ]);
  });

  it('works with text only', () => {
    expect(anchorsFromSelection(new Set(), snippets, 'orange')).toEqual([{ kind: 'text', text: 'orange' }]);
  });
});

describe('selectedSnippets', () => {
  it('returns present snippets in id order', () => {
    expect(selectedSnippets(new Set(['b', 'zzz', 'a']), snippets).map((s) => s.id)).toEqual(['a', 'b']);
  });
});

describe('anchor exclusion', () => {
  it('skips snippet anchors whose id was excluded for this query', () => {
    const out = anchorsFromSelection(new Set(['a', 'b']), snippets, 'tin', new Set(['a']));
    expect(out).toEqual([
      { kind: 'snippet', snippet_id: 'b', line_index: null },
      { kind: 'text', text: 'tin' },
    ]);
  });

  it('can exclude every snippet and leave only the typed anchor', () => {
    expect(anchorsFromSelection(new Set(['a', 'b']), snippets, 'tin', new Set(['a', 'b']))).toEqual([{ kind: 'text', text: 'tin' }]);
  });
});

describe('anchorSnippets', () => {
  it('returns selected, non-blank, non-excluded snippets in id order', () => {
    expect(anchorSnippets(new Set(['b', 'empty', 'a', 'zzz']), snippets, new Set()).map((s) => s.id)).toEqual(['a', 'b']);
    expect(anchorSnippets(new Set(['b', 'a']), snippets, new Set(['b'])).map((s) => s.id)).toEqual(['a']);
  });
});

describe('anchor word ranges', () => {
  const ranged = new Map<string, Snippet>([
    ['a', { id: 'a', raw_text: 'I saw a black cat\norange door hinge', grid_data: { lines: [
      { cells: [], syllable_count: 0, raw_text: 'I saw a black cat' },
      { cells: [], syllable_count: 0, raw_text: 'orange door hinge' },
    ] } } as unknown as Snippet],
    ['b', snip('b', 'four inch')],
  ]);

  it('sends line_index, word_start and word_end for a snippet with a range', () => {
    const ranges = new Map([['a', { lineIndex: 0, wordStart: 3, wordEnd: 5 }]]);
    expect(anchorsFromSelection(new Set(['a', 'b']), ranged, '', new Set(), ranges)).toEqual([
      { kind: 'snippet', snippet_id: 'a', line_index: 0, word_start: 3, word_end: 5 },
      { kind: 'snippet', snippet_id: 'b', line_index: null },
    ]);
  });

  it('an excluded snippet sends nothing even with a range', () => {
    const ranges = new Map([['a', { lineIndex: 0, wordStart: 3, wordEnd: 5 }]]);
    expect(anchorsFromSelection(new Set(['a']), ranged, 'tin', new Set(['a']), ranges)).toEqual([{ kind: 'text', text: 'tin' }]);
  });
});
