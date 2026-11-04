import { describe, it, expect, vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import TagExpressionInput from './TagExpressionInput';

function render(value: string): string {
  // useLayoutEffect is a no-op (with a warning) in server rendering; only the markup matters here.
  const err = vi.spyOn(console, 'error').mockImplementation(() => undefined);
  const html = renderToStaticMarkup(<TagExpressionInput value={value} onChange={() => undefined} tags={[]} ariaLabel="Filter" />);
  err.mockRestore();
  return html;
}

describe('TagExpressionInput', () => {
  it('renders a valid expression without an alert', () => {
    const html = render('love &amp; night'.replace('&amp;', '&'));
    expect(html).toContain('aria-invalid="false"');
    expect(html).not.toContain('role="alert"');
  });

  it('shows the message and marks the character at the error position', () => {
    const html = render('🔥 )');
    expect(html).toContain('aria-invalid="true"');
    expect(html).toContain('role="alert"');
    expect(html).toContain('Unexpected token');
    expect(html).toMatch(/🔥 <span[^>]*>\)<\/span>/u);
  });
});
