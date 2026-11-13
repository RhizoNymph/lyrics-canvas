import { describe, it, expect } from 'vitest';
import { AxiosError, AxiosHeaders } from 'axios';
import { PatternError, describePatternError, toPatternError } from './errors';

function axiosErr(status: number | null, data?: unknown): AxiosError {
  const config = { headers: new AxiosHeaders() };
  const response = status === null ? undefined : { status, statusText: '', headers: {}, config, data };
  return new AxiosError('Request failed', status === null ? 'ERR_NETWORK' : 'ERR_BAD_REQUEST', config, undefined, response);
}

const coded = (error: string, message = 'msg') => ({ detail: { error, message } });

describe('toPatternError', () => {
  it('maps the pattern codes', () => {
    expect(toPatternError(axiosErr(422, coded('pattern_range_invalid')), 'detect')).toMatchObject({ kind: 'range_invalid', status: 422, operation: 'detect', message: 'msg' });
    expect(toPatternError(axiosErr(422, coded('pattern_span_invalid')), 'suggest').kind).toBe('span_invalid');
    expect(toPatternError(axiosErr(422, coded('pattern_slot_order')), 'suggest').kind).toBe('slot_order');
  });

  it('maps invalid_tag_expression with its expression error', () => {
    const e = toPatternError(
      axiosErr(422, { detail: { error: 'invalid_tag_expression', message: 'bad', expression_error: { code: 'unexpected_token', position: 2 } } }),
      'suggest',
    );
    expect(e.kind).toBe('invalid_tag_filter');
    expect(e.expressionError?.code).toBe('unexpected_token');
  });

  it('classes by status otherwise', () => {
    expect(toPatternError(axiosErr(404, { detail: 'Snippet not found' }), 'detect')).toMatchObject({ kind: 'not_found', message: 'Snippet not found' });
    expect(toPatternError(axiosErr(422, { detail: [{ msg: 'a' }, { msg: 'b' }] }), 'suggest')).toMatchObject({ kind: 'invalid_request', message: 'a; b' });
    expect(toPatternError(axiosErr(500), 'more').kind).toBe('server');
    expect(toPatternError(axiosErr(null), 'detect')).toMatchObject({ kind: 'network', status: null });
  });

  it('wraps other failures and passes a PatternError through', () => {
    expect(toPatternError(new Error('boom'), 'make_snippet')).toMatchObject({ kind: 'unknown', message: 'boom' });
    expect(toPatternError('x', 'detect').kind).toBe('unknown');
    const e = new PatternError('server', 'detect', 'x', 503);
    expect(toPatternError(e, 'suggest')).toBe(e);
  });
});

describe('describePatternError', () => {
  it('names the operation and the cause', () => {
    expect(describePatternError(new PatternError('network', 'detect', 'Network Error'))).toBe('Finding the pattern failed: could not reach the server.');
    expect(describePatternError(new PatternError('slot_order', 'suggest', 'slot s1 out of order'))).toBe(
      'Searching failed: the slots are out of order on a line (slot s1 out of order). Try Reset.',
    );
    expect(describePatternError(new PatternError('span_invalid', 'suggest', 'm'))).toBe('Searching failed: a slot word is no longer in the lines (m). Try Reset.');
    expect(describePatternError(new PatternError('range_invalid', 'detect', 'm'))).toBe('Finding the pattern failed: the line range is not valid (m).');
    expect(describePatternError(new PatternError('invalid_tag_filter', 'suggest', 'Tag filter: x'))).toBe('Not searched: Tag filter: x');
    expect(describePatternError(new PatternError('not_found', 'make_snippet', 'm'))).toBe('Make snippet failed: not found (m).');
    expect(describePatternError(new PatternError('server', 'more', ''))).toBe('Loading more failed: server error.');
  });
});
