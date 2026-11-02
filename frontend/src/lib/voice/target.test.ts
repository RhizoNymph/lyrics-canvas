import { describe, it, expect } from 'vitest';
import { resolveCaptureTarget, toWireTarget } from './target';

const viewport = { x: -100, y: 50, zoom: 2 };

describe('resolveCaptureTarget', () => {
  it('appends to the snippet open in the editor', () => {
    expect(resolveCaptureTarget({ editingSnippetId: 's1', viewport, canvasViewSize: { width: 800, height: 600 } })).toEqual({
      kind: 'append',
      snippetId: 's1',
    });
  });

  it('creates at the viewport centre in canvas coordinates', () => {
    // centre = size / (2 * zoom) - offset = (200 + 100, 150 - 50)
    expect(resolveCaptureTarget({ editingSnippetId: null, viewport, canvasViewSize: { width: 800, height: 600 } })).toEqual({
      kind: 'new',
      position: { x: 300, y: 100 },
    });
  });

  it('falls back to the origin when the canvas was never measured (list view from the start)', () => {
    expect(resolveCaptureTarget({ editingSnippetId: null, viewport, canvasViewSize: null })).toEqual({
      kind: 'new',
      position: { x: 0, y: 0 },
    });
    expect(resolveCaptureTarget({ editingSnippetId: null, viewport, canvasViewSize: { width: 0, height: 0 } })).toEqual({
      kind: 'new',
      position: { x: 0, y: 0 },
    });
  });
});

describe('toWireTarget', () => {
  it('maps to the contract shapes', () => {
    expect(toWireTarget({ kind: 'append', snippetId: 'a' })).toEqual({ kind: 'append', snippet_id: 'a' });
    expect(toWireTarget({ kind: 'new', position: { x: 1.5, y: -2 } })).toEqual({ kind: 'new', position_x: 1.5, position_y: -2 });
  });
});
