import { useEffect } from 'react';
import { useUIStore } from '@/stores/uiStore';
import { useRhymeStore } from '@/stores/rhymeStore';
import QueryControls from '@/components/rhyme/QueryControls';
import CandidateList from '@/components/rhyme/CandidateList';
import PatternPanel from '@/components/rhyme/pattern/PatternPanel';
import { PATTERN_SIDEBAR_WIDTH, SIDEBAR_WIDTH } from '@/components/rhyme/sidebarStyles';
import { useRhymePatternStore } from '@/stores/rhymePatternStore';

/**
 * Engine-backed rhyme sidebar. Mounted by CanvasPage while `uiStore.rhymeSidebarOpen`; its
 * lifetime is the rhyme session (`rhymeStore.enter` on mount, `exit` on unmount). Results stay
 * in the list; only Materialize creates anything on the canvas. While `rhymePatternStore` holds a pattern
 * session the body is the Pattern mode (wider, for the side-by-side slot lists); closing clears both.
 */
export default function RhymeSidebar() {
  const toggleRhymeSidebar = useUIStore((s) => s.toggleRhymeSidebar);
  const enter = useRhymeStore((s) => s.enter);
  const exit = useRhymeStore((s) => s.exit);
  const exitPattern = useRhymePatternStore((s) => s.exit);
  const patternOn = useRhymePatternStore((s) => s.view.kind !== 'off');

  useEffect(() => {
    enter();
    return () => {
      exitPattern();
      exit();
    };
  }, [enter, exit, exitPattern]);

  return (
    <div
      style={{
        width: patternOn ? PATTERN_SIDEBAR_WIDTH : SIDEBAR_WIDTH,
        flexShrink: 0,
        background: '#1a1a2e',
        borderLeft: '1px solid #2a2a4a',
        display: 'flex',
        flexDirection: 'column',
        overflow: 'hidden',
        color: '#e0e0e0',
      }}
    >
      <div style={{ padding: 12, borderBottom: '1px solid #2a2a4a', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <span style={{ fontWeight: 'bold', fontSize: 14 }}>{patternOn ? 'Rhymes · Pattern' : 'Rhymes'}</span>
        <button
          onClick={toggleRhymeSidebar}
          title="Close (results are discarded; the canvas is untouched)"
          style={{ background: 'none', border: 'none', color: '#888', cursor: 'pointer', fontSize: 18 }}
        >
          ×
        </button>
      </div>
      {patternOn ? (
        <PatternPanel />
      ) : (
        <>
          <div style={{ padding: 12 }}>
            <QueryControls />
          </div>
          <CandidateList />
        </>
      )}
    </div>
  );
}
