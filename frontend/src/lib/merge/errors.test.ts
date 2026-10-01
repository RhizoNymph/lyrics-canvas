import { describe, it, expect } from 'vitest';
import { AxiosError, AxiosHeaders } from 'axios';
import { toMergeError } from './errors';
import { MergeError } from './types';

function axiosErr(status: number | null, detail?: string): AxiosError {
  const config = { headers: new AxiosHeaders() };
  return new AxiosError(
    'fail',
    'E',
    config,
    undefined,
    status === null ? undefined : { status, statusText: '', headers: {}, config, data: detail ? { detail } : {} }
  );
}

describe('MergeError', () => {
  it('derives its level from the kind', () => {
    expect(new MergeError('create_failed', 'x').level).toBe('error');
    expect(new MergeError('too_few_selected', 'x').level).toBe('error');
    expect(new MergeError('delete_failed', 'x').level).toBe('warning');
    expect(new MergeError('tag_failed', 'x').level).toBe('warning');
  });
});

describe('toMergeError', () => {
  it('passes a MergeError through', () => {
    const e = new MergeError('no_canvas', 'x');
    expect(toMergeError(e, 'create_failed')).toBe(e);
  });

  it('maps axios errors with status and detail', () => {
    const e = toMergeError(axiosErr(404, 'Snippet not found'), 'delete_failed', ['s1']);
    expect(e).toMatchObject({ kind: 'delete_failed', level: 'warning', status: 404, message: 'Snippet not found', ids: ['s1'] });
  });

  it('maps a network error to a null status', () => {
    const e = toMergeError(axiosErr(null), 'create_failed');
    expect(e).toMatchObject({ kind: 'create_failed', status: null, message: 'fail' });
  });

  it('maps plain errors and other throwables', () => {
    expect(toMergeError(new Error('boom'), 'tag_failed').message).toBe('boom');
    expect(toMergeError('boom', 'tag_failed').message).toBe('boom');
  });
});
