import { useCallback, useState, useRef } from 'react';
import { Container, Graphics, Text } from '@pixi/react';
import { TextStyle } from 'pixi.js';
import type { Graphics as PixiGraphics, FederatedPointerEvent } from 'pixi.js';
import type { Group, Snippet } from '@/types';
import { useCanvasStore } from '@/stores/canvasStore';

interface Props {
  group: Group;
  snippets: Snippet[];
  zoom: number;
  viewportX: number;
  viewportY: number;
}

export default function GroupContainer({ group, snippets, zoom, viewportX, viewportY }: Props) {
  const { moveGroup } = useCanvasStore();
  const [dragging, setDragging] = useState(false);
  const dragStart = useRef({ x: 0, y: 0 });

  // Compute bounding box
  const minX = Math.min(...snippets.map((s) => s.position_x), group.position_x);
  const minY = Math.min(...snippets.map((s) => s.position_y), group.position_y);
  const maxX = Math.max(...snippets.map((s) => s.position_x + s.width), minX + 100);
  const maxY = Math.max(...snippets.map((s) => s.position_y + s.height), minY + 50);

  const padding = 20;
  const screenX = (minX + viewportX - padding) * zoom;
  const screenY = (minY + viewportY - padding) * zoom;
  const w = (maxX - minX + padding * 2) * zoom;
  const h = (maxY - minY + padding * 2 + 24) * zoom;

  const draw = useCallback(
    (g: PixiGraphics) => {
      g.clear();
      g.lineStyle(2, 0x6366f1, 0.5);
      g.beginFill(0x6366f1, 0.05);
      g.drawRoundedRect(0, 0, w, h, 12);
      g.endFill();
    },
    [w, h]
  );

  const onPointerDown = useCallback(
    (e: FederatedPointerEvent) => {
      e.stopPropagation();
      setDragging(true);
      dragStart.current = { x: e.globalX, y: e.globalY };
    },
    []
  );

  const onPointerMove = useCallback(
    (e: FederatedPointerEvent) => {
      if (!dragging) return;
      const dx = (e.globalX - dragStart.current.x) / zoom;
      const dy = (e.globalY - dragStart.current.y) / zoom;
      dragStart.current = { x: e.globalX, y: e.globalY };
      moveGroup(group.id, dx, dy);
    },
    [dragging, zoom, group.id, moveGroup]
  );

  const onPointerUp = useCallback(() => {
    setDragging(false);
  }, []);

  const labelStyle = new TextStyle({
    fontSize: 11 * zoom,
    fill: '#6366f1',
    fontWeight: 'bold',
  });

  return (
    <Container
      x={screenX}
      y={screenY}
      eventMode="static"
      pointerdown={onPointerDown}
      pointermove={onPointerMove}
      pointerup={onPointerUp}
      pointerupoutside={onPointerUp}
    >
      <Graphics draw={draw} />
      <Text text={group.name} style={labelStyle} x={12 * zoom} y={4 * zoom} />
    </Container>
  );
}
