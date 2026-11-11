import { useEffect, useRef } from 'react';
import { useCanvasSearchStore } from '@/stores/canvasSearchStore';
import CanvasSearchBoxView from './CanvasSearchBoxView';

/** The in-canvas search box bound to canvasSearchStore; Ctrl/Cmd+F (a store request) focuses it. */
export default function CanvasSearchBox({ countLabel }: { countLabel: string | null }) {
  const text = useCanvasSearchStore((s) => s.text);
  const setText = useCanvasSearchStore((s) => s.setText);
  const clear = useCanvasSearchStore((s) => s.clear);
  const focusRequest = useCanvasSearchStore((s) => s.focusRequest);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (focusRequest === 0) return;
    inputRef.current?.focus();
    inputRef.current?.select();
  }, [focusRequest]);

  return <CanvasSearchBoxView text={text} countLabel={countLabel} onChange={setText} onClear={clear} inputRef={inputRef} />;
}
