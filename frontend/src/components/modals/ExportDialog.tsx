import { useState } from 'react';
import { exportApi } from '@/api/client';
import { useUIStore } from '@/stores/uiStore';
import { useCanvasStore } from '@/stores/canvasStore';

export default function ExportDialog() {
  const { exportDialogOpen, setExportDialogOpen } = useUIStore();
  const { canvasId, canvasTitle } = useCanvasStore();
  const [format, setFormat] = useState('markdown');
  const [loading, setLoading] = useState(false);

  const handleExport = async () => {
    if (!canvasId) return;
    setLoading(true);
    try {
      const res = await exportApi.export(canvasId, format);
      const blob = new Blob([res.data]);
      const ext = format === 'pdf' ? 'pdf' : format === 'text' ? 'txt' : 'md';
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `${canvasTitle || 'lyrics'}.${ext}`;
      a.click();
      URL.revokeObjectURL(url);
      setExportDialogOpen(false);
    } catch (err) {
      console.error('Export failed:', err);
    } finally {
      setLoading(false);
    }
  };

  if (!exportDialogOpen) return null;

  return (
    <div
      style={{
        position: 'fixed',
        inset: 0,
        background: 'rgba(0,0,0,0.6)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        zIndex: 2000,
      }}
      onClick={() => setExportDialogOpen(false)}
    >
      <div
        style={{
          width: 360,
          background: '#1e1e3a',
          borderRadius: 12,
          border: '1px solid #3a3a5a',
          padding: 24,
        }}
        onClick={(e) => e.stopPropagation()}
      >
        <h3 style={{ margin: '0 0 16px', fontSize: 16, color: '#e0e0e0' }}>Export Canvas</h3>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginBottom: 20 }}>
          {[
            { value: 'markdown', label: 'Markdown (.md)', desc: 'Formatted with headers and tags' },
            { value: 'text', label: 'Plain Text (.txt)', desc: 'Simple text, one section per snippet' },
            { value: 'pdf', label: 'PDF (.pdf)', desc: 'Printable with beat grids' },
          ].map((opt) => (
            <label
              key={opt.value}
              style={{
                display: 'flex',
                alignItems: 'flex-start',
                gap: 10,
                padding: '10px 12px',
                background: format === opt.value ? '#2a2a5a' : '#16162a',
                borderRadius: 6,
                cursor: 'pointer',
                border: `1px solid ${format === opt.value ? '#6366f1' : '#2a2a4a'}`,
              }}
            >
              <input
                type="radio"
                name="format"
                value={opt.value}
                checked={format === opt.value}
                onChange={() => setFormat(opt.value)}
                style={{ marginTop: 2 }}
              />
              <div>
                <div style={{ fontSize: 13, color: '#e0e0e0' }}>{opt.label}</div>
                <div style={{ fontSize: 11, color: '#888' }}>{opt.desc}</div>
              </div>
            </label>
          ))}
        </div>

        <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
          <button
            onClick={() => setExportDialogOpen(false)}
            style={{ background: '#3a3a5a', color: '#ccc', border: 'none', borderRadius: 6, padding: '8px 20px', cursor: 'pointer' }}
          >
            Cancel
          </button>
          <button
            onClick={handleExport}
            disabled={loading}
            style={{
              background: '#6366f1',
              color: '#fff',
              border: 'none',
              borderRadius: 6,
              padding: '8px 20px',
              cursor: 'pointer',
              opacity: loading ? 0.6 : 1,
            }}
          >
            {loading ? 'Exporting...' : 'Export'}
          </button>
        </div>
      </div>
    </div>
  );
}
