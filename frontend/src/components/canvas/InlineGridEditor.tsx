import { useState, useRef, useEffect, useCallback, useMemo } from 'react';
import { nlpApi } from '@/api/client';
import { useCanvasStore } from '@/stores/canvasStore';
import { useUIStore } from '@/stores/uiStore';
import { SNIPPET_TYPE_COLORS } from '@/types';
import type { SnippetType, GridData, GridLine, GridCell } from '@/types';

const CELL_WIDTH = 60;
const CELL_HEIGHT = 24;
const PADDING = 14;
const HEADER_HEIGHT = 30;

// ---------------------------------------------------------------------------
// Local syllable estimation for instant visual feedback while typing.
// The API result replaces this for inactive rows & on save.
// ---------------------------------------------------------------------------

/** Count vowel-groups in a word as a rough syllable estimate. */
function estimateSyllableCount(word: string): number {
  const clean = word.toLowerCase().replace(/[^a-z]/g, '');
  if (!clean) return 1;
  const groups = clean.match(/[aeiouy]+/g);
  if (!groups) return 1;
  let count = groups.length;
  // Silent-e heuristic
  if (clean.length > 2 && clean.endsWith('e') && count > 1) count--;
  return Math.max(count, 1);
}

/**
 * Split a word into roughly `n` visual chunks where n = estimated syllable count.
 * Not linguistically perfect — just enough for a live expanding grid.
 */
function splitWord(word: string): string[] {
  const n = estimateSyllableCount(word);
  if (n <= 1) return [word];
  const partLen = Math.ceil(word.length / n);
  const parts: string[] = [];
  for (let i = 0; i < n; i++) {
    const start = i * partLen;
    const end = i === n - 1 ? word.length : (i + 1) * partLen;
    parts.push(word.slice(start, end));
  }
  return parts;
}

