import { describe, it, expect } from 'vitest';
import { canvasPath, planSnippetFocus, runSnippetFocus, snippetFocusParam, viewportCentredOn } from './focus';
import { parseSearchQuery, EMPTY_SEARCH } from './match';
import { parse } from '@/lib/tagExpr';
import { snippet as snippetFixture } from '@/lib/merge/fixtures';
import type { TagExprOrEmpty } from '@/lib/tagExpr';

function tagFilter(text: string): TagExprOrEmpty {
  const r = parse(text);
  if (!r.ok) throw new Error(`bad expression ${text}`);
  return r.expr;
}

describe('planSnippetFocus', () => {
  const s = snippetFixture('s1', 'black cat', { snippet_type: 'verse' });

  it('is missing when the snippet is not on the canvas', () => {
    expect(planSnippetFocus(undefined, null, EMPTY_SEARCH)).toEqual({ kind: 'missing' });
  });

  it('focuses a visible snippet without clearing anything', () => {
    expect(planSnippetFocus(s, null, parseSearchQuery('cat'))).toEqual({ kind: 'focus', clearSearch: false, hiddenByTag: false });
  });

  it('clears the in-canvas search only when it hides the snippet', () => {
    expect(planSnippetFocus(s, null, parseSearchQuery('dog'))).toEqual({ kind: 'focus', clearSearch: true, hiddenByTag: false });
  });

  it('reports a tag filter that still hides it, and never clears the tag filter', () => {
    expect(planSnippetFocus(s, tagFilter('type:chorus'), parseSearchQuery('dog'))).toEqual({ kind: 'focus', clearSearch: true, hiddenByTag: true });
    expect(planSnippetFocus(s, tagFilter('type:verse'), EMPTY_SEARCH)).toEqual({ kind: 'focus', clearSearch: false, hiddenByTag: false });
  });
});

describe('viewportCentredOn', () => {
  it('puts the snippet centre in the middle of the view at the current zoom', () => {
    const s = snippetFixture('s1', 'x', { position_x: 100, position_y: 50, width: 200, height: 100 });
    const v = viewportCentredOn(s, { width: 800, height: 600 }, 2);
    // screen = (pos + v) * zoom; centre (200, 100) lands on (400, 300).
    expect((200 + v.x) * 2).toBe(400);
    expect((100 + v.y) * 2).toBe(300);
  });
});

describe('snippetFocusParam', () => {
  it('reads ?snippet= and ignores blanks', () => {
    expect(snippetFocusParam(new URLSearchParams('snippet=abc'))).toBe('abc');
    expect(snippetFocusParam(new URLSearchParams('snippet='))).toBeNull();
    expect(snippetFocusParam(new URLSearchParams(''))).toBeNull();
  });
});

describe('canvasPath', () => {
  it('builds the canvas route with an optional focus parameter', () => {
    expect(canvasPath('c1')).toBe('/canvas/c1');
    expect(canvasPath('c 1', 's/1')).toBe('/canvas/c%201?snippet=s%2F1');
  });
});

describe('runSnippetFocus', () => {
  const s = snippetFixture('s1', 'black cat', { snippet_type: 'verse' });
  function ports(overrides: Partial<{ tagFilter: TagExprOrEmpty; search: string; listMode: boolean; snippets: Map<string, typeof s> }> = {}) {
    const calls: string[] = [];
    return {
      calls,
      ports: {
        snippets: overrides.snippets ?? new Map([[s.id, s]]),
        tagFilter: overrides.tagFilter ?? null,
        search: parseSearchQuery(overrides.search ?? ''),
        listMode: overrides.listMode ?? false,
        clearSearch: () => calls.push('clearSearch'),
        select: (id: string) => calls.push(`select ${id}`),
        reveal: (id: string) => calls.push(`reveal ${id}`),
        centre: (sn: { id: string }) => calls.push(`centre ${sn.id}`),
        notify: (text: string, tone: string) => calls.push(`${tone}: ${text}`),
      },
    };
  }

  it('selects and centres on the canvas', () => {
    const { calls, ports: p } = ports();
    runSnippetFocus('s1', p);
    expect(calls).toEqual(['select s1', 'centre s1']);
  });

  it('selects and scrolls in list mode', () => {
    const { calls, ports: p } = ports({ listMode: true });
    runSnippetFocus('s1', p);
    expect(calls).toEqual(['select s1', 'reveal s1']);
  });

  it('clears a hiding search, and toasts when the tag filter still hides it', () => {
    const { calls, ports: p } = ports({ search: 'dog', tagFilter: tagFilter('type:chorus') });
    runSnippetFocus('s1', p);
    expect(calls).toEqual(['clearSearch', 'select s1', 'centre s1', 'info: Hidden by the tag filter']);
  });

  it('reports a snippet that is not on the canvas', () => {
    const { calls, ports: p } = ports({ snippets: new Map() });
    expect(runSnippetFocus('s1', p)).toEqual({ kind: 'missing' });
    expect(calls).toEqual(['error: That snippet is no longer on this canvas']);
  });
});
