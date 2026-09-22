import { describe, it, expect } from 'vitest';
import { anchorsFromSelection, selectedSnippets } from './queryFromSelection';
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
