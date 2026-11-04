import { describe, it, expect } from 'vitest';
import { checkPresetExpression } from './presets';

describe('checkPresetExpression', () => {
  it('normalizes a valid expression', () => {
    expect(checkPresetExpression('love  and not draft')).toEqual({ ok: true, normalized: 'love & !draft' });
  });

  it('rejects an empty expression as empty_preset', () => {
    expect(checkPresetExpression('   ')).toEqual({ ok: false, problem: 'empty_preset' });
  });

  it('rejects an invalid expression with its position', () => {
    const r = checkPresetExpression('love &');
    expect(r).toMatchObject({ ok: false, problem: 'invalid_tag_expression', error: { code: 'unexpected_end', position: 6 } });
  });
});
