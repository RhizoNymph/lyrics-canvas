import { describe, it, expect } from 'vitest';
import { AxiosError, AxiosHeaders } from 'axios';
import { TAG_SERVER_ERROR_CODES, TagApiError, describeTagError, toTagApiError } from './errors';
import { parseServerDetail } from './serverDetail';

function axiosErr(status: number | null, data?: unknown): AxiosError {
  const config = { headers: new AxiosHeaders() };
  const response = status === null ? undefined : { status, statusText: '', headers: {}, config, data };
  return new AxiosError('Request failed', status === null ? 'ERR_NETWORK' : 'ERR_BAD_REQUEST', config, undefined, response);
}

describe('toTagApiError', () => {
  it.each(TAG_SERVER_ERROR_CODES.map((c) => [c]))('maps detail.error %s to its kind', (code) => {
    const e = toTagApiError(axiosErr(409, { detail: { error: code, message: 'srv' } }), 'create_tag');
    expect(e).toBeInstanceOf(TagApiError);
    expect(e.kind).toBe(code);
    expect(e.status).toBe(409);
    expect(e.message).toBe('srv');
    expect(e.operation).toBe('create_tag');
  });

  it('carries the expression error of invalid_tag_expression', () => {
    const e = toTagApiError(
      axiosErr(422, {
        detail: { error: 'invalid_tag_expression', message: 'bad', expression_error: { code: 'unexpected_end', position: 6, message: 'ends early' } },
      }),
      'create_preset',
    );
    expect(e.kind).toBe('invalid_tag_expression');
    expect(e.expressionError).toEqual({ code: 'unexpected_end', position: 6, message: 'ends early' });
    expect(describeTagError(e)).toContain('ends early');
  });

  it('falls back by status for unknown or missing codes', () => {
    expect(toTagApiError(axiosErr(404, { detail: 'Not found' }), 'link_tag').kind).toBe('not_found');
    expect(toTagApiError(axiosErr(422, { detail: [{ msg: 'field required' }] }), 'create_tag')).toMatchObject({
      kind: 'validation',
      message: 'field required',
    });
    expect(toTagApiError(axiosErr(500, { detail: { error: 'something_else' } }), 'load_tags').kind).toBe('server');
    expect(toTagApiError(axiosErr(418), 'load_tags').kind).toBe('http');
  });

  it('maps a missing response to network and non-axios throws to http', () => {
    expect(toTagApiError(axiosErr(null), 'load_tags').kind).toBe('network');
    expect(toTagApiError(new Error('boom'), 'load_tags')).toMatchObject({ kind: 'http', message: 'boom' });
    expect(toTagApiError('weird', 'load_tags')).toMatchObject({ kind: 'http', message: 'weird' });
  });

  it('passes a TagApiError through', () => {
    const e = new TagApiError('tag_name_taken', 'update_tag', 'x');
    expect(toTagApiError(e, 'create_tag')).toBe(e);
  });
});

describe('describeTagError', () => {
  it('uses fixed wording for contract codes', () => {
    expect(describeTagError(new TagApiError('tag_name_taken', 'create_tag', 'srv text'))).toBe('A tag with that name already exists');
    expect(describeTagError(new TagApiError('preset_name_taken', 'create_preset', 'srv'))).toBe('A preset with that name already exists');
  });
});

describe('parseServerDetail', () => {
  it('ignores malformed expression errors', () => {
    expect(parseServerDetail({ detail: { error: 'invalid_tag_expression', expression_error: { code: 'nope', position: 1 } } }).expressionError).toBeNull();
    expect(parseServerDetail({ detail: { error: 'invalid_tag_expression', expression_error: { code: 'too_deep', position: -1 } } }).expressionError).toBeNull();
    expect(parseServerDetail(null)).toEqual({ code: null, message: null, expressionError: null });
  });

  it('gives the local message when the server sends none', () => {
    const d = parseServerDetail({ detail: { error: 'invalid_tag_expression', expression_error: { code: 'too_deep', position: 32 } } });
    expect(d.expressionError).toMatchObject({ code: 'too_deep', position: 32 });
    expect(d.expressionError?.message.length).toBeGreaterThan(0);
  });
});
