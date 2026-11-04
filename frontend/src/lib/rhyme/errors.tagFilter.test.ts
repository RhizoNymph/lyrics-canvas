import { describe, it, expect } from 'vitest';
import { AxiosError, AxiosHeaders } from 'axios';
import { toRhymeError } from './errors';

function axiosErr(status: number, data: unknown): AxiosError {
  const config = { headers: new AxiosHeaders() };
  return new AxiosError('Request failed', 'ERR_BAD_REQUEST', config, undefined, { status, statusText: '', headers: {}, config, data });
}

describe('toRhymeError: tag filter', () => {
  it('maps 422 invalid_tag_expression to invalid_tag_filter with the position', () => {
    const e = toRhymeError(
      axiosErr(422, { detail: { error: 'invalid_tag_expression', message: 'bad filter', expression_error: { code: 'unclosed_paren', position: 2, message: 'm' } } }),
      'query',
    );
    expect(e.kind).toBe('invalid_tag_filter');
    expect(e.message).toBe('bad filter');
    expect(e.expressionError).toEqual({ code: 'unclosed_paren', position: 2, message: 'm' });
  });

  it('keeps other structured 422s as invalid_request', () => {
    const e = toRhymeError(axiosErr(422, { detail: { error: 'something', message: 'x' } }), 'query');
    expect(e.kind).toBe('invalid_request');
    expect(e.expressionError).toBeNull();
  });
});
