import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { canvasApi } from '@/api/client';
import { useAuthStore } from '@/stores/authStore';
import type { Canvas } from '@/types';

export default function CanvasListPage() {
  const [canvases, setCanvases] = useState<Canvas[]>([]);
  const [loading, setLoading] = useState(true);
  const [newTitle, setNewTitle] = useState('');
  const { user, logout } = useAuthStore();
  const navigate = useNavigate();

  const load = async () => {
    setLoading(true);
    try {
      const res = await canvasApi.list();
      setCanvases(res.data);
    } catch {
      setCanvases([]);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, []);

  const handleCreate = async () => {
    const title = newTitle.trim() || 'Untitled Canvas';
    const res = await canvasApi.create(title);
    setNewTitle('');
    navigate(`/canvas/${res.data.id}`);
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
        <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
          <span style={{ fontSize: 13, color: '#888' }}>{user?.display_name || user?.email}</span>
          <button
            onClick={logout}
            style={{ background: '#2a2a4a', color: '#ccc', border: '1px solid #3a3a5a', borderRadius: 4, padding: '6px 14px', cursor: 'pointer', fontSize: 12 }}
          >
            Sign Out
          </button>
        </div>
      </header>

      <main style={{ maxWidth: 800, margin: '0 auto', padding: 32 }}>
        <div style={{ display: 'flex', gap: 8, marginBottom: 32 }}>
          <input
            value={newTitle}
            onChange={(e) => setNewTitle(e.target.value)}
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
              onClick={() => navigate(`/canvas/${canvas.id}`)}
            >
              <div>
                <div style={{ fontSize: 15, fontWeight: 'bold', marginBottom: 4 }}>{canvas.title}</div>
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
      </main>
    </div>
  );
}
