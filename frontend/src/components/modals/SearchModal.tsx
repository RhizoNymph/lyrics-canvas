import { useState, useCallback, useRef, useEffect } from 'react';
import { nlpApi } from '@/api/client';
import { useUIStore } from '@/stores/uiStore';
import { useCanvasStore } from '@/stores/canvasStore';
import { SNIPPET_TYPE_COLORS } from '@/types';

interface SearchResult {
  snippet_id: string;
  raw_text: string;
  score: number;
  snippet_type: string;
}

export default function SearchModal() {
  const { searchModalOpen, setSearchModalOpen } = useUIStore();
  const { canvasId, setViewport, selectSnippet, snippets } = useCanvasStore();
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<SearchResult[]>([]);
  const [loading, setLoading] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (searchModalOpen) {
      inputRef.current?.focus();
    }
  }, [searchModalOpen]);

  const handleSearch = useCallback(async () => {
    if (!query.trim() || !canvasId) return;
    setLoading(true);
    try {
      const res = await nlpApi.search(canvasId, query.trim());
      setResults(res.data);
    } catch {
      setResults([]);
    } finally {
      setLoading(false);
    }
  }, [query, canvasId]);

  const navigateTo = (snippetId: string) => {
    const s = snippets.get(snippetId);
    if (s) {
      setViewport({ x: -s.position_x + 400, y: -s.position_y + 300 });
      selectSnippet(snippetId);
      setSearchModalOpen(false);
    }
  };

  if (!searchModalOpen) return null;

  return (
    <div
      style={{
        position: 'fixed',
        inset: 0,
        background: 'rgba(0,0,0,0.6)',
        display: 'flex',
        alignItems: 'flex-start',
        justifyContent: 'center',
        paddingTop: 100,
        zIndex: 2000,
      }}
      onClick={() => setSearchModalOpen(false)}
    >
      <div
        style={{
          width: 560,
          maxHeight: '60vh',
          background: '#1e1e3a',
          borderRadius: 12,
          border: '1px solid #3a3a5a',
          display: 'flex',
          flexDirection: 'column',
          overflow: 'hidden',
        }}
        onClick={(e) => e.stopPropagation()}
      >
        <div style={{ padding: 16, display: 'flex', gap: 8 }}>
          <input
            ref={inputRef}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && handleSearch()}
            placeholder="Search snippets..."
            style={{
              flex: 1,
              background: '#2a2a4a',
              color: '#e0e0e0',
              border: '1px solid #3a3a5a',
              borderRadius: 6,
              padding: '10px 14px',
              fontSize: 14,
            }}
          />
          <button
            onClick={handleSearch}
            style={{ background: '#6366f1', color: '#fff', border: 'none', borderRadius: 6, padding: '10px 20px', cursor: 'pointer' }}
          >
            Search
          </button>
        </div>

        <div style={{ flex: 1, overflowY: 'auto', padding: '0 16px 16px' }}>
          {loading && <div style={{ color: '#888', padding: 16, textAlign: 'center' }}>Searching...</div>}
          {!loading && results.length === 0 && query && (
            <div style={{ color: '#666', padding: 16, textAlign: 'center' }}>No results found</div>
          )}
          {results.map((r) => (
            <div
              key={r.snippet_id}
              onClick={() => navigateTo(r.snippet_id)}
              style={{
                padding: '10px 12px',
                marginBottom: 6,
                background: '#16162a',
                borderRadius: 6,
                cursor: 'pointer',
                borderLeft: `3px solid ${SNIPPET_TYPE_COLORS[r.snippet_type as keyof typeof SNIPPET_TYPE_COLORS] || '#666'}`,
              }}
            >
              <div style={{ fontSize: 11, color: '#888', textTransform: 'uppercase', marginBottom: 4 }}>
                {r.snippet_type}
              </div>
              <div style={{ fontSize: 13, color: '#e0e0e0' }}>
                {r.raw_text.slice(0, 120)}
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
