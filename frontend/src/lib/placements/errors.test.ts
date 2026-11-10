import { describe, it, expect } from 'vitest';
import { AxiosError, AxiosHeaders } from 'axios';
import { PlacementError, describePlacementError, isPlacementServerErrorCode, toPlacementError } from './errors';

function axiosErr(status: number | null, data: unknown = {}): AxiosError {
  const config = { headers: new AxiosHeaders() };
  return new AxiosError(
    'Request failed',
    'E',
    config,
    undefined,
    status === null ? undefined : { status, statusText: '', headers: {}, config, data },
  );
}

const coded = (error: string, message = 'server says') => ({ detail: { error, message } });

describe('toPlacementError', () => {
  it.each([
    ['invalid_canvas_title', 422],
    ['placement_not_found', 404],
    ['canvas_not_found', 404],
    ['same_canvas', 422],
  ] as const)('maps the contract code %s', (code, status) => {
    const e = toPlacementError(axiosErr(status, coded(code)), 'transfer');
    expect(e).toBeInstanceOf(PlacementError);
    expect(e.kind).toBe(code);
    expect(e.status).toBe(status);
    expect(e.operation).toBe('transfer');
    expect(e.message).toBe('server says');
  });

  it('falls back on the status without a contract code', () => {
    expect(toPlacementError(axiosErr(404, { detail: 'Snippet not found' }), 'delete_everywhere').kind).toBe('not_found');
    expect(toPlacementError(axiosErr(422, { detail: [{ msg: 'field required' }] }), 'update_snippet')).toMatchObject({
      kind: 'validation',
      message: 'field required',
    });
    expect(toPlacementError(axiosErr(503), 'save_positions').kind).toBe('server');
    expect(toPlacementError(axiosErr(409), 'save_positions').kind).toBe('http');
    expect(toPlacementError(axiosErr(404, coded('tag_not_found')), 'transfer').kind).toBe('not_found');
  });

  it('a missing response is a network error', () => {
    expect(toPlacementError(axiosErr(null), 'rename_canvas')).toMatchObject({ kind: 'network', status: null });
  });

  it('passes a PlacementError through and wraps anything else', () => {
    const e = new PlacementError('same_canvas', 'transfer', 'x');
    expect(toPlacementError(e, 'rename_canvas')).toBe(e);
    expect(toPlacementError(new Error('boom'), 'transfer')).toMatchObject({ kind: 'http', message: 'boom' });
    expect(toPlacementError('weird', 'transfer')).toMatchObject({ kind: 'http', message: 'weird' });
  });
});

describe('isPlacementServerErrorCode', () => {
  it('accepts exactly the contract codes', () => {
    expect(isPlacementServerErrorCode('same_canvas')).toBe(true);
    expect(isPlacementServerErrorCode('tag_not_found')).toBe(false);
    expect(isPlacementServerErrorCode(3)).toBe(false);
  });
});

describe('describePlacementError', () => {
  it('gives every contract code and client kind a human message', () => {
    const kinds = [
      'invalid_canvas_title',
      'placement_not_found',
      'canvas_not_found',
      'same_canvas',
      'no_canvas',
      'nothing_selected',
      'too_many_snippets',
      'not_found',
      'validation',
      'network',
      'server',
      'http',
    ] as const;
    for (const kind of kinds) {
      expect(describePlacementError(new PlacementError(kind, 'transfer', '')).length).toBeGreaterThan(0);
    }
  });

  it('uses fixed wording for contract codes and appends server text otherwise', () => {
    expect(describePlacementError(new PlacementError('same_canvas', 'transfer', 'raw'))).toBe(
      'The snippets are already on this canvas; pick another one',
    );
    expect(describePlacementError(new PlacementError('server', 'transfer', 'db down'))).toBe(
      'The server failed; try again: db down',
    );
  });

  it('keeps a local title problem message for invalid_canvas_title', () => {
    const e = new PlacementError('invalid_canvas_title', 'rename_canvas', 'Titles are at most 255 characters', null);
    expect(describePlacementError(e)).toBe('Titles are at most 255 characters');
  });
});
