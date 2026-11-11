import { SNIPPET_TYPE_COLORS } from '@/types';
import type { GlobalSearchResponse, SearchSnippetHit } from '@/types/search';
import { describeSearchError, highlightSegments, parseSearchQuery } from '@/lib/search';
import type { GlobalSearchView, SearchQuery } from '@/lib/search';

interface Props {
  /** Never `off` (the page shows its canvas list then). */
  view: Exclude<GlobalSearchView, { kind: 'off' }>;
  onOpenCanvas: (canvasId: string) => void;
  onOpenSnippet: (canvasId: string, snippetId: string) => void;
}

const sectionTitle = { fontSize: 12, color: '#8a8aaa', textTransform: 'uppercase', letterSpacing: 1, margin: '20px 0 8px' } as const;
const rowStyle = {
  display: 'block',
  width: '100%',
  textAlign: 'left',
  padding: '12px 16px',
  background: '#1e1e3a',
  borderRadius: 8,
  border: '1px solid #2a2a4a',
  cursor: 'pointer',
  color: '#e0e0e0',
  fontSize: 14,
  boxSizing: 'border-box',
} as const;
const linkStyle = { background: 'none', border: 'none', padding: 0, color: '#c7d2fe', cursor: 'pointer', fontSize: 11, textDecoration: 'underline' } as const;

/** Highlights are computed here with the matching rule; the server's excerpt may be cut (leading/trailing …). */
function Excerpt({ text, query }: { text: string; query: SearchQuery }) {
  return (
    <>
      {highlightSegments(text, query).map((seg, i) =>
        seg.match ? (
          <mark key={i} style={{ background: 'rgba(250, 204, 21, 0.35)', color: '#fff', borderRadius: 2 }}>
            {seg.text}
          </mark>
        ) : (
          <span key={i}>{seg.text}</span>
        ),
      )}
    </>
  );
}

function SnippetHit({ hit, query, onOpenSnippet }: { hit: SearchSnippetHit; query: SearchQuery; onOpenSnippet: Props['onOpenSnippet'] }) {
  const first = hit.canvases[0];
  const color = SNIPPET_TYPE_COLORS[hit.snippet_type] ?? '#6b7280';
  return (
    <div
      role="link"
      tabIndex={0}
      onClick={() => first && onOpenSnippet(first.id, hit.snippet_id)}
      onKeyDown={(e) => {
        if ((e.key === 'Enter' || e.key === ' ') && first) {
          e.preventDefault();
          onOpenSnippet(first.id, hit.snippet_id);
        }
      }}
      style={{ ...rowStyle, borderLeft: `3px solid ${color}` }}
      data-snippet-hit={hit.snippet_id}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
        <span style={{ fontSize: 10, fontWeight: 'bold', textTransform: 'uppercase', color: '#fff', background: color, borderRadius: 3, padding: '1px 6px' }}>
          {hit.snippet_type}
        </span>
        <span style={{ fontSize: 11, color: '#8a8aaa' }}>
          {hit.canvases.map((c, i) => (
            <span key={c.id}>
              {i > 0 && ', '}
              <button
                type="button"
                onClick={(e) => {
                  e.stopPropagation();
                  onOpenSnippet(c.id, hit.snippet_id);
                }}
                style={linkStyle}
              >
                {c.title}
              </button>
            </span>
          ))}
        </span>
      </div>
      <div style={{ fontFamily: 'monospace', fontSize: 13, whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
        {hit.excerpt === '' ? <span style={{ color: '#6a6a8a' }}>No text</span> : <Excerpt text={hit.excerpt} query={query} />}
      </div>
    </div>
  );
}

function Results({ result, query, onOpenCanvas, onOpenSnippet }: { result: GlobalSearchResponse; query: SearchQuery } & Omit<Props, 'view'>) {
  if (result.canvases.length === 0 && result.snippets.length === 0) {
    return <div style={{ color: '#8a8aaa', textAlign: 'center', padding: 40 }}>No matches</div>;
  }
  return (
    <>
      {result.canvases.length > 0 && (
        <section aria-label="Canvases">
          <h2 style={sectionTitle}>Canvases</h2>
          <div style={{ display: 'grid', gap: 8 }}>
            {result.canvases.map((c) => (
              <button key={c.id} type="button" onClick={() => onOpenCanvas(c.id)} style={{ ...rowStyle, fontWeight: 'bold' }}>
                <Excerpt text={c.title} query={query} />
              </button>
            ))}
          </div>
        </section>
      )}
      {result.snippets.length > 0 && (
        <section aria-label="Snippets">
          <h2 style={sectionTitle}>Snippets</h2>
          <div style={{ display: 'grid', gap: 8 }}>
            {result.snippets.map((hit) => (
              <SnippetHit key={hit.snippet_id} hit={hit} query={query} onOpenSnippet={onOpenSnippet} />
            ))}
          </div>
        </section>
      )}
    </>
  );
}

/** Search results on the canvas list page: Canvases and Snippets sections, typed errors, "No matches". */
export default function GlobalSearchResultsView({ view, onOpenCanvas, onOpenSnippet }: Props) {
  switch (view.kind) {
    case 'loading':
      return (
        <div aria-busy="true">
          <div style={{ color: '#8a8aaa', fontSize: 12 }}>Searching…</div>
          {view.previous !== null && (
            <div style={{ opacity: 0.5 }}>
              <Results result={view.previous} query={parseSearchQuery(view.q)} onOpenCanvas={onOpenCanvas} onOpenSnippet={onOpenSnippet} />
            </div>
          )}
        </div>
      );
    case 'failed':
      return (
        <div role="alert" style={{ color: '#fca5a5', fontSize: 13, padding: '16px 0' }}>
          {describeSearchError(view.error)}
        </div>
      );
    case 'results':
      return <Results result={view.result} query={view.query} onOpenCanvas={onOpenCanvas} onOpenSnippet={onOpenSnippet} />;
  }
}
