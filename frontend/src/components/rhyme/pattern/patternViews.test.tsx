import { describe, it, expect } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import PatternLinesView from './PatternLinesView';
import WordPicker from './WordPicker';
import SlotLegend from './SlotLegend';
import SlotColumns from './SlotColumns';
import TupleTray from './TupleTray';
import LineMatchList from './LineMatchList';
import PatternHeader from './PatternHeader';
import { PatternShortcutView } from './PatternShortcut';
import { patternEmptyMessage } from './PatternPanel';
import { sourceLabel } from './PatternCandidateRow';
import { EMPTY_PICKS, firstPageResults, slotViews, togglePick, wordActions } from '@/lib/rhyme/pattern';
import type { SlotEditState, SlotView } from '@/lib/rhyme/pattern';
import { pcand, phit, pline, pmatch, pslot } from '@/lib/rhyme/pattern/fixtures';

const noop = () => undefined;

const lines = [pline(0, 'steady when the pressure on'), pline(1, 'already heavy weather wrong')];
const edit: SlotEditState = {
  lines: [{ line: 0, wordCount: 5 }, { line: 1, wordCount: 4 }],
  slots: [
    { id: 's0', on: true, members: [{ line: 0, start: 0, end: 1 }, { line: 1, start: 1, end: 2 }] },
    { id: 's1', on: false, members: [{ line: 0, start: 4, end: 5 }] },
  ],
  nextUserId: 0,
};
const views: SlotView[] = slotViews(edit, new Map([['s0', pslot('s0', [[0, 0, 1], [1, 1, 2]])]]));

