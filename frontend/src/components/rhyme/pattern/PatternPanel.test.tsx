import { describe, it, expect, beforeEach } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import PatternPanel, { PatternPanelView } from './PatternPanel';
import type { PatternPanelViewProps, PanelActions } from './PatternPanel';
import type { PatternView } from '@/stores/rhymePatternStore';
import { useRhymePatternStore } from '@/stores/rhymePatternStore';
import { EMPTY_PICKS, PatternError, firstPageResults, initialEditState } from '@/lib/rhyme/pattern';
import { pcand, phit, pline, pmatch, pslot } from '@/lib/rhyme/pattern/fixtures';

const session = { canvasId: 'c1', snippetId: 'sn', lineStart: 0, lineEnd: 2 };
const lines = [pline(0, 'steady when the pressure on'), pline(1, 'already heavy weather wrong')];
const slots = [pslot('s0', [[0, 0, 1], [1, 1, 2]]), pslot('s1', [[0, 4, 5], [1, 3, 4]])];

beforeEach(() => useRhymePatternStore.getState().exit());

const noop = () => undefined;
const anoop = async () => undefined;
const actions: PanelActions = {
  edit: noop, togglePick: noop, dismiss: noop, loadMoreSlot: anoop, loadMoreLines: anoop, makeSnippet: anoop,
  addPicksToBank: anoop, clearPicks: noop, materializeLine: anoop, exit: noop, reset: noop,
};

function render(view: Exclude<PatternView, { kind: 'off' }>, over: Partial<PatternPanelViewProps> = {}) {
  return renderToStaticMarkup(
    <PatternPanelView view={view} suggest={{ kind: 'done' }} paging={new Set()} notice={null} actionError={null} snippetLabel="steady" ignored={0} actions={actions} {...over} />,
  );
}

describe('PatternPanel', () => {
  it('the connected panel renders nothing when off', () => {
    expect(renderToStaticMarkup(<PatternPanel />)).toBe('');
  });

  it('shows detecting and detect failures', () => {
    expect(render({ kind: 'detecting', session })).toContain('Finding where these lines rhyme');
    expect(render({ kind: 'detect_failed', session, error: new PatternError('network', 'detect', 'x') })).toContain(
      'Finding the pattern failed: could not reach the server.',
    );
  });

  it('renders the lines, legend, columns, tray and line matches when ready', () => {
    const results = firstPageResults(
      {
        slots: slots.map((slot) => ({ slot, candidates: [pcand(`dict:${slot.id}`, `word ${slot.id}`, 0.9)], total: 1 })),
        line_matches: [pmatch('note:n:0:0', 'ready and strong', [phit('s0', 0, 1, 'ready'), phit('s1', 2, 3, 'strong')])],
        line_match_total: 1,
        elapsed_ms: 1,
      },
      new Set(),
    );
    const html = render({ kind: 'ready', session, lines, edit: initialEditState(lines, slots), detected: new Map(slots.map((s) => [s.id, s])), results, picks: EMPTY_PICKS });
    expect(html).toContain('Back to single');
    expect(html).toContain('>pressure</button>');
    expect(html).toContain('data-column="A"');
    expect(html).toContain('data-column="B"');
    expect(html).toContain('word s1');
    expect(html).toContain('aria-label="Tuple"');
    expect(html).toContain('2 of 2 slots');
    expect(html).toContain('Search options');
    expect(html).toContain('Lines 1–2 of <i>steady</i>');
  });

  it('shows the no-slot empty state with the detection threshold', () => {
    const html = render(
      { kind: 'ready', session, lines, edit: initialEditState(lines, []), detected: new Map(), results: null, picks: EMPTY_PICKS },
      { suggest: { kind: 'idle' } },
    );
    expect(html).toContain("These lines don&#x27;t share a rhyme at this threshold");
    expect(html).toContain('Detection threshold');
  });
});
