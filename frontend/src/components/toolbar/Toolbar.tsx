import { useCanvasStore } from '@/stores/canvasStore';
import { useUIStore } from '@/stores/uiStore';
import { useRhymeStore } from '@/stores/rhymeStore';
import { nlpApi, exportApi } from '@/api/client';

export default function Toolbar() {
  const { canvasTitle, canvasId, viewport, setViewport, undo, redo, tsneMode, exitTSNE, enterTSNE } = useCanvasStore();
  const { setSearchModalOpen, setExportDialogOpen, toggleRhymeSidebar, rhymeSidebarOpen } = useUIStore();
  const rhymeViewActive = useRhymeStore((s) => s.active);
  const enterRhymeView = useRhymeStore((s) => s.enter);
  const exitRhymeView = useRhymeStore((s) => s.exit);

  const handleTSNE = async () => {
    if (tsneMode) {
      exitTSNE();
      return;
    }
    if (!canvasId) return;
    try {
      const res = await nlpApi.tsne(canvasId);
      enterTSNE(res.data.positions);
    } catch (err) {
      console.error('t-SNE failed:', err);
    }
  };

  const handleZoomIn = () => setViewport({ zoom: Math.min(5, viewport.zoom * 1.2) });
  const handleZoomOut = () => setViewport({ zoom: Math.max(0.1, viewport.zoom / 1.2) });
  const handleZoomReset = () => setViewport({ zoom: 1, x: 0, y: 0 });

  const btnStyle: React.CSSProperties = {
    background: '#2a2a4a',
    color: '#ccc',
    border: '1px solid #3a3a5a',
    borderRadius: 4,
    padding: '6px 12px',
    cursor: 'pointer',
    fontSize: 12,
    whiteSpace: 'nowrap',
  };

  const activeBtnStyle: React.CSSProperties = {
    ...btnStyle,
    background: '#6366f1',
    color: '#fff',
    borderColor: '#6366f1',
  };

  return (
    <div
      style={{
        height: 48,
        background: '#16162a',
        borderBottom: '1px solid #2a2a4a',
        display: 'flex',
        alignItems: 'center',
        padding: '0 16px',
        gap: 8,
        flexShrink: 0,
      }}
    >
      <span style={{ fontWeight: 'bold', fontSize: 15, marginRight: 16, color: '#e0e0e0' }}>
        {canvasTitle || 'Lyrics Canvas'}
      </span>

      <div style={{ display: 'flex', gap: 4 }}>
        <button onClick={handleZoomOut} style={btnStyle} title="Zoom Out">−</button>
        <button onClick={handleZoomReset} style={btnStyle} title="Reset Zoom">
          {Math.round(viewport.zoom * 100)}%
        </button>
        <button onClick={handleZoomIn} style={btnStyle} title="Zoom In">+</button>
      </div>

      <div style={{ width: 1, height: 24, background: '#3a3a5a' }} />

      <button onClick={undo} style={btnStyle} title="Undo (Ctrl+Z)">Undo</button>
      <button onClick={redo} style={btnStyle} title="Redo (Ctrl+Shift+Z)">Redo</button>

      <div style={{ width: 1, height: 24, background: '#3a3a5a' }} />

      <button onClick={() => setSearchModalOpen(true)} style={btnStyle} title="Search (Ctrl+F)">
        Search
      </button>
      <button onClick={toggleRhymeSidebar} style={rhymeSidebarOpen ? activeBtnStyle : btnStyle}>
        Rhymes
      </button>
      <button onClick={handleTSNE} style={tsneMode ? activeBtnStyle : btnStyle}>
        {tsneMode ? 'Exit t-SNE' : 't-SNE'}
      </button>
      <button
        onClick={() => (rhymeViewActive ? exitRhymeView() : enterRhymeView())}
        style={rhymeViewActive ? activeBtnStyle : btnStyle}
        title="Show rhyme candidates for the selected snippet or a typed phrase"
      >
        {rhymeViewActive ? 'Exit Rhyme View' : 'Rhyme View'}
      </button>

      <div style={{ flex: 1 }} />

      <button onClick={() => setExportDialogOpen(true)} style={btnStyle}>
        Export
      </button>
    </div>
  );
}
