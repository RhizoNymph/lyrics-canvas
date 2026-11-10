import { describe, it, expect } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { PlacementToastView } from './PlacementToast';
import { CanvasesPopoverView } from './CanvasesPopover';
import CanvasBadge from './CanvasBadge';
import { snippet } from '@/lib/merge/fixtures';

const noop = () => undefined;

describe('PlacementToastView', () => {
  it('shows the text and an Open action for a target canvas', () => {
    const html = renderToStaticMarkup(
      <PlacementToastView notice={{ id: 1, text: 'Moved 3 snippets to Hooks', tone: 'info', open: { id: 'h', title: 'Hooks' } }} onDismiss={noop} onOpen={noop} />,
    );
    expect(html).toContain('role="status"');
    expect(html).toContain('Moved 3 snippets to Hooks');
    expect(html).toContain('>Open<');
  });

  it('an error has no Open action and is an alert', () => {
    const html = renderToStaticMarkup(
      <PlacementToastView notice={{ id: 1, text: 'Could not reach the server', tone: 'error', open: null }} onDismiss={noop} onOpen={noop} />,
    );
    expect(html).toContain('role="alert"');
    expect(html).not.toContain('>Open<');
  });
});

describe('CanvasesPopoverView', () => {
  it('lists every canvas; only the others are buttons', () => {
    const html = renderToStaticMarkup(
      <CanvasesPopoverView
        canvases={[{ id: 'a', title: 'Album' }, { id: 'c1', title: 'Here' }]}
        currentCanvasId="c1"
        x={0}
        y={0}
        onOpen={noop}
      />,
    );
    expect(html).toContain('On 2 canvases');
    expect(html).toMatch(/<button[^>]*>Album<\/button>/);
    expect(html).toContain('Here <span');
    expect(html).toContain('(this canvas)');
    expect(html).not.toMatch(/<button[^>]*>Here/);
  });
});

describe('CanvasBadge', () => {
  it('renders "⧉ N" for a snippet on several canvases', () => {
    const s = snippet('s', 'x', { canvases: [{ id: 'a', title: 'A' }, { id: 'c1', title: 'B' }] });
    const html = renderToStaticMarkup(<CanvasBadge snippet={s} onOpen={noop} />);
    expect(html).toContain('⧉ 2');
    expect(html).toContain('aria-label="On 2 canvases: A, B"');
  });

  it('renders nothing for a snippet on one canvas', () => {
    expect(renderToStaticMarkup(<CanvasBadge snippet={snippet('s', 'x')} onOpen={noop} />)).toBe('');
  });
});
