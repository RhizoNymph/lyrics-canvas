import { describe, it, expect } from 'vitest';
import { AxiosError, AxiosHeaders } from 'axios';
import { TAG_SERVER_ERROR_CODES } from '@/lib/tags';
import {
  DEFAULT_RETRY_AFTER_SECONDS, WORD_BANK_SERVER_ERROR_CODES, WordBankApiError, describeWordBankError, parseRetryAfter,
  toWordBankApiError,
} from './errors';

function axiosErr(status: number | null, data?: unknown, headers: Record<string, string> = {}): AxiosError {
  const config = { headers: new AxiosHeaders() };
  const response = status === null ? undefined : { status, statusText: '', headers, config, data };
  return new AxiosError('Request failed', status === null ? 'ERR_NETWORK' : 'ERR_BAD_REQUEST', config, undefined, response);
}

describe('toWordBankApiError', () => {
  it.each(WORD_BANK_SERVER_ERROR_CODES.map((c) => [c]))('maps the word bank code %s', (code) => {
    const e = toWordBankApiError(axiosErr(422, { detail: { error: code, message: 'srv' } }), 'add_words');
    expect(e).toBeInstanceOf(WordBankApiError);
    expect(e.kind).toBe(code);
    expect(e.status).toBe(422);
    expect(e.message).toBe('srv');
    expect(e.operation).toBe('add_words');
  });

  it.each(TAG_SERVER_ERROR_CODES.map((c) => [c]))('maps the tag code %s', (code) => {
    expect(toWordBankApiError(axiosErr(404, { detail: { error: code, message: 'm' } }), 'link_words').kind).toBe(code);
  });

  it('carries the index of invalid_word', () => {
    const e = toWordBankApiError(axiosErr(422, { detail: { error: 'invalid_word', message: 'bad', index: 3 } }), 'add_words');
    expect(e.detail).toEqual({ kind: 'invalid_word', index: 3 });
  });

  it('has a null index for invalid_word without one (rename, related q)', () => {
    const e = toWordBankApiError(axiosErr(422, { detail: { error: 'invalid_word', message: 'bad' } }), 'rename_word');
    expect(e.detail).toEqual({ kind: 'invalid_word', index: null });
    const neg = toWordBankApiError(axiosErr(422, { detail: { error: 'invalid_word', index: -1 } }), 'add_words');
    expect(neg.detail).toEqual({ kind: 'invalid_word', index: null });
  });

  it('reads Retry-After for related_words_warming', () => {
    const e = toWordBankApiError(
      axiosErr(503, { detail: { error: 'related_words_warming', message: 'w' } }, { 'retry-after': '7' }),
      'related',
    );
    expect(e.detail).toEqual({ kind: 'related_words_warming', retryAfterSeconds: 7 });
  });

  it('defaults Retry-After to 10 seconds when missing', () => {
    const e = toWordBankApiError(axiosErr(503, { detail: { error: 'related_words_warming' } }), 'related');
    expect(e.detail).toEqual({ kind: 'related_words_warming', retryAfterSeconds: DEFAULT_RETRY_AFTER_SECONDS });
  });

  it('reads Retry-After from AxiosHeaders too', () => {
    const headers = new AxiosHeaders({ 'Retry-After': '3' });
    const config = { headers: new AxiosHeaders() };
    const err = new AxiosError('x', 'E', config, undefined, {
      status: 503, statusText: '', headers, config, data: { detail: { error: 'related_words_warming' } },
    });
    expect(toWordBankApiError(err, 'related').detail).toEqual({ kind: 'related_words_warming', retryAfterSeconds: 3 });
  });

  it('carries the expression error of invalid_tag_expression', () => {
    const e = toWordBankApiError(
      axiosErr(422, {
        detail: { error: 'invalid_tag_expression', message: 'bad', expression_error: { code: 'unexpected_end', position: 4, message: 'ends' } },
      }),
      'families',
    );
    expect(e.detail).toEqual({ kind: 'invalid_tag_expression', expressionError: { code: 'unexpected_end', position: 4, message: 'ends' } });
  });

  it('falls back by status for unknown or missing codes', () => {
    expect(toWordBankApiError(axiosErr(404, { detail: 'Not Found' }), 'load_words').kind).toBe('not_found');
    expect(toWordBankApiError(axiosErr(422, { detail: [{ msg: 'field required' }] }), 'rhymes')).toMatchObject({
      kind: 'validation', message: 'field required',
    });
    expect(toWordBankApiError(axiosErr(503, { detail: { error: 'other' } }), 'related').kind).toBe('server');
    expect(toWordBankApiError(axiosErr(409, {}), 'rename_word').kind).toBe('http');
    expect(toWordBankApiError(axiosErr(null), 'load_words').kind).toBe('network');
  });

  it('wraps non-axios failures and passes WordBankApiError through', () => {
    expect(toWordBankApiError(new Error('boom'), 'add_words')).toMatchObject({ kind: 'http', message: 'boom' });
    expect(toWordBankApiError('weird', 'add_words').kind).toBe('http');
    const same = new WordBankApiError({ kind: 'word_exists' }, 'rename_word', 'x');
    expect(toWordBankApiError(same, 'add_words')).toBe(same);
  });
});

describe('parseRetryAfter', () => {
  it('parses whole seconds and clamps', () => {
    expect(parseRetryAfter('10')).toBe(10);
    expect(parseRetryAfter(' 4 ')).toBe(4);
    expect(parseRetryAfter('0')).toBe(1);
    expect(parseRetryAfter('9999')).toBe(60);
    expect(parseRetryAfter('soon')).toBe(DEFAULT_RETRY_AFTER_SECONDS);
    expect(parseRetryAfter(undefined)).toBe(DEFAULT_RETRY_AFTER_SECONDS);
    expect(parseRetryAfter(5)).toBe(5);
  });
});

describe('describeWordBankError', () => {
  it('names the word of an invalid batch entry', () => {
    const e = new WordBankApiError({ kind: 'invalid_word', index: 1 }, 'add_words', 'bad');
    expect(describeWordBankError(e, ['ok', 'bad1'])).toContain('"bad1"');
    expect(describeWordBankError(e)).toContain('bad');
  });

  it('has fixed wording for the related-word states', () => {
    expect(describeWordBankError(new WordBankApiError({ kind: 'related_words_warming', retryAfterSeconds: 10 }, 'related', ''))).toBe(
      'Building related-word index…',
    );
    expect(describeWordBankError(new WordBankApiError({ kind: 'related_words_unavailable' }, 'related', ''))).toBe(
      'Related words are unavailable on this server',
    );
  });

  it('has a message for every code', () => {
    for (const code of WORD_BANK_SERVER_ERROR_CODES) {
      const detail =
        code === 'invalid_word'
          ? ({ kind: code, index: null } as const)
          : code === 'related_words_warming'
            ? ({ kind: code, retryAfterSeconds: 1 } as const)
            : ({ kind: code } as const);
      expect(describeWordBankError(new WordBankApiError(detail, 'add_words', '')).length).toBeGreaterThan(0);
    }
    expect(describeWordBankError(new WordBankApiError({ kind: 'word_exists' }, 'rename_word', ''))).toMatch(/already/i);
    expect(describeWordBankError(new WordBankApiError({ kind: 'too_many_words' }, 'families', ''))).toMatch(/1000|too many/i);
    expect(describeWordBankError(new WordBankApiError({ kind: 'network' }, 'load_words', 'x'))).toBe('Could not reach the server');
  });
});
