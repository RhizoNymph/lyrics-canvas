import { describe, it, expect } from 'vitest';
import { EMPTY_FILTER, filterReducer, isStale, restoreFilter, toPersisted } from './state';
import type { FilterAction, FilterState } from './state';
import { format } from '@/lib/tagExpr';

const run = (actions: FilterAction[], from: FilterState = EMPTY_FILTER) => actions.reduce(filterReducer, from);

describe('filterReducer', () => {
  it('applies a valid edit', () => {
    const s = run([{ type: 'edit', text: 'love and night' }]);
    expect(s.text).toBe('love and night');
    expect(s.error).toBeNull();
    expect(s.appliedText).toBe('love and night');
    expect(format(s.applied)).toBe('love & night');
  });

  it('keeps the last valid filter while the text is invalid', () => {
    const s = run([{ type: 'edit', text: 'love' }, { type: 'edit', text: 'love &' }]);
    expect(s.text).toBe('love &');
    expect(s.error).toMatchObject({ code: 'unexpected_end', position: 6 });
    expect(s.appliedText).toBe('love');
    expect(format(s.applied)).toBe('love');
    expect(isStale(s)).toBe(true);
  });

  it('applies again once the text parses', () => {
    const s = run([{ type: 'edit', text: 'love' }, { type: 'edit', text: 'love &' }, { type: 'edit', text: 'love & night' }]);
    expect(s.error).toBeNull();
    expect(format(s.applied)).toBe('love & night');
  });

  it('treats an empty edit as no filter', () => {
    const s = run([{ type: 'edit', text: 'love' }, { type: 'edit', text: '   ' }]);
    expect(s.applied).toBeNull();
    expect(s.error).toBeNull();
  });

  it('applies a preset like an edit', () => {
    const s = run([{ type: 'edit', text: 'oops (' }, { type: 'applyPreset', expression: 'type:chorus & !draft' }]);
    expect(s.text).toBe('type:chorus & !draft');
    expect(s.error).toBeNull();
    expect(format(s.applied)).toBe('type:chorus & !draft');
  });

  it('clears everything', () => {
    expect(run([{ type: 'edit', text: 'love' }, { type: 'edit', text: '(' }, { type: 'clear' }])).toEqual(EMPTY_FILTER);
  });

  it('follows a tag rename in valid text', () => {
    const s = run([{ type: 'edit', text: 'love  and night' }, { type: 'renameTag', from: 'LOVE', to: 'late night' }]);
    expect(s.text).toBe('"late night" & night');
    expect(format(s.applied)).toBe('"late night" & night');
  });

  it('renames inside the applied text when the input is invalid', () => {
    const s = run([{ type: 'edit', text: 'love' }, { type: 'edit', text: 'love &' }, { type: 'renameTag', from: 'love', to: 'romance' }]);
    expect(s.text).toBe('love &');
    expect(s.appliedText).toBe('romance');
    expect(format(s.applied)).toBe('romance');
    expect(s.error).not.toBeNull();
  });

  it('leaves text that does not mention the tag alone', () => {
    const before = run([{ type: 'edit', text: 'night   or dark' }]);
    const after = filterReducer(before, { type: 'renameTag', from: 'love', to: 'x' });
    expect(after.text).toBe('night   or dark');
  });
});

describe('restoreFilter / toPersisted', () => {
  it('round-trips, including an invalid text over a valid applied one', () => {
    const s = run([{ type: 'edit', text: 'love' }, { type: 'edit', text: 'love |' }]);
    const back = restoreFilter(toPersisted(s));
    expect(back.text).toBe('love |');
    expect(back.appliedText).toBe('love');
    expect(format(back.applied)).toBe('love');
    expect(back.error?.code).toBe('unexpected_end');
  });

  it('drops a stored applied text that does not parse', () => {
    const back = restoreFilter({ text: 'x', appliedText: '((' });
    expect(back.appliedText).toBe('x');
    expect(format(back.applied)).toBe('x');
    const bad = restoreFilter({ text: '((', appliedText: '((' });
    expect(bad.applied).toBeNull();
    expect(bad.appliedText).toBe('');
  });

  it('restores nothing as the empty filter', () => {
    expect(restoreFilter(null)).toEqual(EMPTY_FILTER);
  });
});
