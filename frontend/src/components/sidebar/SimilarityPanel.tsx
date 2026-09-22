import { useState, useEffect } from 'react';
import { nlpApi } from '@/api/client';
import { useUIStore } from '@/stores/uiStore';
import { useCanvasStore } from '@/stores/canvasStore';
import type { SimilarityResult } from '@/types';
import { SNIPPET_TYPE_COLORS } from '@/types';

export default function SimilarityPanel() {
  const { similarityPanelOpen, toggleSimilarityPanel, similarityTargetId } = useUIStore();
  const { setViewport, selectSnippet, snippets } = useCanvasStore();
  const [results, setResults] = useState<SimilarityResult[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!similarityTargetId) return;
    setLoading(true);
    nlpApi
      .similar(similarityTargetId)
      .then((res) => setResults(res.data))
      .catch(() => setResults([]))
      .finally(() => setLoading(false));
  }, [similarityTargetId]);

  if (!similarityPanelOpen) return null;

  const navigateTo = (snippetId: string) => {
    const s = snippets.get(snippetId);
    if (s) {
      setViewport({ x: -s.position_x + 400, y: -s.position_y + 300 });
      selectSnippet(snippetId);
    }
  };

  return (
    <div
      style={{
        height: 200,
        background: '#1a1a2e',
        borderTop: '1px solid #2a2a4a',
        display: 'flex',
        flexDirection: 'column',
        overflow: 'hidden',
      }}
    >
      <div style={{ padding: '8px 12px', borderBottom: '1px solid #2a2a4a', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <span style={{ fontWeight: 'bold', fontSize: 13 }}>Similar Snippets</span>
        <button onClick={toggleSimilarityPanel} style={{ background: 'none', border: 'none', color: '#888', cursor: 'pointer', fontSize: 18 }}>
          ×
        </button>
      </div>

      <div style={{ flex: 1, overflowY: 'auto', padding: 8 }}>
        {loading && <div style={{ color: '#888', padding: 8 }}>Finding similar snippets...</div>}
        {!loading && results.length === 0 && (
          <div style={{ color: '#666', padding: 8, textAlign: 'center' }}>No similar snippets found</div>
        )}
        {results.map((r) => (
          <div
            key={r.snippet_id}
            onClick={() => navigateTo(r.snippet_id)}
            style={{
              padding: '8px 10px',
              marginBottom: 4,
              background: '#16162a',
              borderRadius: 4,
              cursor: 'pointer',
              borderLeft: `3px solid ${SNIPPET_TYPE_COLORS[r.snippet_type as keyof typeof SNIPPET_TYPE_COLORS] || '#666'}`,
            }}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 2 }}>
              <span style={{ fontSize: 11, color: '#888', textTransform: 'uppercase' }}>{r.snippet_type}</span>
              <span style={{ fontSize: 11, color: '#6366f1' }}>{(r.score * 100).toFixed(0)}% match</span>
            </div>
            <div style={{ fontSize: 12, color: '#ccc', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
              {r.raw_text.slice(0, 80)}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