describe('patternEmptyMessage', () => {
  it('explains no slots, all off, and no source', () => {
    expect(patternEmptyMessage([], { kind: 'idle' })).toMatch(/don't share a rhyme at this threshold.*tap words to make a slot/);
    expect(patternEmptyMessage(views.map((v) => ({ ...v, on: false })), { kind: 'idle' })).toBe('Every slot is off. Turn one on to search.');
    expect(patternEmptyMessage(views, { kind: 'idle' })).toBe('Turn on notes or dictionary to search.');
    expect(patternEmptyMessage(views, { kind: 'done' })).toBeNull();
  });
});

describe('PatternLinesView', () => {
  const render = (picked: { line: number; word: number } | null = null) =>
    renderToStaticMarkup(
      <PatternLinesView
        lines={lines}
        edit={edit}
        views={views}
        picked={picked}
        actions={picked === null ? [] : wordActions(edit, picked.line, picked.word, () => 'x')}
        onWordClick={noop}
        onAction={noop}
        onClosePicker={noop}
      />,
    );

  it('renders every word with members marked by slot letter', () => {
    const html = render();
    expect(html).toContain('>steady<sup');
    expect((html.match(/data-slot="A"/g) ?? []).length).toBe(2);
    expect((html.match(/data-slot="B"/g) ?? []).length).toBe(1);
    expect(html).toContain('>weather</button>');
    expect(html).not.toContain('role="menu"');
  });

  it('opens the picker under the tapped word line', () => {
    const html = render({ line: 1, word: 2 });
    expect(html).toContain('role="menu"');
    expect(html).toContain('Slot actions for weather');
    expect(html).toContain('New slot');
  });
});

describe('WordPicker', () => {
  it('disables refused actions with the reason as a tooltip', () => {
    const html = renderToStaticMarkup(
      <WordPicker
        word="on"
        actions={[
          { label: 'Add to A', edit: { type: 'add', slotId: 's0', line: 0, word: 4 }, disabled: 'That would put slot A out of order on line 1.' },
          { label: 'New slot', edit: { type: 'new_slot', line: 0, word: 4 }, disabled: null },
        ]}
        onAction={noop}
        onClose={noop}
      />,
    );
    expect(html).toMatch(/title="That would put slot A out of order on line 1\."><button[^>]*disabled=""/);
    expect(html).toContain('>New slot</button>');
  });
});

describe('SlotLegend', () => {
  it('shows letter, key and coverage, with toggle state', () => {
    const html = renderToStaticMarkup(<SlotLegend views={views} onToggle={noop} onDrop={noop} />);
    expect(html).toContain('EH D IY');
    expect(html).toContain('>2/2<');
    expect(html).toContain('>1/2<');
    expect(html).toContain('aria-pressed="true"');
    expect(html).toContain('aria-pressed="false"');
    expect(html).toContain('aria-label="Drop slot B"');
  });

  it('renders nothing without slots', () => {
    expect(renderToStaticMarkup(<SlotLegend views={[]} onToggle={noop} onDrop={noop} />)).toBe('');
  });
});

describe('SlotColumns', () => {
  const results = firstPageResults(
    {
      slots: [{ slot: pslot('s0', [[0, 0, 1], [1, 1, 2]]), candidates: [pcand('dict:a', 'ready', 0.91), pcand('note:n:1:0-1', 'heady', 0.8)], total: 45 }],
      line_matches: [],
      line_match_total: 0,
      elapsed_ms: 1,
    },
    new Set(),
  );

  it('renders a column per slot with rows, the pick marked, and More while there is more', () => {
    const picks = togglePick(EMPTY_PICKS, 's0', { candidateId: 'dict:a', text: 'ready' });
    const html = renderToStaticMarkup(
      <SlotColumns slots={views.slice(0, 1)} columns={results.columns} picks={picks} paging={new Set()} loading={false} onPick={noop} onDismiss={noop} onMore={noop} />,
    );
    expect(html).toContain('data-column="A"');
    expect(html).toMatch(/data-candidate-id="dict:a" aria-selected="true"/);
    expect(html).toMatch(/data-candidate-id="note:n:1:0-1" aria-selected="false"/);
    expect(html).toContain('>my line<');
    expect(html).toContain('>dict<');
    expect(html).toContain('0.91');
    expect(html).toContain('>More</button>');
    expect(html).toContain('+ bank');
  });

  it('shows searching, empty columns and a column for a slot the server has not answered yet', () => {
    const empty = new Map([['s0', { slot: pslot('s0', []), candidates: [], total: 0, offset: 0 }]]);
    expect(renderToStaticMarkup(<SlotColumns slots={views.slice(0, 1)} columns={empty} picks={EMPTY_PICKS} paging={new Set()} loading={false} onPick={noop} onDismiss={noop} onMore={noop} />)).toContain(
      'Nothing rhymes with every member at this score.',
    );
    expect(renderToStaticMarkup(<SlotColumns slots={views.slice(0, 1)} columns={null} picks={EMPTY_PICKS} paging={new Set()} loading onPick={noop} onDismiss={noop} onMore={noop} />)).toContain(
      'Searching…',
    );
  });
});

describe('TupleTray', () => {
  it('shows picks in order with gaps and enables the actions once something is picked', () => {
    const html = renderToStaticMarkup(
      <TupleTray
        entries={[
          { slotId: 's0', letter: 'A', text: 'steady' },
          { slotId: 's1', letter: 'B', text: null },
          { slotId: 's2', letter: 'C', text: 'strong' },
        ]}
        indexOf={() => 0}
        onMakeSnippet={noop}
        onAddToBank={noop}
        onClear={noop}
      />,
    );
    expect(html).toContain('>steady<');
    expect(html).toContain('>___<');
    expect(html).toContain('>strong<');
    expect(html).not.toMatch(/disabled=""[^>]*>Make snippet/);
  });

  it('disables the actions with no pick and hides with no on slot', () => {
    const html = renderToStaticMarkup(<TupleTray entries={[{ slotId: 's0', letter: 'A', text: null }]} indexOf={() => 0} onMakeSnippet={noop} onAddToBank={noop} onClear={noop} />);
    expect(html).toMatch(/disabled=""[^>]*>Make snippet/);
    expect(html).toContain('Tap a row in a list');
    expect(renderToStaticMarkup(<TupleTray entries={[]} indexOf={() => 0} onMakeSnippet={noop} onAddToBank={noop} onClear={noop} />)).toBe('');
  });
});

describe('LineMatchList', () => {
  it('lists the lines with hits and "N of M slots"', () => {
    const html = renderToStaticMarkup(
      <LineMatchList
        matches={[pmatch('note:n:0:0', 'ready and strong', [phit('s0', 0, 1, 'ready'), phit('s1', 2, 3, 'strong')])]}
        total={1}
        slotCount={3}
        slotIndex={(id) => (id === 's0' ? 0 : 1)}
        canMore={false}
        loadingMore={false}
        onMaterialize={noop}
        onDismiss={noop}
        onMore={noop}
      />,
    );
    expect(html).toContain('2 of 3 slots');
    expect(html).toContain('Materialize');
    expect(html).toMatch(/font-weight:bold">ready</);
    expect(html).not.toMatch(/font-weight:bold">and</);
  });

  it('says when nothing matches', () => {
    const html = renderToStaticMarkup(
      <LineMatchList matches={[]} total={0} slotCount={2} slotIndex={() => null} canMore={false} loadingMore={false} onMaterialize={noop} onDismiss={noop} onMore={noop} />,
    );
    expect(html).toContain('None of your lines hit 2 or more of these slots.');
  });
});

describe('PatternHeader', () => {
  it('shows the range 1-based and notes ignored snippets', () => {
    const html = renderToStaticMarkup(<PatternHeader lineStart={2} lineEnd={6} snippetLabel="verse one" ignored={2} onBack={noop} onReset={noop} />);
    expect(html).toContain('Lines 3–6 of <i>verse one</i>');
    expect(html).toContain('The other 2 selected snippets are');
    expect(html).toContain('Back to single');
    expect(html).toContain('Reset');
    expect(renderToStaticMarkup(<PatternHeader lineStart={0} lineEnd={2} snippetLabel="x" ignored={0} onBack={noop} onReset={noop} />)).not.toContain('ignored');
  });
});

describe('PatternShortcutView', () => {
  it('labels N and bounds the stepper', () => {
    const html = renderToStaticMarkup(<PatternShortcutView n={2} max={4} onChange={noop} onStart={noop} />);
    expect(html).toContain('Pattern (last 2 lines)');
    expect(html).toMatch(/disabled=""[^>]*aria-label="Fewer lines"|aria-label="Fewer lines"[^>]*disabled=""/);
  });
});

describe('sourceLabel', () => {
  it('names my lines and the dictionary', () => {
    expect(sourceLabel(pcand('note:x', 'a', 1))).toBe('my line');
    expect(sourceLabel(pcand('dict:x', 'a', 1))).toBe('dict');
  });
});
