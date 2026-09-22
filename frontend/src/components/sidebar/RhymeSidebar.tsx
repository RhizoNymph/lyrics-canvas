import { useState, useEffect } from 'react';
import { nlpApi } from '@/api/client';
import { useUIStore } from '@/stores/uiStore';
import type { RhymeResult } from '@/types';

export default function RhymeSidebar() {
  const { rhymeSidebarOpen, toggleRhymeSidebar, rhymeWord, setRhymeWord } = useUIStore();
  const [input, setInput] = useState(rhymeWord);
  const [dictRhymes, setDictRhymes] = useState<{ perfect: string[]; near: string[] }>({ perfect: [], near: [] });
  const [snippetRhymes, setSnippetRhymes] = useState<RhymeResult[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    setInput(rhymeWord);
    if (rhymeWord) fetchRhymes(rhymeWord);
  }, [rhymeWord]);

  const fetchRhymes = async (word: string) => {
    if (!word.trim()) return;
    setLoading(true);
    try {
      const res = await nlpApi.rhymes(word.trim());
      setDictRhymes(res.data.dictionary);
      setSnippetRhymes(res.data.snippets);
    } catch {
      setDictRhymes({ perfect: [], near: [] });
      setSnippetRhymes([]);
    } finally {
      setLoading(false);
    }
  };

  if (!rhymeSidebarOpen) return null;

  return (
    <div
      style={{
        width: 280,
        background: '#1a1a2e',
        borderLeft: '1px solid #2a2a4a',
        display: 'flex',
        flexDirection: 'column',
        overflow: 'hidden',
      }}
    >
      <div style={{ padding: 12, borderBottom: '1px solid #2a2a4a', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <span style={{ fontWeight: 'bold', fontSize: 14 }}>Rhyme Finder</span>
        <button onClick={toggleRhymeSidebar} style={{ background: 'none', border: 'none', color: '#888', cursor: 'pointer', fontSize: 18 }}>
          ×
        </button>
      </div>

      <div style={{ padding: 12 }}>
        <div style={{ display: 'flex', gap: 8 }}>
          <input
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && fetchRhymes(input)}
            placeholder="Enter a word..."
            style={{
              flex: 1,
              background: '#2a2a4a',
              color: '#e0e0e0',
              border: '1px solid #3a3a5a',
              borderRadius: 4,
              padding: '6px 10px',
              fontSize: 13,
            }}
          />
          <button
            onClick={() => fetchRhymes(input)}
            style={{ background: '#6366f1', color: '#fff', border: 'none', borderRadius: 4, padding: '6px 12px', cursor: 'pointer' }}
          >
            Find
          </button>
        </div>
      </div>

      <div style={{ flex: 1, overflowY: 'auto', padding: '0 12px 12px' }}>
        {loading && <div style={{ color: '#888', padding: 12 }}>Searching...</div>}

        {!loading && dictRhymes.perfect.length > 0 && (
          <div style={{ marginBottom: 16 }}>
            <h4 style={{ fontSize: 12, color: '#6366f1', marginBottom: 6, textTransform: 'uppercase' }}>Perfect Rhymes</h4>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
              {dictRhymes.perfect.slice(0, 30).map((w) => (
                <span
                  key={w}
                  onClick={() => { setRhymeWord(w); setInput(w); }}
                  style={{
                    background: '#2a2a4a',
                    padding: '2px 8px',
                    borderRadius: 3,
                    fontSize: 12,
                    cursor: 'pointer',
                  }}
                >
                  {w}
                </span>
              ))}
            </div>
          </div>
        )}

        {!loading && dictRhymes.near.length > 0 && (
          <div style={{ marginBottom: 16 }}>
            <h4 style={{ fontSize: 12, color: '#8b5cf6', marginBottom: 6, textTransform: 'uppercase' }}>Near Rhymes</h4>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
              {dictRhymes.near.slice(0, 20).map((w) => (
                <span
                  key={w}
                  onClick={() => { setRhymeWord(w); setInput(w); }}
                  style={{
                    background: '#2a2a4a',
                    padding: '2px 8px',
                    borderRadius: 3,
                    fontSize: 12,
                    cursor: 'pointer',
                    opacity: 0.8,
                  }}
                >
                  {w}
                </span>
              ))}
            </div>
          </div>
        )}

        {!loading && snippetRhymes.length > 0 && (
          <div>
            <h4 style={{ fontSize: 12, color: '#f59e0b', marginBottom: 6, textTransform: 'uppercase' }}>In Your Snippets</h4>
            {snippetRhymes.map((r, i) => (
              <div key={i} style={{ padding: '6px 0', borderBottom: '1px solid #1a1a3a' }}>
                <span style={{ color: '#f59e0b', fontWeight: 'bold' }}>{r.word}</span>
                {r.snippet_preview && (
                  <div style={{ fontSize: 11, color: '#888', marginTop: 2 }}>
                    {r.snippet_preview}
                  </div>
                )}
              </div>
            ))}
          </div>
        )}

        {!loading && dictRhymes.perfect.length === 0 && dictRhymes.near.length === 0 && rhymeWord && (
          <div style={{ color: '#666', padding: 12, textAlign: 'center' }}>No rhymes found</div>
        )}
      </div>
    </div>
  );
}
