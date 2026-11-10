import { useEffect, useMemo, useRef, useState } from 'react';
import { placementApi } from '@/api/client';
import { createCanvasFromInput, describePlacementError, pickerCanvases, titleEditKey, toPlacementError } from '@/lib/placements';
import type { Canvas, CanvasRef, TransferMode } from '@/types';

interface Props {
  currentCanvasId: string;
  mode: TransferMode;
  /** How many snippets the action applies to (for the heading). */
  count: number;
  onPick: (target: CanvasRef) => void;
  onBack: () => void;
}

type ListState =
  | { kind: 'loading' }
  | { kind: 'loaded'; canvases: Canvas[] }
  | { kind: 'failed'; message: string };

type NewCanvasState = { kind: 'closed' } | { kind: 'open'; title: string; error: string | null; saving: boolean };

const rowStyle = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  padding: '6px 12px',
  cursor: 'pointer',
  background: 'transparent',
  border: 'none',
  color: 'inherit',
  width: '100%',
  textAlign: 'left',
  fontSize: 13,
} as const;

const inputStyle = {
  width: '100%',
  boxSizing: 'border-box',
  background: '#12122a',
  color: '#e0e0e0',
  border: '1px solid #3a3a5c',
  borderRadius: 4,
  padding: '5px 8px',
  fontSize: 12,
  outline: 'none',
} as const;

/**
 * The context menu's "Move to canvas…" / "Also show on canvas…" picker: a searchable list of the user's other
 * canvases, newest updated first, and "New canvas…" at the top, which creates a canvas and picks it.
 */
export default function CanvasPicker({ currentCanvasId, mode, count, onPick, onBack }: Props) {
  const [list, setList] = useState<ListState>({ kind: 'loading' });
  const [query, setQuery] = useState('');
  const [creating, setCreating] = useState<NewCanvasState>({ kind: 'closed' });
  const searchRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    let live = true;
    placementApi.listCanvases().then(
      (canvases) => live && setList({ kind: 'loaded', canvases }),
      (err: unknown) => live && setList({ kind: 'failed', message: describePlacementError(toPlacementError(err, 'list_canvases')) }),
    );
    searchRef.current?.focus();
    return () => {
      live = false;
    };
  }, []);

  const shown = useMemo(
    () => (list.kind === 'loaded' ? pickerCanvases(list.canvases, currentCanvasId, query) : []),
    [list, currentCanvasId, query],
  );

  const createAndPick = async () => {
    if (creating.kind !== 'open' || creating.saving) return;
    setCreating({ ...creating, saving: true, error: null });
    const result = await createCanvasFromInput(creating.title, placementApi.createCanvas);
    if (!result.ok) {
      setCreating({ ...creating, saving: false, error: describePlacementError(result.error) });
      return;
    }
    onPick({ id: result.value.id, title: result.value.title });
  };

  const verb = mode === 'move' ? 'Move' : 'Also show';
  const what = `${count} snippet${count === 1 ? '' : 's'}`;

  return (
    <div role="dialog" aria-label={`${verb} ${what} on canvas`} style={{ padding: '4px 0' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '4px 12px 6px' }}>
        <button type="button" onClick={onBack} title="Back" style={{ ...rowStyle, width: 'auto', padding: '0 4px' }}>
          ‹
        </button>
        <span style={{ fontSize: 12, color: '#a0a0c0' }}>
          {verb} {what} {mode === 'move' ? 'to' : 'on'}…
        </span>
      </div>
      <div style={{ padding: '0 12px 6px' }}>
        <input
          ref={searchRef}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search canvases"
          aria-label="Search canvases"
          style={inputStyle}
        />
      </div>

      {creating.kind === 'closed' ? (
        <button type="button" style={{ ...rowStyle, color: '#a5b4fc' }} onClick={() => setCreating({ kind: 'open', title: query.trim(), error: null, saving: false })}>
          <span style={{ width: 16, textAlign: 'center' }}>+</span>
          <span>New canvas…</span>
        </button>
      ) : (
        <div style={{ padding: '4px 12px 6px' }}>
          <input
            autoFocus
            value={creating.title}
            disabled={creating.saving}
            placeholder="New canvas title (Enter to create)"
            aria-label="New canvas title"
            aria-invalid={creating.error !== null}
            onChange={(e) => setCreating({ ...creating, title: e.target.value, error: null })}
            onKeyDown={(e) => {
              const action = titleEditKey(e.key);
              if (action === null) return;
              e.preventDefault();
              e.stopPropagation();
              if (action === 'save') void createAndPick();
              else setCreating({ kind: 'closed' });
            }}
            style={{ ...inputStyle, borderColor: creating.error ? '#f87171' : '#6366f1' }}
          />
          {creating.error !== null && (
            <div role="alert" style={{ color: '#fca5a5', fontSize: 11, marginTop: 3 }}>
              {creating.error}
            </div>
          )}
        </div>
      )}

      <div style={{ height: 1, background: '#3a3a5c', margin: '4px 0' }} />
      <div style={{ maxHeight: 260, overflowY: 'auto' }}>
        {list.kind === 'loading' && <div style={{ padding: '6px 12px', color: '#6a6a8a' }}>Loading…</div>}
        {list.kind === 'failed' && (
          <div role="alert" style={{ padding: '6px 12px', color: '#fca5a5' }}>
            {list.message}
          </div>
        )}
        {list.kind === 'loaded' && shown.length === 0 && (
          <div style={{ padding: '6px 12px', color: '#6a6a8a' }}>{query.trim() ? 'No canvas matches' : 'No other canvases yet'}</div>
        )}
        {shown.map((c) => (
          <button
            key={c.id}
            type="button"
            style={rowStyle}
            onClick={() => onPick({ id: c.id, title: c.title })}
            onMouseEnter={(e) => (e.currentTarget.style.background = 'rgba(99, 102, 241, 0.15)')}
            onMouseLeave={(e) => (e.currentTarget.style.background = 'transparent')}
          >
            <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{c.title}</span>
          </button>
        ))}
      </div>
    </div>
  );
}
