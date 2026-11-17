import { describe, it, expect } from 'vitest';
import { AxiosError } from 'axios';
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { describeScoreApiError, toScoreApiError } from './errors';
import { SCORE_SERVER_ERROR_CODES, ScoreApiError } from '@/types/scoreTools';
import type { ScoreApiErrorKind } from '@/types/scoreTools';

function httpError(status: number, data: unknown): AxiosError {
  const config = { headers: {} } as InternalAxiosRequestConfig;
  const response = { status, statusText: '', data, headers: {}, config } as AxiosResponse;
  return new AxiosError('Request failed', AxiosError.ERR_BAD_RESPONSE, config, {}, response);
}

describe('toScoreApiError', () => {
  it.each(SCORE_SERVER_ERROR_CODES.filter((c) => c !== 'invalid_score'))('maps the %s code', (code) => {
    const err = toScoreApiError(httpError(404, { detail: { error: code, message: 'gone' } }), 'draft');
    expect(err).toBeInstanceOf(ScoreApiError);
    expect(err.kind).toBe(code);
    expect(err.operation).toBe('draft');
    expect(err.status).toBe(404);
    expect(err.message).toBe('gone');
    expect(err.reason).toBeNull();
  });

  it('reads invalid_score with its reason, line and note', () => {
    const err = toScoreApiError(httpError(422, { detail: { error: 'invalid_score', reason: 'notes_overlap', line: 1, note: 2 } }), 'draft');
    expect(err.kind).toBe('invalid_score');
    expect(err.reason).toBe('notes_overlap');
    expect(err.line).toBe(1);
    expect(err.note).toBe(2);
  });

  it('reads invalid_score without a known reason as malformed', () => {
    const err = toScoreApiError(httpError(422, { detail: { error: 'invalid_score', reason: 'new_reason' } }), 'draft');
    expect(err.kind).toBe('invalid_score');
    expect(err.reason).toBe('malformed');
    expect(err.line).toBeNull();
  });

  it('reads a code at the top level of the body too', () => {
    const err = toScoreApiError(httpError(422, { error: 'draft_empty' }), 'draft');
    expect(err.kind).toBe('draft_empty');
  });

  it('falls back on the status without a known code', () => {
    const cases: [number, unknown, ScoreApiErrorKind][] = [
      [422, { detail: [{ loc: ['body', 'bpm'], msg: 'bad' }] }, 'invalid_request'],
      [404, { detail: 'Not Found' }, 'not_found'],
      [502, '<html>bad gateway</html>', 'server_unavailable'],
      [503, null, 'server_unavailable'],
      [504, null, 'server_unavailable'],
      [500, { detail: { error: 'mystery' } }, 'http'],
    ];
    for (const [status, data, kind] of cases) {
      const err = toScoreApiError(httpError(status, data), 'export');
      expect(err.kind).toBe(kind);
      expect(err.status).toBe(status);
      expect(err.operation).toBe('export');
    }
  });

  it('maps no response to network and a cancel to aborted', () => {
    const network = new AxiosError('Network Error', AxiosError.ERR_NETWORK);
    expect(toScoreApiError(network, 'draft').kind).toBe('network');
    const cancel = new AxiosError('canceled', AxiosError.ERR_CANCELED);
    expect(toScoreApiError(cancel, 'draft').kind).toBe('aborted');
  });

  it('passes a ScoreApiError through and wraps anything else as http', () => {
    const own = new ScoreApiError('draft_empty', 'draft', 'x', 422);
    expect(toScoreApiError(own, 'draft')).toBe(own);
    expect(toScoreApiError(new Error('boom'), 'export').kind).toBe('http');
    expect(toScoreApiError('boom', 'export').message).toBe('boom');
  });
});

describe('describeScoreApiError', () => {
  const kinds: ScoreApiErrorKind[] = [
    ...SCORE_SERVER_ERROR_CODES,
    'invalid_request',
    'not_found',
    'server_unavailable',
    'network',
    'aborted',
    'http',
  ];

  it.each(kinds)('describes %s with a title', (kind) => {
    const d = describeScoreApiError(new ScoreApiError(kind, 'draft', 'm'));
    expect(d.title.length).toBeGreaterThan(0);
  });

  it('explains a take without timings', () => {
    const d = describeScoreApiError(new ScoreApiError('recording_has_no_timings', 'draft', 'm', 422));
    expect(d.title).toMatch(/timings/i);
    expect(d.retryable).toBe(false);
  });

  it('names the invalid_score reason and position', () => {
    const d = describeScoreApiError(
      new ScoreApiError('invalid_score', 'draft', 'm', 422, { reason: 'exceeds_max_ticks', line: 3, note: null }),
    );
    expect(d.title).toMatch(/invalid/i);
    expect(d.hint).toContain('exceeds_max_ticks');
    expect(d.hint).toContain('line 4');
  });

  it('marks transient failures retryable', () => {
    expect(describeScoreApiError(new ScoreApiError('network', 'export', 'm')).retryable).toBe(true);
    expect(describeScoreApiError(new ScoreApiError('server_unavailable', 'export', 'm', 503)).retryable).toBe(true);
    expect(describeScoreApiError(new ScoreApiError('score_not_found', 'export', 'm', 404)).retryable).toBe(false);
  });
});
