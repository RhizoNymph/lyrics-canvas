import { useCallback, useState, useRef, useEffect, useMemo } from 'react';
import { Container, Graphics, Text } from '@pixi/react';
import { TextStyle } from 'pixi.js';
import type { Graphics as PixiGraphics, FederatedPointerEvent } from 'pixi.js';
import { SNIPPET_TYPE_COLORS } from '@/types';
import type { Snippet } from '@/types';
import { useCanvasStore } from '@/stores/canvasStore';
import { useUIStore } from '@/stores/uiStore';
import { markSnippetInteraction } from './CanvasViewport';
import {
  CELL_HEIGHT,
  PADDING,
  HEADER_HEIGHT,
  drawBeatGrid,
  placeCellText,
  createGridTextStyles,
  styleForKind,
} from './gridDraw';

interface Props {
  snippet: Snippet;
  screenX: number;
  screenY: number;
  zoom: number;
}

const RESIZE_HANDLE_SIZE = 14;

export default function SnippetCard({ snippet, screenX, screenY, zoom }: Props) {
  const { selectSnippet, moveSnippet, selectedSnippetIds, snippetScales, setSnippetScale } = useCanvasStore();
  const { setEditingSnippet, setContextMenu } = useUIStore();
  const [dragging, setDragging] = useState(false);
  const [resizing, setResizing] = useState(false);
  const dragStart = useRef({ x: 0, y: 0, sx: 0, sy: 0 });
  const resizeStart = useRef({ x: 0, y: 0, startScale: 1 });
  const lastTap = useRef(0);
  const isSelected = selectedSnippetIds.has(snippet.id);
  const color = SNIPPET_TYPE_COLORS[snippet.snippet_type] || '#6b7280';
  const snippetScale = snippetScales.get(snippet.id) || 1;

  // Combined scale: canvas zoom * snippet's own scale
  const s = zoom * snippetScale;
  const w = snippet.width * s;
  const h = snippet.height * s;

  const drawCard = useCallback(
    (g: PixiGraphics) => {
      g.clear();

      // Shadow
      g.beginFill(0x000000, 0.25);
      g.drawRoundedRect(4, 4, w, h, 8);
      g.endFill();

      // Background
      g.beginFill(0x1c1c38);
      g.drawRoundedRect(0, 0, w, h, 8);
      g.endFill();

      // Header bar
      const headerColor = parseInt(color.replace('#', ''), 16);
      g.beginFill(headerColor, 0.85);
      g.drawRoundedRect(0, 0, w, HEADER_HEIGHT * s, 8);
      g.beginFill(headerColor, 0.85);
      g.drawRect(0, (HEADER_HEIGHT * s) / 2, w, (HEADER_HEIGHT * s) / 2);
      g.endFill();

      // Selection border
      if (isSelected) {
        g.lineStyle(2.5, 0xffffff, 0.9);
        g.drawRoundedRect(0, 0, w, h, 8);
      }

      // Tag pills in header
      g.lineStyle(0);
      const tagPillH = 16 * s;
      const tagPillGap = 4 * s;
      const tagPillPadX = 6 * s;
      const tagCharW = 6 * s; // approximate char width at fontSize 9*s
      const tagPillY = ((HEADER_HEIGHT * s) - tagPillH) / 2;
      let tagX = w - PADDING * s;
      for (let i = snippet.tags.length - 1; i >= 0; i--) {
        const tagColor = parseInt(snippet.tags[i].color.replace('#', ''), 16);
        const labelLen = snippet.tags[i].name.length;
        const pillW = tagPillPadX * 2 + labelLen * tagCharW;
        tagX -= pillW;
        g.beginFill(tagColor, 0.7);
        g.drawRoundedRect(tagX, tagPillY, pillW, tagPillH, tagPillH / 2);
        g.endFill();
        tagX -= tagPillGap;
      }

      // Beat grid (via gridDraw)
      const gridLines = snippet.grid_data?.lines || [];
      drawBeatGrid(g, gridLines, {
        s,
        left: PADDING * s,
        top: (HEADER_HEIGHT + PADDING) * s,
        maxRight: w - PADDING * s,
      });

      // Resize handle (bottom-right diagonal)
      const handleSize = RESIZE_HANDLE_SIZE * zoom; // handle scales with canvas zoom only
      const hx = w - handleSize;
      const hy = h - handleSize;
      g.lineStyle(0);
      g.beginFill(isSelected ? 0xffffff : 0x6366f1, isSelected ? 0.4 : 0.25);
      g.moveTo(w, hy);
      g.lineTo(w, h);
      g.lineTo(hx, h);
      g.closePath();
      g.endFill();
      // Diagonal lines in handle
      g.lineStyle(1, isSelected ? 0xffffff : 0x6366f1, isSelected ? 0.6 : 0.4);
      g.moveTo(w - 4 * zoom, h);
      g.lineTo(w, h - 4 * zoom);
      g.moveTo(w - 8 * zoom, h);
      g.lineTo(w, h - 8 * zoom);
    },
    [w, h, s, zoom, color, isSelected, snippet.grid_data, snippet.tags]
  );

  // Drag: global DOM listeners
  useEffect(() => {
    if (!dragging) return;
    const onMove = (e: PointerEvent) => {
      const dx = (e.clientX - dragStart.current.x) / zoom;
      const dy = (e.clientY - dragStart.current.y) / zoom;
      moveSnippet(snippet.id, dragStart.current.sx + dx, dragStart.current.sy + dy);
    };
    const onUp = () => setDragging(false);
    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp);
    return () => {
      window.removeEventListener('pointermove', onMove);
      window.removeEventListener('pointerup', onUp);
    };
  }, [dragging, zoom, snippet.id, moveSnippet]);

  // Resize: global DOM listeners
  useEffect(() => {
    if (!resizing) return;
    const onMove = (e: PointerEvent) => {
      // Diagonal distance from start (both axes contribute)
      const dx = e.clientX - resizeStart.current.x;
      const dy = e.clientY - resizeStart.current.y;
      const diag = (dx + dy) / 2; // average of both axes for diagonal feel
      const baseDim = Math.max(snippet.width, snippet.height) * zoom;
      const newScale = resizeStart.current.startScale + diag / baseDim;
      setSnippetScale(snippet.id, newScale);
    };
    const onUp = () => setResizing(false);
    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp);
    return () => {
      window.removeEventListener('pointermove', onMove);
      window.removeEventListener('pointerup', onUp);
    };
  }, [resizing, zoom, snippet.id, snippet.width, snippet.height, setSnippetScale]);

  const onPointerDown = useCallback(
    (e: FederatedPointerEvent) => {
      e.stopPropagation();
      markSnippetInteraction();
      const nativeEvent = e.nativeEvent as PointerEvent;

      // Check if click is on resize handle (bottom-right corner)
      const target = e.currentTarget as import('pixi.js').DisplayObject;
      const local = e.getLocalPosition(target);
      const cardLocalX = local.x;
      const cardLocalY = local.y;
      const handleZone = RESIZE_HANDLE_SIZE * zoom;
      if (cardLocalX > w - handleZone && cardLocalY > h - handleZone) {
        setResizing(true);
        resizeStart.current = {
          x: nativeEvent.clientX,
          y: nativeEvent.clientY,
          startScale: snippetScale,
        };
        return;
      }

      // Right-click: open context menu
      if (nativeEvent.button === 2) {
        setContextMenu({
          snippetId: snippet.id,
          x: nativeEvent.clientX,
          y: nativeEvent.clientY,
        });
        // Right-clicking a member of a multi-selection keeps it so the menu can merge the set.
        if (!useCanvasStore.getState().selectedSnippetIds.has(snippet.id)) {
          selectSnippet(snippet.id, false);
        }
        return;
      }

      // Double-click detection
      const now = Date.now();
      if (now - lastTap.current < 350) {
        setEditingSnippet(snippet.id);
        lastTap.current = 0;
        return;
      }
      lastTap.current = now;

      // Start drag
      setDragging(true);
      dragStart.current = {
        x: nativeEvent.clientX,
        y: nativeEvent.clientY,
        sx: snippet.position_x,
        sy: snippet.position_y,
      };
      selectSnippet(snippet.id, e.shiftKey);
    },
    [snippet.id, snippet.position_x, snippet.position_y, selectSnippet, setEditingSnippet, setContextMenu, snippetScale, w, h, zoom]
  );

  // Text styles that scale with both canvas zoom and snippet scale
  const headerStyle = useMemo(() => new TextStyle({
    fontSize: 11 * s,
    fill: '#ffffff',
    fontWeight: 'bold',
  }), [s]);

  const tagLabelStyle = useMemo(() => new TextStyle({
    fontSize: 9 * s,
    fill: '#ffffff',
    fontWeight: 'bold',
  }), [s]);

  // Compute tag pill positions for Text overlay
  const tagPillPositions = useMemo(() => {
    const tagPillH = 16 * s;
    const tagPillGap = 4 * s;
    const tagPillPadX = 6 * s;
    const tagCharW = 6 * s;
    const tagPillY = ((HEADER_HEIGHT * s) - tagPillH) / 2;
    const positions: Array<{ x: number; y: number }> = [];
    let tagX = w - PADDING * s;
    for (let i = snippet.tags.length - 1; i >= 0; i--) {
      const pillW = tagPillPadX * 2 + snippet.tags[i].name.length * tagCharW;
      tagX -= pillW;
      positions[i] = { x: tagX + tagPillPadX, y: tagPillY + (tagPillH - 9 * s) / 2 };
      tagX -= tagPillGap;
    }
    return positions;
  }, [s, w, snippet.tags]);

  const gridStyles = useMemo(() => createGridTextStyles(s), [s]);

  const rawTextStyle = useMemo(() => new TextStyle({
    fontSize: 13 * s,
    fill: '#a0a0c0',
    fontFamily: 'monospace',
    wordWrap: true,
    wordWrapWidth: (snippet.width - PADDING * 2) * s,
  }), [s, snippet.width]);

  const gridLines = snippet.grid_data?.lines || [];
  const gridTop = (HEADER_HEIGHT + PADDING) * s;
  const cellH = CELL_HEIGHT * s;
  const cellFrame = { s, left: PADDING * s, top: gridTop };

  return (
    <Container
      x={screenX}
      y={screenY}
      eventMode="static"
      pointerdown={onPointerDown}
      cursor={resizing ? 'nwse-resize' : 'grab'}
    >
      <Graphics draw={drawCard} />
      {/* Header label */}
      <Text
        text={snippet.snippet_type.toUpperCase()}
        style={headerStyle}
        x={PADDING * s}
        y={7 * s}
      />
      {/* Tag labels in header */}
      {snippet.tags.map((tag, i) => tagPillPositions[i] && (
        <Text
          key={`tag-${tag.id}`}
          text={tag.name}
          style={tagLabelStyle}
          x={tagPillPositions[i].x}
          y={tagPillPositions[i].y}
        />
      ))}
      {/* Grid cells */}
      {gridLines.map((line, row) =>
        line.cells.map((cell, col) => {
          const placed = placeCellText(cell, row, col, cellFrame);
          return (
            <Text
              key={`${row}-${col}`}
              text={placed.text}
              style={styleForKind(gridStyles, placed.kind)}
              x={placed.x}
              y={placed.y}
            />
          );
        })
      )}
      {/* Syllable counts */}
      {gridLines.map((line, row) => (
        <Text
          key={`count-${row}`}
          text={`${line.syllable_count}`}
          style={gridStyles.count}
          x={(snippet.width - PADDING - 14) * s}
          y={gridTop + row * cellH + 5 * s}
        />
      ))}
      {/* Fallback: show raw text if no grid data */}
      {gridLines.length === 0 && snippet.raw_text && (
        <Text
          text={snippet.raw_text.slice(0, 200)}
          style={rawTextStyle}
          x={PADDING * s}
          y={gridTop}
        />
      )}
    </Container>
  );
}
