import { useCallback, useRef, useState, useEffect } from 'react';
import { Stage, Container } from '@pixi/react';
import InfiniteGrid from './InfiniteGrid';
import SnippetCard from './SnippetCard';
import GroupContainer from './GroupContainer';
import InlineGridEditor from './InlineGridEditor';
import SnippetContextMenu from './SnippetContextMenu';
import { useCanvasStore } from '@/stores/canvasStore';
import { useUIStore } from '@/stores/uiStore';

// Module-level flag: SnippetCard sets this in pointerdown so onStageClick
// knows a snippet was interacted with (Pixi stopPropagation doesn't reach DOM).
let _snippetInteracted = false;
export function markSnippetInteraction() { _snippetInteracted = true; }

export default function CanvasViewport() {
  const containerRef = useRef<HTMLDivElement>(null);
  const [size, setSize] = useState({ width: 800, height: 600 });
  const { viewport, setViewport, snippets, groups, activeTagFilters, addSnippet, clearSelection, commitPositions } =
    useCanvasStore();
  const { editingSnippetId, setEditingSnippet } = useUIStore();

  const isPanning = useRef(false);
  const panStart = useRef({ x: 0, y: 0, vx: 0, vy: 0 });
  const lastClickTime = useRef(0);
  const lastClickPos = useRef({ x: 0, y: 0 });

  useEffect(() => {
    const updateSize = () => {
      if (containerRef.current) {
        setSize({
          width: containerRef.current.clientWidth,
          height: containerRef.current.clientHeight,
        });
      }
    };
    updateSize();
    window.addEventListener('resize', updateSize);
    return () => window.removeEventListener('resize', updateSize);
  }, []);

  const onWheel = useCallback(
    (e: React.WheelEvent) => {
      e.preventDefault();
      if (e.ctrlKey || e.metaKey) {
        const delta = e.deltaY > 0 ? 0.9 : 1.1;
        const newZoom = Math.max(0.1, Math.min(5, viewport.zoom * delta));
        setViewport({ zoom: newZoom });
      } else {
        setViewport({
          x: viewport.x - e.deltaX / viewport.zoom,
          y: viewport.y - e.deltaY / viewport.zoom,
        });
      }
    },
    [viewport, setViewport]
  );

  const onMouseDown = useCallback(
    (e: React.MouseEvent) => {
      if (e.button === 1 || (e.button === 0 && e.altKey)) {
        isPanning.current = true;
        panStart.current = { x: e.clientX, y: e.clientY, vx: viewport.x, vy: viewport.y };
        return;
      }

      // Double-click detection for adding snippets (only on empty canvas area).
      // Use a short delay so Pixi snippet card pointerdown can set editing first.
      if (e.button === 0) {
        const now = Date.now();
        const dx = Math.abs(e.clientX - lastClickPos.current.x);
        const dy = Math.abs(e.clientY - lastClickPos.current.y);
        if (now - lastClickTime.current < 400 && dx < 10 && dy < 10) {
          lastClickTime.current = 0;
          // Delay to let Pixi events settle — if a snippet was clicked, editing will be set
          setTimeout(() => {
            const editing = useUIStore.getState().editingSnippetId;
            if (editing) return; // User double-clicked a snippet, not empty canvas
            const rect = containerRef.current?.getBoundingClientRect();
            if (rect) {
              const cx = (e.clientX - rect.left) / viewport.zoom - viewport.x;
              const cy = (e.clientY - rect.top) / viewport.zoom - viewport.y;
              addSnippet(cx, cy);
            }
          }, 60);
        } else {
          lastClickTime.current = now;
          lastClickPos.current = { x: e.clientX, y: e.clientY };
        }
      }
    },
    [viewport, editingSnippetId, addSnippet]
  );

  const onMouseMove = useCallback(
    (e: React.MouseEvent) => {
      if (!isPanning.current) return;
      const dx = (e.clientX - panStart.current.x) / viewport.zoom;
      const dy = (e.clientY - panStart.current.y) / viewport.zoom;
      setViewport({ x: panStart.current.vx + dx, y: panStart.current.vy + dy });
    },
    [viewport.zoom, setViewport]
  );

  const onMouseUp = useCallback(() => {
    if (isPanning.current) {
      isPanning.current = false;
    } else {
      commitPositions();
    }
  }, [commitPositions]);

  // Only clear selection when clicking the Stage background (not a snippet).
  // Pixi's stopPropagation only works within the scene graph, not the DOM,
  // so we use a module-level flag set by SnippetCard to detect snippet clicks.
  const stageClickTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const onStageClick = useCallback(() => {
    if (stageClickTimer.current) clearTimeout(stageClickTimer.current);
    stageClickTimer.current = setTimeout(() => {
      if (_snippetInteracted) {
        _snippetInteracted = false;
        return;
      }
      const currentEditing = useUIStore.getState().editingSnippetId;
      if (!currentEditing) {
        clearSelection();
      }
    }, 50);
  }, [clearSelection]);

  // Filter snippets by active tags
  const visibleSnippets = Array.from(snippets.values()).filter((s) => {
    if (activeTagFilters.size === 0) return true;
    return s.tags.some((t) => activeTagFilters.has(t.id));
  });

  const groupArray = Array.from(groups.values());

  return (
    <div
      ref={containerRef}
      style={{ flex: 1, overflow: 'hidden', cursor: isPanning.current ? 'grabbing' : 'default' }}
      onContextMenu={(e) => e.preventDefault()}
      onWheel={onWheel}
      onMouseDown={onMouseDown}
      onMouseMove={onMouseMove}
      onMouseUp={onMouseUp}
    >
      <Stage
        width={size.width}
        height={size.height}
        options={{ background: 0x12122a, antialias: true }}
        onPointerDown={onStageClick}
      >
        <Container>
          <InfiniteGrid viewport={viewport} width={size.width} height={size.height} />

          {/* Group bounding boxes (behind snippets) */}
          {groupArray.map((group) => {
            const groupSnippets = visibleSnippets.filter((s) => s.group_id === group.id);
            if (groupSnippets.length === 0) return null;
            return (
              <GroupContainer
                key={group.id}
                group={group}
                snippets={groupSnippets}
                zoom={viewport.zoom}
                viewportX={viewport.x}
                viewportY={viewport.y}
              />
            );
          })}

          {/* Snippet cards */}
          {visibleSnippets.map((snippet) => (
            <SnippetCard
              key={snippet.id}
              snippet={snippet}
              screenX={(snippet.position_x + viewport.x) * viewport.zoom}
              screenY={(snippet.position_y + viewport.y) * viewport.zoom}
              zoom={viewport.zoom}
            />
          ))}
        </Container>
      </Stage>

      {/* HTML overlay for inline grid editing */}
      {editingSnippetId && <InlineGridEditor snippetId={editingSnippetId} viewport={viewport} containerRef={containerRef} />}

      {/* HTML overlay for snippet context menu */}
      <SnippetContextMenu />
    </div>
  );
}
