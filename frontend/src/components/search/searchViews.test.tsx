import { describe, it, expect } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import CanvasSearchBoxView from './CanvasSearchBoxView';
import GlobalSearchResultsView from './GlobalSearchResultsView';
import { SearchError, parseSearchQuery } from '@/lib/search';
import type { GlobalSearchResponse } from '@/types/search';

const noop = () => undefined;

describe('CanvasSearchBoxView', () => {
  it('shows the box, the clear button and the count while searching', () => {
    const html = renderToStaticMarkup(<CanvasSearchBoxView text="cat" countLabel="3 of 12" onChange={noop} onClear={noop} />);
    expect(html).toContain('aria-label="Search snippets on this canvas"');
    expect(html).toContain('value="cat"');
    expect(html).toContain('aria-label="Clear the search"');
    expect(html).toContain('3 of 12');
  });

  it('has no count and a disabled clear when empty', () => {
    const html = renderToStaticMarkup(<CanvasSearchBoxView text="" countLabel={null} onChange={noop} onClear={noop} />);
    expect(html).not.toContain(' of ');
    expect(html).toMatch(/<button[^>]*disabled=""[^>]*aria-label="Clear the search"/);
  });
});

const result: GlobalSearchResponse = {
  canvases: [{ id: 'c1', title: 'Night songs', updated_at: '' }],
  snippets: [
    {
      snippet_id: 's1',
      snippet_type: 'chorus',
      content_updated_at: '',
      excerpt: 'black cat at night',
      canvases: [
        { id: 'c1', title: 'Night songs' },
        { id: 'c2', title: 'Drafts' },
      ],
    },
  ],
};

describe('GlobalSearchResultsView', () => {
  it('shows Canvases and Snippets sections with highlighted terms and every canvas title', () => {
    const html = renderToStaticMarkup(
      <GlobalSearchResultsView view={{ kind: 'results', q: 'night', query: parseSearchQuery('night'), result }} onOpenCanvas={noop} onOpenSnippet={noop} />,
    );
    expect(html).toContain('aria-label="Canvases"');
    expect(html).toContain('aria-label="Snippets"');
    expect(html).toContain('<mark');
    expect(html).toMatch(/<mark[^>]*>night<\/mark>/);
    expect(html).toContain('Drafts');
    expect(html).toContain('chorus');
  });

  it('says No matches for empty results', () => {
    const html = renderToStaticMarkup(
      <GlobalSearchResultsView
        view={{ kind: 'results', q: 'zz', query: parseSearchQuery('zz'), result: { canvases: [], snippets: [] } }}
        onOpenCanvas={noop}
        onOpenSnippet={noop}
      />,
    );
    expect(html).toContain('No matches');
  });

  it('shows typed errors', () => {
    const net = renderToStaticMarkup(<GlobalSearchResultsView view={{ kind: 'failed', q: 'x', error: new SearchError('network', 'Network Error') }} onOpenCanvas={noop} onOpenSnippet={noop} />);
    expect(net).toContain('Could not reach the server');
    const bad = renderToStaticMarkup(
      <GlobalSearchResultsView view={{ kind: 'failed', q: 'x', error: new SearchError('invalid_search_query', 'q must be 1 to 200 characters', 422) }} onOpenCanvas={noop} onOpenSnippet={noop} />,
    );
    expect(bad).toContain('q must be 1 to 200 characters');
  });

  it('shows Searching… over the previous results while loading', () => {
    const html = renderToStaticMarkup(<GlobalSearchResultsView view={{ kind: 'loading', q: 'night', previous: result }} onOpenCanvas={noop} onOpenSnippet={noop} />);
    expect(html).toContain('Searching…');
    expect(html).toContain('Night songs');
  });
});

describe('GlobalSearchResultsView excerpts', () => {
  it('highlights inside a cut excerpt and marks an empty one', () => {
    const cut: GlobalSearchResponse = {
      canvases: [{ id: 'c_1', title: 'my_draft', updated_at: '2026-11-01 10:00:00' }],
      snippets: [
        { snippet_id: 'a', snippet_type: 'verse', content_updated_at: '', excerpt: '…the night is long…', canvases: [{ id: 'c1', title: 'A' }] },
        { snippet_id: 'b', snippet_type: 'hook', content_updated_at: '', excerpt: '', canvases: [{ id: 'c1', title: 'A' }] },
      ],
    };
    const html = renderToStaticMarkup(
      <GlobalSearchResultsView view={{ kind: 'results', q: 'night _', query: parseSearchQuery('night _'), result: cut }} onOpenCanvas={noop} onOpenSnippet={noop} />,
    );
    expect(html).toMatch(/<span>…the <\/span><mark[^>]*>night<\/mark><span> is long…<\/span>/);
    expect(html).toMatch(/<span>my<\/span><mark[^>]*>_<\/mark><span>draft<\/span>/);
    expect(html).toContain('No text');
  });
});
