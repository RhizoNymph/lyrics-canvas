import { useState, useRef, useEffect, useCallback } from 'react';
import type { CSSProperties, KeyboardEvent as ReactKeyboardEvent } from 'react';
import { nlpApi } from '@/api/client';
import { useCanvasStore } from '@/stores/canvasStore';
import { useUIStore } from '@/stores/uiStore';
import { SNIPPET_TYPE_COLORS } from '@/types';
import type { SnippetType, GridData } from '@/types';
import { CELL_WIDTH, CELL_HEIGHT, PADDING, HEADER_HEIGHT, COUNT_COLUMN_WIDTH } from './gridConstants';
import GridRow, { ROW_CONTROLS_WIDTH } from './GridRow';
import {
  initEditorState,
  gridLineForRow,
  setActiveRow as setActiveRowState,
  typeLine,
  insertRow,
  deleteRow,
  moveChunkInRow,
  insertRestInRow,
  removeCellsInRow,
  reorderRows,
  mergeSyllabified,
  needsSyllabify,
  rawTextForSave,
} from './editorState';
import type { EditorRowsState } from './editorState';

const SYLLABIFY_DEBOUNCE_MS = 300;

interface CellSelection {
  row: number;
  start: number;
  end: number; // exclusive
}

/**
 * Where the editor sits. `canvas`: a fixed overlay over the snippet's card, scaled with canvas zoom and
 * the card's scale. `inline`: in normal flow inside a list row, full row width, scale 1.
 */
export type EditorPlacement =
  | {
      kind: 'canvas';
      viewport: { x: number; y: number; zoom: number };
      containerRef: React.RefObject<HTMLDivElement | null>;
    }
  | { kind: 'inline' };

interface Props {
  snippetId: string;
  placement: EditorPlacement;
}

function isTextTarget(target: EventTarget | null): boolean {
  const tag = target instanceof HTMLElement ? target.tagName : '';
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT';
}