/** Instant local syllabification for a single line of text. */
function localSyllabifyLine(text: string): { cells: GridCell[]; count: number } {
  if (!text.trim()) return { cells: [], count: 0 };
  const words = text.trim().split(/\s+/);
  const cells: GridCell[] = [];
  let total = 0;
  for (let wi = 0; wi < words.length; wi++) {
    for (const syl of splitWord(words[wi])) {
      cells.push({ syllable: syl, word_index: wi });
      total++;
    }
  }
  return { cells, count: total };
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

interface Props {
  snippetId: string;
  viewport: { x: number; y: number; zoom: number };
  containerRef: React.RefObject<HTMLDivElement | null>;
}

export default function InlineGridEditor({ snippetId, viewport, containerRef }: Props) {
  const { snippets, updateSnippet, snippetScales } = useCanvasStore();
  const { setEditingSnippet } = useUIStore();
  const snippet = snippets.get(snippetId);
  const snippetScale = snippetScales.get(snippetId) || 1;

  const [lines, setLines] = useState<string[]>(() => {
    if (!snippet) return [''];
    if (snippet.raw_text) return snippet.raw_text.split('\n');
    return [''];
  });
  const [gridData, setGridData] = useState<GridData | null>(snippet?.grid_data || null);
  const [activeRow, setActiveRow] = useState<number>(() => {
    if (!snippet || !snippet.raw_text) return 0;
    return -1;
  });
  const [snippetType, setSnippetType] = useState<SnippetType>(snippet?.snippet_type || 'freestyle');
  const [hoveredRow, setHoveredRow] = useState(-1);

  const editorRef = useRef<HTMLDivElement>(null);
  const inputRefs = useRef<Map<number, HTMLInputElement>>(new Map());
  const debounceTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const isSaving = useRef(false);

  // Focus the active row's input
  useEffect(() => {
    if (activeRow >= 0) {
      // Small delay so the DOM element is mounted
      requestAnimationFrame(() => {
        const input = inputRefs.current.get(activeRow);
        if (input) {
          input.focus();
          input.selectionStart = input.selectionEnd = input.value.length;
        }
      });
    }
  }, [activeRow]);

  // Instant local syllable estimate for the active row
  const activeRowLocal = useMemo(() => {
    if (activeRow < 0 || activeRow >= lines.length) return { cells: [], count: 0 };
    return localSyllabifyLine(lines[activeRow]);
  }, [activeRow, lines]);

  // Debounced API syllabification (updates gridData for all rows)
  const syllabify = useCallback((currentLines: string[]) => {
    if (debounceTimer.current) clearTimeout(debounceTimer.current);
    debounceTimer.current = setTimeout(async () => {
      const text = currentLines.join('\n');
      if (!text.trim()) {
        setGridData(null);
        return;
      }
      try {
        const res = await nlpApi.syllabify(text);
        setGridData(res.data as GridData);
      } catch {
        // Fallback: build grid from local estimates
        const fallbackLines: GridLine[] = currentLines
          .filter((l) => l.trim())
          .map((line) => {
            const { cells, count } = localSyllabifyLine(line);
            return { cells, syllable_count: count, raw_text: line };
          });
        setGridData({ lines: fallbackLines });
      }
    }, 300);
  }, []);

  useEffect(() => {
    syllabify(lines);
    return () => {
      if (debounceTimer.current) clearTimeout(debounceTimer.current);
    };
  }, [lines, syllabify]);

  // Mapping: local line index → gridData.lines index (null for blank lines)
  const lineGridMapping = useMemo(() => {
    const mapping: (number | null)[] = [];
    let gridIdx = 0;
    const gl = gridData?.lines || [];
    for (let i = 0; i < lines.length; i++) {
      if (lines[i].trim() && gridIdx < gl.length) {
        mapping.push(gridIdx);
        gridIdx++;
      } else {
        mapping.push(null);
      }
    }
    return mapping;
  }, [lines, gridData]);

  // Save & close
  const save = useCallback(async () => {
    if (isSaving.current) return;
    isSaving.current = true;
    try {
      const raw_text = lines.join('\n');
      await updateSnippet(snippetId, {
        raw_text,
        grid_data: gridData,
        snippet_type: snippetType,
      } as any);
    } finally {
      isSaving.current = false;
      setEditingSnippet(null);
    }
  }, [lines, gridData, snippetType, snippetId, updateSnippet, setEditingSnippet]);

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

  const updateLine = (row: number, value: string) => {
    setLines((prev) => {
      const next = [...prev];
      next[row] = value;
      return next;
    });
  };

  // Keyboard navigation
  const onInputKeyDown = (e: React.KeyboardEvent<HTMLInputElement>, row: number) => {
    switch (e.key) {
      case 'Enter': {
        e.preventDefault();
        setLines((prev) => {
          const next = [...prev];
          next.splice(row + 1, 0, '');
          return next;
        });
        setActiveRow(row + 1);
        break;
      }
      case 'Tab': {
        e.preventDefault();
        if (e.shiftKey) {
          if (row > 0) setActiveRow(row - 1);
        } else {
          if (row === lines.length - 1) setLines((prev) => [...prev, '']);
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
        if (row > 0) { e.preventDefault(); setActiveRow(row - 1); }
        break;
      }
      case 'ArrowDown': {
        if (row < lines.length - 1) { e.preventDefault(); setActiveRow(row + 1); }
        break;
      }
      case 'Backspace': {
        if (lines[row] === '' && lines.length > 1) {
          e.preventDefault();
          setLines((prev) => { const n = [...prev]; n.splice(row, 1); return n; });
          setActiveRow(Math.max(0, row - 1));
        }
        break;
      }
    }
  };

  const onEditorKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Escape' && activeRow === -1) { e.preventDefault(); save(); }
  };

  if (!snippet) return null;
  const rect = containerRef.current?.getBoundingClientRect();
  if (!rect) return null;

  const s = viewport.zoom * snippetScale;
  const left = (snippet.position_x + viewport.x) * viewport.zoom + rect.left;
  const top = (snippet.position_y + viewport.y) * viewport.zoom + rect.top;

  const gridLines = gridData?.lines || [];
  const color = SNIPPET_TYPE_COLORS[snippetType] || '#6b7280';

  // For the active row, prefer API data when it matches the current text
  const activeRowGridIdx = activeRow >= 0 ? lineGridMapping[activeRow] : null;
  const activeRowApiLine = activeRowGridIdx !== null ? gridLines[activeRowGridIdx] : null;
  const activeRowApiFresh = activeRowApiLine && activeRowApiLine.raw_text.trim() === lines[activeRow]?.trim();
  const activeRowCells = activeRowApiFresh ? activeRowApiLine!.cells : activeRowLocal.cells;
  const activeRowCount = activeRowApiFresh ? activeRowApiLine!.syllable_count : activeRowLocal.count;

  // Compute max cell count across all rows (for consistent grid width)
  const cellCountPerRow = lines.map((_, row) => {
    if (row === activeRow) return activeRowCells.length + 1; // +1 empty
    const gi = lineGridMapping[row];
    if (gi !== null && gridLines[gi]) return gridLines[gi].cells.length;
    return 0;
  });
  const maxCells = Math.max(...cellCountPerRow, 4);
  const editorWidth = Math.max((maxCells * CELL_WIDTH + PADDING * 2) * s, snippet.width * s);

  // ---- Shared cell renderer ----
  const renderCellRow = (cells: GridCell[], count: number, extra: boolean, highlight: boolean) => (
    <div style={{ display: 'flex', alignItems: 'center', height: CELL_HEIGHT * s, minWidth: 0 }}>
      {cells.map((cell, col) => (
        <span
          key={col}
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            width: CELL_WIDTH * s,
            height: CELL_HEIGHT * s,
            fontSize: 13 * s,
            fontFamily: 'monospace',
            color: cell.rest
              ? '#555577'
              : cell.stress && cell.stress > 0 ? '#ffffff' : '#d0d0e8',
            fontWeight: cell.stress && cell.stress > 0 ? 'bold' : 'normal',
            padding: `0 ${4 * s}px`,
            boxSizing: 'border-box',
            borderRight: '1px solid rgba(74, 74, 106, 0.35)',
            borderBottom: '1px solid rgba(74, 74, 106, 0.18)',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
            background: highlight ? 'rgba(99, 102, 241, 0.06)' : 'transparent',
            flexShrink: 0,
          }}
        >
          {cell.rest ? '·' : cell.syllable || ''}
        </span>
      ))}
      {/* +1 empty cell for the active row */}
      {extra && (
        <span
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            width: CELL_WIDTH * s,
            height: CELL_HEIGHT * s,
            borderRight: '1px solid rgba(74, 74, 106, 0.2)',
            borderBottom: '1px solid rgba(74, 74, 106, 0.18)',
            background: 'rgba(99, 102, 241, 0.04)',
            flexShrink: 0,
          }}
        />
      )}
      {/* Syllable count badge */}
      <span
        style={{
          fontSize: 10 * s,
          color: highlight ? '#a0a0ff' : '#7a7a9a',
          fontFamily: 'monospace',
          fontWeight: highlight ? 'bold' : 'normal',
          marginLeft: 'auto',
          paddingLeft: 8 * s,
          paddingRight: 4 * s,
          flexShrink: 0,
          minWidth: 20 * s,
          textAlign: 'right',
        }}
      >
        {count || ''}
      </span>
    </div>
  );

  return (
    <div
      ref={editorRef}
      tabIndex={-1}
      onKeyDown={onEditorKeyDown}
      onClick={(e) => e.stopPropagation()}
      onDoubleClick={(e) => e.stopPropagation()}
      style={{
        position: 'fixed',
        left,
        top,
        width: editorWidth,
        background: '#1c1c38',
        border: '2px solid #6366f1',
        borderRadius: 8,
        zIndex: 1000,
        overflow: 'hidden',
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

      {/* Grid body */}
      <div style={{ padding: `${PADDING * s}px` }}>
        {lines.map((lineText, row) => {
          const gridIdx = lineGridMapping[row];
          const gridLine = gridIdx !== null ? gridLines[gridIdx] : null;

          if (row === activeRow) {
            // ---- ACTIVE ROW: live grid cells + compact input below ----
            return (
              <div key={row}>
                {/* Syllable cells (API when fresh, local estimate as fallback) */}
                {renderCellRow(activeRowCells, activeRowCount, true, true)}

                {/* Text input for typing */}
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

          // ---- INACTIVE ROW: API grid cells (or placeholder) ----
          return (
            <div
              key={row}
              onClick={() => setActiveRow(row)}
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
              {gridLine && gridLine.cells.length > 0 ? (
                renderCellRow(gridLine.cells, gridLine.syllable_count, false, false)
              ) : (
                <div
                  style={{
                    height: CELL_HEIGHT * s,
                    display: 'flex',
                    alignItems: 'center',
                    padding: `0 ${4 * s}px`,
                  }}
                >
                  <span style={{ fontSize: 13 * s, fontFamily: 'monospace', color: '#3a3a5a' }}>
                    {lineText.trim() || '\u00A0'}
                  </span>
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
