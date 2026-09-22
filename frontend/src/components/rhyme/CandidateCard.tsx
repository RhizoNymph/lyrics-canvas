import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Container, Graphics, Text } from '@pixi/react';
import { TextStyle } from 'pixi.js';
import type { Graphics as PixiGraphics, FederatedPointerEvent } from 'pixi.js';
import type { RhymeCandidate } from '@/types/rhyme';
import type { Point } from '@/lib/rhyme/radialLayout';
import { candidateCardSize, candidateTopLeft } from '@/lib/rhyme/candidateGeometry';
import { useRhymeStore } from '@/stores/rhymeStore';
import { markSnippetInteraction } from '@/components/canvas/CanvasViewport';
import { PADDING, HEADER_HEIGHT, drawBeatGrid, placeCellText, createGridTextStyles, styleForKind } from '@/components/canvas/gridDraw';
import { drawDashedRoundedRect } from './dashedRect';
import { PROVENANCE_COLORS, PROVENANCE_LABELS, NON_FIT_ALPHA, hexToNumber } from './rhymeColors';

interface Props {
  candidate: RhymeCandidate;
  /** World-coordinate card centre from the radial layout. */
  center: Point;
  viewport: { x: number; y: number; zoom: number };
}

/** Screen-pixel movement below which a pointer down/up counts as a click. */
const CLICK_TOLERANCE_PX = 4;
/** World-unit displacement from the original spot needed to promote on drop. */
const PROMOTE_DRAG_THRESHOLD = 24;
/** Delay before hover clears so the pointer can reach the tooltip. */
const HOVER_OUT_DELAY_MS = 250;

