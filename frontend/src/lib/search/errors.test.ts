import { describe, it, expect } from 'vitest';
import { AxiosError } from 'axios';
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { SearchError, checkGlobalQuery, describeSearchError, toSearchError, MAX_GLOBAL_QUERY_LENGTH } from './errors';

function axiosFailure(status: number | null, data: unknown = null): AxiosError {
  const config = { headers: {} } as InternalAxiosRequestConfig;
  if (status === null) return new AxiosError('Network Error', AxiosError.ERR_NETWORK, config);
  const response: AxiosResponse = { status, statusText: '', data, headers: {}, config };
  return new AxiosError('Request failed', AxiosError.ERR_BAD_RESPONSE, config, {}, response);
}

describe('toSearchError', () => {
  it('maps the contract code with its server message', () => {
    const err = toSearchError(axiosFailure(422, { detail: { error: 'invalid_search_query', message: 'q must be 1 to 200 characters' } }));
    expect(err).toBeInstanceOf(SearchError);
    expect(err).toMatchObject({ kind: 'invalid_search_query', status: 422, message: 'q must be 1 to 200 characters' });
  });

  it('maps a missing response to network and other statuses by class', () => {
    expect(toSearchError(axiosFailure(null)).kind).toBe('network');
    expect(toSearchError(axiosFailure(503)).kind).toBe('server');
    expect(toSearchError(axiosFailure(422, { detail: [{ msg: 'bad' }] })).kind).toBe('validation');
    expect(toSearchError(axiosFailure(418)).kind).toBe('http');
    expect(toSearchError(new Error('boom')).kind).toBe('http');
  });

  it('passes a SearchError through', () => {
    const e = new SearchError('network', 'x');
    expect(toSearchError(e)).toBe(e);
  });
});

describe('describeSearchError', () => {
  it('has a message for each kind', () => {
    expect(describeSearchError(new SearchError('invalid_search_query', 'q too long', 422))).toBe('q too long');
    expect(describeSearchError(new SearchError('invalid_search_query', ''))).toMatch(/1 to 200/);
    expect(describeSearchError(new SearchError('network', 'Network Error'))).toBe('Could not reach the server');
    expect(describeSearchError(new SearchError('server', 'HTTP 500', 500))).toMatch(/try again/);
  });
});

describe('checkGlobalQuery', () => {
  it('trims and accepts 1 to 200 characters', () => {
    expect(checkGlobalQuery('  sea ')).toEqual({ kind: 'query', q: 'sea' });
    expect(checkGlobalQuery('x'.repeat(MAX_GLOBAL_QUERY_LENGTH))).toEqual({ kind: 'query', q: 'x'.repeat(200) });
  });

  it('is blank below one character, so the page shows the normal list', () => {
    expect(checkGlobalQuery('   ')).toEqual({ kind: 'blank' });
  });

  it('refuses more than 200 characters locally', () => {
    const r = checkGlobalQuery('x'.repeat(201));
    expect(r.kind).toBe('invalid');
    if (r.kind === 'invalid') expect(r.error.kind).toBe('invalid_search_query');
  });

  it('counts code points, not UTF-16 units', () => {
    expect(checkGlobalQuery('😀'.repeat(200)).kind).toBe('query');
  });
});
