import { describe, it, expect } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { EMPTY_FILTER, filterReducer } from '@/lib/tagFilter';
import TagFilterBar from './TagFilterBar';
import TagChecklist from './TagChecklist';

const noop = () => undefined;

describe('TagFilterBar (shared by the canvas and the word bank)', () => {
  it('shows the field, presets, clear and the caller count', () => {
    const html = renderToStaticMarkup(
      <TagFilterBar filter={EMPTY_FILTER} dispatch={noop} countLabel="12 words (12 shown)" ariaLabel="Word bank filter expression" noTagsHint="none" compact />,
    );
    expect(html).toContain('aria-label="Word bank filter expression"');
    expect(html).toContain('12 words (12 shown)');
    expect(html).toContain('aria-label="Clear the filter"');
    expect(html).toContain('Presets');
  });

  it('notes that an invalid text is not applied and names the filter in force', () => {
    const valid = filterReducer(EMPTY_FILTER, { type: 'edit', text: 'love' });
    const invalid = filterReducer(valid, { type: 'edit', text: 'love &' });
    const html = renderToStaticMarkup(<TagFilterBar filter={invalid} dispatch={noop} countLabel="" ariaLabel="f" noTagsHint="" />);
    expect(html).toContain('Not applied.');
    expect(html).toContain('Still filtering by');
  });
});

describe('TagChecklist', () => {
  it('renders the caller title over any targets', () => {
    const html = renderToStaticMarkup(
      <TagChecklist
        targets={[{ id: 'w1', tags: [] }]}
        onToggle={async () => ({ ok: true })}
        title="Tags · 2 words"
        countOf={(t) => t.word_count}
        countNoun="words"
      />,
    );
    expect(html).toContain('Tags · 2 words');
    expect(html).toContain('aria-label="Search or create tags"');
  });
});
