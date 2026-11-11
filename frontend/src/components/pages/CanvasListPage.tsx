import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { canvasApi, placementApi } from '@/api/client';
import TitleEditor from '@/components/placements/TitleEditor';
import { createCanvasFromInput, describePlacementError, renameCanvasIn, replaceCanvas } from '@/lib/placements';
import VoiceDatasetDialog from '@/components/voice/dataset/VoiceDatasetDialog';
import { useAuthStore } from '@/stores/authStore';
import type { Canvas } from '@/types';
import { useGlobalSearch } from '@/hooks/useGlobalSearch';
import { canvasPath } from '@/lib/search';
import GlobalSearchResultsView from '@/components/search/GlobalSearchResultsView';

export default function CanvasListPage() {
  const [canvases, setCanvases] = useState<Canvas[]>([]);
  const [loading, setLoading] = useState(true);
  const [newTitle, setNewTitle] = useState('');
  const [createError, setCreateError] = useState<string | null>(null);
  const [renamingId, setRenamingId] = useState<string | null>(null);
  const [datasetOpen, setDatasetOpen] = useState(false);
  const { user, logout } = useAuthStore();
  const navigate = useNavigate();
  const search = useGlobalSearch();

  const load = async () => {
    setLoading(true);
    try {
      setCanvases(await placementApi.listCanvases());
    } catch (err) {
      console.warn('canvas list failed to load', { error: err });
      setCanvases([]);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, []);

  const handleCreate = async () => {
    const result = await createCanvasFromInput(newTitle, placementApi.createCanvas);
    if (!result.ok) {
      setCreateError(describePlacementError(result.error));
      return;
    }
    setNewTitle('');
    setCreateError(null);
    navigate(`/canvas/${result.value.id}`);
  };

  const renameCanvas = async (id: string, title: string) => {
    const result = await renameCanvasIn(id, title, placementApi.renameCanvas);
    if (result.ok) setCanvases((prev) => replaceCanvas(prev, result.value));
    return result;
  };

  const handleDelete = async (id: string) => {
    await canvasApi.delete(id);
    setCanvases((prev) => prev.filter((c) => c.id !== id));
  };

  return (
    <div style={{ minHeight: '100vh', background: '#12122a', color: '#e0e0e0' }}>
      <header
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          padding: '16px 32px',
          borderBottom: '1px solid #2a2a4a',
        }}
      >
        <h1 style={{ fontSize: 20, fontWeight: 'bold' }}>Lyrics Canvas</h1>
        <input
          type="search"
          value={search.input}
          onChange={(e) => search.setInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Escape') search.setInput('');
          }}
          placeholder="Search all canvases and snippets…"
          aria-label="Search all canvases and snippets"
          spellCheck={false}
          style={{
            flex: 1,
            maxWidth: 420,
            margin: '0 24px',
            background: '#1e1e3a',
            color: '#e0e0e0',
            border: '1px solid #3a3a5a',
            borderRadius: 6,
            padding: '8px 12px',
            fontSize: 13,
          }}
        />
        <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
          <span style={{ fontSize: 13, color: '#888' }}>{user?.display_name || user?.email}</span>
          <button
            type="button"
            onClick={() => setDatasetOpen(true)}
            title="Voice takes and checked transcripts: stats and dataset export"
            style={{ background: '#2a2a4a', color: '#ccc', border: '1px solid #3a3a5a', borderRadius: 4, padding: '6px 14px', cursor: 'pointer', fontSize: 12 }}
          >
            Voice dataset
          </button>
          <button
            onClick={logout}
            style={{ background: '#2a2a4a', color: '#ccc', border: '1px solid #3a3a5a', borderRadius: 4, padding: '6px 14px', cursor: 'pointer', fontSize: 12 }}
          >
            Sign Out
          </button>
        </div>
      </header>

      <main style={{ maxWidth: 800, margin: '0 auto', padding: 32 }}>
        {search.view.kind !== 'off' ? (
          <GlobalSearchResultsView
            view={search.view}
            onOpenCanvas={(id) => navigate(canvasPath(id))}
            onOpenSnippet={(canvasId, snippetId) => navigate(canvasPath(canvasId, snippetId))}
          />
        ) : (
          <>
            <div style={{ display: 'flex', gap: 8, marginBottom: 32 }}>
              <input
                value={newTitle}
                onChange={(e) => {
                  setNewTitle(e.target.value);
                  setCreateError(null);
                }}
                onKeyDown={(e) => e.key === 'Enter' && handleCreate()}
                placeholder="New canvas title..."
                style={{
                  flex: 1,
                  background: '#1e1e3a',
                  color: '#e0e0e0',
                  border: '1px solid #3a3a5a',
                  borderRadius: 6,
                  padding: '12px 16px',
                  fontSize: 14,
                }}
              />
              <button
                onClick={handleCreate}
                style={{
                  background: '#6366f1',
                  color: '#fff',
                  border: 'none',
                  borderRadius: 6,
                  padding: '12px 24px',
                  cursor: 'pointer',
                  fontWeight: 'bold',
                  fontSize: 14,
                }}
              >
                Create
              </button>
            </div>
            {createError !== null && (
              <div role="alert" style={{ color: '#fca5a5', fontSize: 12, marginTop: -24, marginBottom: 24 }}>
                {createError}
              </div>
            )}

            {loading && <div style={{ color: '#888', textAlign: 'center', padding: 40 }}>Loading...</div>}

            {!loading && canvases.length === 0 && (
              <div style={{ color: '#666', textAlign: 'center', padding: 60 }}>
                <div style={{ fontSize: 40, marginBottom: 12 }}>~</div>
                <div style={{ fontSize: 16, marginBottom: 8 }}>No canvases yet</div>
                <div style={{ fontSize: 13 }}>Create one to start writing lyrics</div>
              </div>
            )}

            <div style={{ display: 'grid', gap: 12 }}>
              {canvases.map((canvas) => (
                <div
                  key={canvas.id}
                  style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    padding: '16px 20px',
                    background: '#1e1e3a',
                    borderRadius: 8,
                    border: '1px solid #2a2a4a',
                    cursor: 'pointer',
                  }}
                  onClick={() => {
                    if (renamingId !== canvas.id) navigate(`/canvas/${canvas.id}`);
                  }}
                >
                  <div style={{ minWidth: 0 }}>
                    <div style={{ fontSize: 15, fontWeight: 'bold', marginBottom: 4, display: 'flex', alignItems: 'center', gap: 6 }}>
                      {renamingId === canvas.id ? (
                        <TitleEditor
                          initial={canvas.title}
                          save={(title) => renameCanvas(canvas.id, title)}
                          onDone={() => setRenamingId(null)}
                          onCancel={() => setRenamingId(null)}
                        />
                      ) : (
                        <>
                          <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{canvas.title}</span>
                          <button
                            type="button"
                            onClick={(e) => {
                              e.stopPropagation();
                              setRenamingId(canvas.id);
                            }}
                            title="Rename canvas"
                            aria-label={`Rename ${canvas.title}`}
                            style={{ background: 'none', border: 'none', color: '#777', cursor: 'pointer', fontSize: 13, padding: '0 4px' }}
                          >
                            ✎
                          </button>
                        </>
                      )}
                    </div>
                    <div style={{ fontSize: 11, color: '#888' }}>
                      Updated {new Date(canvas.updated_at).toLocaleDateString()}
                      {canvas.is_public && <span style={{ marginLeft: 8, color: '#6366f1' }}>Public</span>}
                    </div>
                  </div>
                  <button
                    onClick={(e) => { e.stopPropagation(); handleDelete(canvas.id); }}
                    style={{ background: 'none', border: 'none', color: '#666', cursor: 'pointer', fontSize: 18 }}
                    title="Delete canvas"
                  >
                    ×
                  </button>
                </div>
              ))}
            </div>
          </>
        )}
      </main>
      {datasetOpen && <VoiceDatasetDialog onClose={() => setDatasetOpen(false)} />}
    </div>
  );
}
