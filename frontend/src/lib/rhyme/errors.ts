import axios from 'axios';
import { RhymeError } from '@/types/rhyme';
import type { RhymeOperation } from '@/types/rhyme';

function detailFromBody(data: unknown): string | null {
  if (typeof data !== 'object' || data === null) return null;
  const detail = (data as { detail?: unknown }).detail;
  if (typeof detail === 'string') return detail;
  if (Array.isArray(detail)) {
    const msgs = detail
      .map((d) => (typeof d === 'object' && d !== null ? (d as { msg?: unknown }).msg : null))
      .filter((m): m is string => typeof m === 'string');
    if (msgs.length > 0) return msgs.join('; ');
  }
  return null;
}

/** Map any thrown value from an API call into a RhymeError. */
export function toRhymeError(err: unknown, operation: RhymeOperation): RhymeError {
  if (err instanceof RhymeError) return err;
  if (axios.isAxiosError(err)) {
    const status = err.response?.status ?? null;
    if (status === null) {
      return new RhymeError('network', operation, err.message || 'Network error', null);
    }
    const detail = detailFromBody(err.response?.data) ?? err.message;
    if (status === 404) return new RhymeError('not_found', operation, detail, status);
    if (status === 422 || status === 400) return new RhymeError('invalid_request', operation, detail, status);
    return new RhymeError('server', operation, detail, status);
  }
  if (err instanceof Error) return new RhymeError('unknown', operation, err.message, null);
  return new RhymeError('unknown', operation, String(err), null);
}