export default function InlineGridEditor({ snippetId, placement }: Props) {
  const { snippets, updateSnippet, snippetScales } = useCanvasStore();
  const { closeEditor } = useUIStore();
  const snippet = snippets.get(snippetId);
  const snippetScale = snippetScales.get(snippetId) || 1;

  const [rows, setRows] = useState<EditorRowsState>(() =>
    initEditorState(snippet?.raw_text ?? '', snippet?.grid_data ?? null),
  );
  const { lines, activeRow } = rows;
  const joinedText = lines.join('\n');
  const [selection, setSelection] = useState<CellSelection | null>(null);
  const [snippetType, setSnippetType] = useState<SnippetType>(snippet?.snippet_type || 'freestyle');
  const [hoveredRow, setHoveredRow] = useState(-1);

  const editorRef = useRef<HTMLDivElement>(null);
  const inputRefs = useRef<Map<number, HTMLInputElement>>(new Map());
  const debounceTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const isSaving = useRef(false);
  const rowsRef = useRef(rows);
  rowsRef.current = rows;

  const setActiveRow = useCallback((row: number) => setRows((prev) => setActiveRowState(prev, row)), []);

  // Focus the active row's input
  useEffect(() => {
    if (activeRow >= 0) {
      requestAnimationFrame(() => {
        const input = inputRefs.current.get(activeRow);
        if (input) {
          input.focus();
          input.selectionStart = input.selectionEnd = input.value.length;
        }
      });
    }
  }, [activeRow]);

  // Debounced API syllabification, keyed on the text so a merged result does not
  // re-trigger it; arranged rows are never replaced (see mergeSyllabified).
  useEffect(() => {
    if (debounceTimer.current) clearTimeout(debounceTimer.current);
    if (!joinedText.trim() || !needsSyllabify(rowsRef.current)) return;
    debounceTimer.current = setTimeout(async () => {
      try {
        const res = await nlpApi.syllabify(joinedText);
        setRows((prev) => mergeSyllabified(prev, res.data as GridData));
      } catch (error) {
        // Every row already carries a local estimate; the API result would only refine it.
        console.warn('syllabify request failed; keeping local estimates', { snippetId, error });
      }
    }, SYLLABIFY_DEBOUNCE_MS);
    return () => {
      if (debounceTimer.current) clearTimeout(debounceTimer.current);
    };
  }, [joinedText, snippetId]);

  // Save & close
  const save = useCallback(async () => {
    if (isSaving.current) return;
    isSaving.current = true;
    try {
      const current = rowsRef.current;
      await updateSnippet(snippetId, {
        raw_text: rawTextForSave(current),
        grid_data: current.gridData,
        snippet_type: snippetType,
      });
    } finally {
      isSaving.current = false;
      closeEditor(snippetId);
    }
  }, [snippetType, snippetId, updateSnippet, closeEditor]);

  // Click-outside
  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (editorRef.current && !editorRef.current.contains(e.target as Node)) {
        save();
      }
    };
    const id = setTimeout(() => document.addEventListener('mousedown', handler), 100);
    return () => {
      clearTimeout(id);
      document.removeEventListener('mousedown', handler);
    };
  }, [save]);

  // ---- Row and chunk operations ----

  const updateLine = (row: number, value: string) => {
    setRows((prev) => typeLine(prev, row, value));
    setSelection((sel) => (sel && sel.row === row ? null : sel));
  };

  const selectCell = (row: number, col: number, extend: boolean) => {
    setSelection((sel) => {
      if (extend && sel && sel.row === row) {
        return { row, start: Math.min(sel.start, col), end: Math.max(sel.end, col + 1) };
      }
      return { row, start: col, end: col + 1 };
    });
    editorRef.current?.focus();
  };

  const moveChunk = (row: number, start: number, end: number, to: number) => {
    const line = gridLineForRow(rowsRef.current, row);
    if (!line) return;
    const len = end - start;
    const landing = Math.min(Math.max(to, 0), line.cells.length - len);
    setRows((prev) => moveChunkInRow(prev, row, start, end, to));
    setSelection({ row, start: landing, end: landing + len });
  };

  const nudgeChunk = (sel: CellSelection, direction: -1 | 1) => {
    moveChunk(sel.row, sel.start, sel.end, sel.start + direction);
  };

  const insertRestBefore = (sel: CellSelection) => {
    setRows((prev) => insertRestInRow(prev, sel.row, sel.start));
    setSelection({ row: sel.row, start: sel.start + 1, end: sel.end + 1 });
  };

  const removeSelectedRests = (sel: CellSelection) => {
    const line = gridLineForRow(rowsRef.current, sel.row);
    if (!line || !line.cells.slice(sel.start, sel.end).every((c) => c.rest)) return;
    setRows((prev) => removeCellsInRow(prev, sel.row, sel.start, sel.end));
    setSelection(null);
  };

  const moveRow = (row: number, direction: -1 | 1) => {
    const to = row + direction;
    if (to < 0 || to >= rowsRef.current.lines.length) return;
    setRows((prev) => reorderRows(prev, row, to));
    setSelection((sel) => (sel && sel.row === row ? { ...sel, row: to } : sel && sel.row === to ? { ...sel, row } : sel));
  };

  const activateRow = (row: number) => {
    setSelection(null);
    setActiveRow(row);
  };

  // ---- Keyboard ----

  const onInputKeyDown = (e: ReactKeyboardEvent<HTMLInputElement>, row: number) => {
    switch (e.key) {
      case 'Enter': {
        e.preventDefault();
        setSelection(null);
        setRows((prev) => insertRow(prev, row));
        break;
      }
      case 'Tab': {
        e.preventDefault();
        if (e.shiftKey) {
          if (row > 0) setActiveRow(row - 1);
        } else if (row === lines.length - 1) {
          setRows((prev) => insertRow(prev, row));
        } else {
          setActiveRow(row + 1);
        }
        break;
      }
      case 'Escape': {
        e.preventDefault();
        setActiveRow(-1);
        break;
      }
      case 'ArrowUp': {
        if (e.altKey) {
          e.preventDefault();
          moveRow(row, -1);
        } else if (row > 0) {
          e.preventDefault();
          setActiveRow(row - 1);
        }
        break;
      }
      case 'ArrowDown': {
        if (e.altKey) {
          e.preventDefault();
          moveRow(row, 1);
        } else if (row < lines.length - 1) {
          e.preventDefault();
          setActiveRow(row + 1);
        }
        break;
      }
      case 'Backspace': {
        if (lines[row] === '' && lines.length > 1) {
          e.preventDefault();
          setSelection(null);
          setRows((prev) => deleteRow(prev, row));
        }
        break;
      }
    }
  };

  const onEditorKeyDown = (e: ReactKeyboardEvent) => {
    if (isTextTarget(e.target)) return;
    if (e.key === 'Escape') {
      e.preventDefault();
      if (selection) setSelection(null);
      else if (activeRow >= 0) setActiveRow(-1);
      else save();
      return;
    }
    if (!selection) return;
    switch (e.key) {
      case 'Insert':
      case '+':
        e.preventDefault();
        insertRestBefore(selection);
        break;
      case 'Delete':
      case 'Backspace':
      case '-':
        e.preventDefault();
        removeSelectedRests(selection);
        break;
      case 'ArrowLeft':
        e.preventDefault();
        nudgeChunk(selection, -1);
        break;
      case 'ArrowRight':
        e.preventDefault();
        nudgeChunk(selection, 1);
        break;
      case 'ArrowUp':
        if (e.altKey) {
          e.preventDefault();
          moveRow(selection.row, -1);
        }
        break;
      case 'ArrowDown':
        if (e.altKey) {
          e.preventDefault();
          moveRow(selection.row, 1);
        }
        break;
    }
  };

  if (!snippet) return null;
  let s = 1;
  let frame: CSSProperties = { position: 'relative', width: '100%', boxSizing: 'border-box' };
  if (placement.kind === 'canvas') {
    const { viewport, containerRef } = placement;
    const rect = containerRef.current?.getBoundingClientRect();
    if (!rect) return null;
    s = viewport.zoom * snippetScale;
    frame = {
      position: 'fixed',
      left: (snippet.position_x + viewport.x) * viewport.zoom + rect.left,
      top: (snippet.position_y + viewport.y) * viewport.zoom + rect.top,
      zIndex: 1000,
    };
  }
  const color = SNIPPET_TYPE_COLORS[snippetType] || '#6b7280';

  const rowLines = lines.map((_, row) => gridLineForRow(rows, row));
  const cellCountPerRow = rowLines.map((line, row) => (line ? line.cells.length : 0) + (row === activeRow ? 1 : 0));
  const maxCells = Math.max(...cellCountPerRow, 4);
  const contentWidth = (maxCells * CELL_WIDTH + PADDING * 2 + COUNT_COLUMN_WIDTH + ROW_CONTROLS_WIDTH) * s;
  const sizing: CSSProperties =
    placement.kind === 'canvas'
      ? { width: Math.max(contentWidth, snippet.width * s), overflow: 'hidden' }
      : { overflowX: 'auto' };

  return (
    <div
      ref={editorRef}
      tabIndex={-1}
      onKeyDown={onEditorKeyDown}
      onClick={(e) => e.stopPropagation()}
      onDoubleClick={(e) => e.stopPropagation()}
      style={{
        ...frame,
        ...sizing,
        background: '#1c1c38',
        border: '2px solid #6366f1',
        borderRadius: 8,
        outline: 'none',
      }}
    >
      {/* Header */}
      <div
        style={{
          height: HEADER_HEIGHT * s,
          background: color,
          display: 'flex',
          alignItems: 'center',
          padding: `0 ${PADDING * s}px`,
        }}
      >
        <select
          value={snippetType}
          onChange={(e) => setSnippetType(e.target.value as SnippetType)}
          style={{
            background: 'rgba(0,0,0,0.3)',
            color: '#fff',
            border: '1px solid rgba(255,255,255,0.3)',
            borderRadius: 4,
            padding: '2px 6px',
            fontSize: 11 * s,
            fontWeight: 'bold',
            cursor: 'pointer',
            outline: 'none',
          }}
        >
          <option value="verse">VERSE</option>
          <option value="chorus">CHORUS</option>
          <option value="bridge">BRIDGE</option>
          <option value="hook">HOOK</option>
          <option value="freestyle">FREESTYLE</option>
        </select>
      </div>

      {/* Grid body (inline: wide enough for the longest row, scrolls sideways inside the row) */}
      <div style={{ padding: `${PADDING * s}px`, ...(placement.kind === 'inline' ? { minWidth: contentWidth, boxSizing: 'border-box' } : {}) }}>
        {lines.map((lineText, row) => {
          const gridLine = rowLines[row];
          const isActive = row === activeRow;
          const rowSelection = selection && selection.row === row ? { start: selection.start, end: selection.end } : null;
          const strip = gridLine ? (
            <GridRow
              cells={gridLine.cells}
              count={gridLine.syllable_count}
              scale={s}
              active={isActive}
              selection={rowSelection}
              canMoveUp={row > 0}
              canMoveDown={row < lines.length - 1}
              onSelectCell={(col, extend) => selectCell(row, col, extend)}
              onActivate={() => activateRow(row)}
              onMoveChunk={(start, end, to) => moveChunk(row, start, end, to)}
              onMoveRow={(direction) => moveRow(row, direction)}
              onInsertRest={() => rowSelection && insertRestBefore({ row, ...rowSelection })}
              onRemoveRests={() => rowSelection && removeSelectedRests({ row, ...rowSelection })}
            />
          ) : (
            <GridRow
              cells={[]}
              count={0}
              scale={s}
              active={isActive}
              selection={null}
              canMoveUp={row > 0}
              canMoveDown={row < lines.length - 1}
              onSelectCell={() => undefined}
              onActivate={() => activateRow(row)}
              onMoveChunk={() => undefined}
              onMoveRow={(direction) => moveRow(row, direction)}
              onInsertRest={() => undefined}
              onRemoveRests={() => undefined}
            />
          );

          if (isActive) {
            return (
              <div key={row}>
                {strip}
                <div style={{ height: CELL_HEIGHT * s, display: 'flex', alignItems: 'center' }}>
                  <input
                    ref={(el) => {
                      if (el) inputRefs.current.set(row, el);
                      else inputRefs.current.delete(row);
                    }}
                    type="text"
                    value={lineText}
                    onChange={(e) => updateLine(row, e.target.value)}
                    onKeyDown={(e) => onInputKeyDown(e, row)}
                    placeholder="Type lyrics..."
                    style={{
                      width: '100%',
                      height: '100%',
                      background: 'rgba(99, 102, 241, 0.08)',
                      border: '1px solid rgba(99, 102, 241, 0.25)',
                      borderRadius: 3,
                      color: '#9090b8',
                      fontFamily: 'monospace',
                      fontSize: 11 * s,
                      padding: `0 ${4 * s}px`,
                      outline: 'none',
                      boxSizing: 'border-box',
                    }}
                  />
                </div>
              </div>
            );
          }

          return (
            <div
              key={row}
              onClick={() => activateRow(row)}
              onMouseEnter={() => setHoveredRow(row)}
              onMouseLeave={() => setHoveredRow(-1)}
              style={{
                cursor: 'text',
                background:
                  hoveredRow === row
                    ? 'rgba(99, 102, 241, 0.08)'
                    : row % 2 === 0
                      ? 'rgba(36, 36, 72, 0.5)'
                      : 'transparent',
                borderRadius: 2,
              }}
            >
              {gridLine ? (
                strip
              ) : (
                <div style={{ height: CELL_HEIGHT * s, display: 'flex', alignItems: 'center', padding: `0 ${4 * s}px` }}>
                  <span style={{ fontSize: 13 * s, fontFamily: 'monospace', color: '#3a3a5a' }}>
                    {lineText.trim() || ' '}
                  </span>
                </div>
              )}
            </div>
          );
        })}
        <div style={{ paddingTop: 4 * s, fontSize: 9 * s, fontFamily: 'monospace', color: '#4a4a6a', whiteSpace: 'nowrap' }}>
          {selection
            ? 'drag or ←/→ to move · shift-click extends · +· rest · − remove rest · alt+↑/↓ move line · esc'
            : 'click a syllable to select · double-click to edit text · ▲▼ move line'}
        </div>
      </div>
    </div>
  );
}
