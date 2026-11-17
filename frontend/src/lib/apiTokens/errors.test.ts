import { describe, it, expect } from 'vitest';
import { AxiosError } from 'axios';
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { ApiTokenError, describeApiTokenError, toApiTokenError } from './errors';

const config = { headers: {} } as InternalAxiosRequestConfig;

function httpError(status: number, data: unknown): AxiosError {
  const response = { status, statusText: '', data, headers: {}, config } as AxiosResponse;
  return new AxiosError('Request failed', AxiosError.ERR_BAD_RESPONSE, config, {}, response);
}

describe('toApiTokenError', () => {
  it('maps the contract codes', () => {
    expect(toApiTokenError(httpError(422, { detail: { error: 'invalid_token_name', message: 'name must be 1-64 characters' } })))
      .toMatchObject({ kind: 'invalid_token_name', status: 422, message: 'name must be 1-64 characters' });
    expect(toApiTokenError(httpError(404, { detail: { error: 'token_not_found' } })))
      .toMatchObject({ kind: 'token_not_found', status: 404 });
    expect(toApiTokenError(httpError(403, { detail: { error: 'token_management_forbidden' } })))
      .toMatchObject({ kind: 'token_management_forbidden', status: 403 });
  });

  it('classes other failures by status', () => {
    expect(toApiTokenError(httpError(422, { detail: [{ msg: 'bad expiry' }] }))).toMatchObject({ kind: 'validation', message: 'bad expiry' });
    expect(toApiTokenError(httpError(503, {}))).toMatchObject({ kind: 'server', status: 503 });
    expect(toApiTokenError(httpError(409, {}))).toMatchObject({ kind: 'http', status: 409 });
  });

  it('is network without a response, and aborted on cancel', () => {
    expect(toApiTokenError(new AxiosError('Network Error', AxiosError.ERR_NETWORK, config))).toMatchObject({ kind: 'network', status: null });
    expect(toApiTokenError(new AxiosError('canceled', AxiosError.ERR_CANCELED, config))).toMatchObject({ kind: 'aborted' });
  });

  it('passes an ApiTokenError through and wraps anything else', () => {
    const e = new ApiTokenError('server', 'x', 500);
    expect(toApiTokenError(e)).toBe(e);
    expect(toApiTokenError(new Error('boom'))).toMatchObject({ kind: 'http', message: 'boom' });
    expect(toApiTokenError('weird')).toMatchObject({ kind: 'http', message: 'weird' });
  });
});

describe('describeApiTokenError', () => {
  it('has a line per kind', () => {
    expect(describeApiTokenError(new ApiTokenError('invalid_token_name', ''))).toBe('Name the token (1 to 64 characters)');
    expect(describeApiTokenError(new ApiTokenError('token_not_found', ''))).toBe('That token no longer exists');
    expect(describeApiTokenError(new ApiTokenError('token_management_forbidden', ''))).toBe(
      'Tokens can only be managed from a signed-in session, not with an API token',
    );
    expect(describeApiTokenError(new ApiTokenError('network', ''))).toBe('Could not reach the server');
    expect(describeApiTokenError(new ApiTokenError('server', 'oops'))).toBe('The server failed; try again: oops');
    expect(describeApiTokenError(new ApiTokenError('http', ''))).toBe('Request failed');
  });
});
