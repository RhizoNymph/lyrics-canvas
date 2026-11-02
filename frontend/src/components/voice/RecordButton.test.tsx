import { describe, it, expect, vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import RecordButton, { recordButtonTitle } from './RecordButton';

vi.mock('@/api/client', () => ({ nlpApi: {}, snippetApi: {}, canvasApi: {}, groupApi: {}, tagApi: {} }));

const insecure = { ok: false, reason: 'insecure_context', message: 'Recording needs HTTPS' } as const;

describe('RecordButton', () => {
  it('is disabled with the HTTPS tooltip when the page cannot record', () => {
    const html = renderToStaticMarkup(<RecordButton variant="toolbar" style={{}} support={insecure} />);
    expect(html).toContain('title="Recording needs HTTPS"');
    expect(html).toMatch(/<button[^>]*disabled=""/);
  });

  it('is enabled and advertises the shortcut otherwise; keeps the editor open', () => {
    const html = renderToStaticMarkup(<RecordButton variant="editor" style={{}} support={{ ok: true }} />);
    expect(html).not.toMatch(/<button[^>]*disabled/);
    expect(html).toContain('Alt+Shift+R');
    expect(html).toContain('data-keep-editor-open');
    expect(html).toContain('aria-label="Record and append to this snippet"');
  });

  it('describes the target per placement', () => {
    expect(recordButtonTitle('toolbar', { ok: true })).toMatch(/new snippet/);
    expect(recordButtonTitle('editor', { ok: true })).toMatch(/append to this snippet/);
  });
});
