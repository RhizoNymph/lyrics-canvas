import { describe, it, expect, vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { ListSortMenuView } from './ListSortMenu';
import ListRow from './ListRow';
import { snippet } from '@/lib/merge/fixtures';

vi.mock('@/components/canvas/InlineGridEditor', () => ({ default: () => null }));

const noop = () => undefined;

describe('ListSortMenuView', () => {
  it('offers the four sorts in order with the current one selected', () => {
    const html = renderToStaticMarkup(<ListSortMenuView sort="a_z" onChange={noop} />);
    const options = [...html.matchAll(/<option value="([a-z_]+)"[^>]*>([^<]+)</g)].map((m) => [m[1], m[2]]);
    expect(options).toEqual([
      ['last_edited', 'Last edited'],
      ['created', 'Created'],
      ['a_z', 'A-Z'],
      ['custom', 'Custom'],
    ]);
    expect(html).toMatch(/<option value="a_z" selected="">/);
    expect(html).toContain('>Sort<');
  });
});

describe('ListRow reorder affordance', () => {
  const s = snippet('a', 'hello world');
  const base = {
    snippet: s,
    selected: false,
    editing: false,
    onClick: noop,
    onContextMenu: noop,
    onOpen: noop,
    onShowCanvases: noop,
  };

  it('shows a drag handle and announces Alt+Up / Alt+Down when reordering is on', () => {
    const html = renderToStaticMarkup(
      <ListRow {...base} reorder={{ dragging: false, onHandlePointerDown: noop, onStep: noop }} />,
    );
    expect(html).toContain('data-drag-handle');
    expect(html).toContain('aria-keyshortcuts="Alt+ArrowUp Alt+ArrowDown"');
  });

  it('has no handle while reordering is off (an editor is open)', () => {
    const html = renderToStaticMarkup(<ListRow {...base} reorder={null} />);
    expect(html).not.toContain('data-drag-handle');
    expect(html).not.toContain('aria-keyshortcuts');
  });

  it('fades the row being dragged', () => {
    const html = renderToStaticMarkup(
      <ListRow {...base} reorder={{ dragging: true, onHandlePointerDown: noop, onStep: noop }} />,
    );
    expect(html).toContain('opacity:0.45');
  });
});
