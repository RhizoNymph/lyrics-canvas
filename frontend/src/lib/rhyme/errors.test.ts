import { describe, it, expect } from 'vitest';
import { AxiosError, AxiosHeaders } from 'axios';
import { toRhymeError } from './errors';
import { RhymeError } from '@/types/rhyme';

function axiosErr(status: number | null, data?: unknown): AxiosError {
  const config = { headers: new AxiosHeaders() };
  const response =
    status === null
      ? undefined
      : { status, statusText: '', headers: {}, config, data };
  return new AxiosError('Request failed', status === null ? 'ERR_NETWORK' : 'ERR_BAD_REQUEST', config, undefined, response);
}

describe('toRhymeError', () => {
  it('maps a missing response to network', () => {
    const e = toRhymeError(axiosErr(null), 'query');
    expect(e).toBeInstanceOf(RhymeError);
    expect(e.kind).toBe('network');
    expect(e.status).toBeNull();
    expect(e.operation).toBe('query');
  });

  it('maps 404 to not_found with the detail message', () => {
    const e = toRhymeError(axiosErr(404, { detail: 'Canvas not found' }), 'query');
    expect(e.kind).toBe('not_found');
    expect(e.status).toBe(404);
    expect(e.message).toBe('Canvas not found');
  });

  it('maps 422 to invalid_request and joins pydantic messages', () => {
    const e = toRhymeError(axiosErr(422, { detail: [{ msg: 'k must be 1..4' }, { msg: 'bad stress' }] }), 'query');
    expect(e.kind).toBe('invalid_request');
    expect(e.message).toBe('k must be 1..4; bad stress');
  });

  it('maps 500 to server', () => {
    const e = toRhymeError(axiosErr(500), 'promote');
    expect(e.kind).toBe('server');
    expect(e.operation).toBe('promote');
  });

  it('wraps plain errors and non-errors as unknown', () => {
    expect(toRhymeError(new Error('boom'), 'query').kind).toBe('unknown');
    expect(toRhymeError('boom', 'query').message).toBe('boom');
  });

  it('passes through an existing RhymeError', () => {
    const orig = new RhymeError('server', 'query', 'x', 503);
    expect(toRhymeError(orig, 'promote')).toBe(orig);
  });
});