export default function CandidateCard({ candidate, center, viewport }: Props) {
  const { setHovered, setPinned, promote, hoveredId, pinnedId } = useRhymeStore();
  const [dragOffset, setDragOffset] = useState<Point>({ x: 0, y: 0 });
  const [dragging, setDragging] = useState(false);
  const dragStart = useRef({ x: 0, y: 0 });
  const offsetRef = useRef<Point>({ x: 0, y: 0 });
  const hoverTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const zoom = viewport.zoom;
  const s = zoom;
  const size = useMemo(() => candidateCardSize(candidate), [candidate]);
  const topLeft = useMemo(() => candidateTopLeft(center, size), [center, size]);
  const worldX = topLeft.x + dragOffset.x;
  const worldY = topLeft.y + dragOffset.y;
  const screenX = (worldX + viewport.x) * zoom;
  const screenY = (worldY + viewport.y) * zoom;
  const w = size.width * s;
  const h = size.height * s;

  const headerHex = PROVENANCE_COLORS[candidate.provenance];
  const headerColor = hexToNumber(headerHex);
  const highlighted = hoveredId === candidate.id || pinnedId === candidate.id;

  const draw = useCallback(
    (g: PixiGraphics) => {
      g.clear();

      g.beginFill(0x000000, 0.2);
      g.drawRoundedRect(3, 3, w, h, 8);
      g.endFill();

      g.beginFill(0x1c1c38, 0.92);
      g.drawRoundedRect(0, 0, w, h, 8);
      g.endFill();

      g.beginFill(headerColor, 0.8);
      g.drawRoundedRect(0, 0, w, HEADER_HEIGHT * s, 8);
      g.drawRect(0, (HEADER_HEIGHT * s) / 2, w, (HEADER_HEIGHT * s) / 2);
      g.endFill();

      drawBeatGrid(g, [candidate.grid_line], {
        s,
        left: PADDING * s,
        top: (HEADER_HEIGHT + PADDING) * s,
        maxRight: w - PADDING * s,
      });

      if (candidate.ephemeral) {
        g.lineStyle(1.5, highlighted ? 0xffffff : headerColor, highlighted ? 0.9 : 0.75);
        drawDashedRoundedRect(g, 0, 0, w, h, 8, 6 * zoom, 4 * zoom);
      } else if (highlighted) {
        g.lineStyle(2, 0xffffff, 0.9);
        g.drawRoundedRect(0, 0, w, h, 8);
      }
      g.lineStyle(0);
    },
    [w, h, s, zoom, headerColor, highlighted, candidate.ephemeral, candidate.grid_line]
  );

  useEffect(() => {
    if (!dragging) return;
    const onMove = (e: PointerEvent) => {
      const next = { x: (e.clientX - dragStart.current.x) / zoom, y: (e.clientY - dragStart.current.y) / zoom };
      offsetRef.current = next;
      setDragOffset(next);
    };
    const onUp = () => {
      setDragging(false);
      const off = offsetRef.current;
      const movedPx = Math.max(Math.abs(off.x), Math.abs(off.y)) * zoom;
      offsetRef.current = { x: 0, y: 0 };
      setDragOffset({ x: 0, y: 0 });
      if (movedPx < CLICK_TOLERANCE_PX) {
        const { pinnedId: current } = useRhymeStore.getState();
        setPinned(current === candidate.id ? null : candidate.id);
        return;
      }
      if (Math.hypot(off.x, off.y) >= PROMOTE_DRAG_THRESHOLD) {
        void promote(candidate.id, { x: topLeft.x + off.x, y: topLeft.y + off.y });
      }
    };
    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp);
    return () => {
      window.removeEventListener('pointermove', onMove);
      window.removeEventListener('pointerup', onUp);
    };
  }, [dragging, zoom, candidate.id, topLeft, promote, setPinned]);

  useEffect(
    () => () => {
      if (hoverTimer.current) clearTimeout(hoverTimer.current);
    },
    []
  );

  const onPointerDown = useCallback(
    (e: FederatedPointerEvent) => {
      e.stopPropagation();
      markSnippetInteraction();
      const native = e.nativeEvent as PointerEvent;
      if (native.button !== 0) return;
      dragStart.current = { x: native.clientX, y: native.clientY };
      offsetRef.current = { x: 0, y: 0 };
      setDragging(true);
    },
    []
  );

  const onPointerOver = useCallback(() => {
    if (hoverTimer.current) {
      clearTimeout(hoverTimer.current);
      hoverTimer.current = null;
    }
    setHovered(candidate.id);
  }, [candidate.id, setHovered]);

  const onPointerOut = useCallback(() => {
    if (hoverTimer.current) clearTimeout(hoverTimer.current);
    hoverTimer.current = setTimeout(() => {
      hoverTimer.current = null;
      if (useRhymeStore.getState().hoveredId === candidate.id) setHovered(null);
    }, HOVER_OUT_DELAY_MS);
  }, [candidate.id, setHovered]);

  const headerStyle = useMemo(() => new TextStyle({ fontSize: 10 * s, fill: '#ffffff', fontWeight: 'bold' }), [s]);
  const scoreStyle = useMemo(
    () => new TextStyle({ fontSize: 10 * s, fill: '#ffffff', fontFamily: 'monospace' }),
    [s]
  );
  const gridStyles = useMemo(() => createGridTextStyles(s), [s]);
  const cellFrame = { s, left: PADDING * s, top: (HEADER_HEIGHT + PADDING) * s };
  const scoreText = candidate.score.toFixed(2);

  return (
    <Container
      x={screenX}
      y={screenY}
      alpha={candidate.fits_slot ? 1 : NON_FIT_ALPHA}
      eventMode="static"
      cursor={dragging ? 'grabbing' : 'grab'}
      pointerdown={onPointerDown}
      pointerover={onPointerOver}
      pointerout={onPointerOut}
    >
      <Graphics draw={draw} />
      <Text text={PROVENANCE_LABELS[candidate.provenance]} style={headerStyle} x={PADDING * s} y={9 * s} />
      <Text text={scoreText} style={scoreStyle} x={w - PADDING * s - scoreText.length * 6.2 * s} y={9 * s} />
      {candidate.grid_line.cells.map((cell, col) => {
        const placed = placeCellText(cell, 0, col, cellFrame);
        return <Text key={col} text={placed.text} style={styleForKind(gridStyles, placed.kind)} x={placed.x} y={placed.y} />;
      })}
      <Text
        text={`${candidate.grid_line.syllable_count}`}
        style={gridStyles.count}
        x={(size.width - PADDING - 14) * s}
        y={(HEADER_HEIGHT + PADDING) * s + 5 * s}
      />
    </Container>
  );
}
