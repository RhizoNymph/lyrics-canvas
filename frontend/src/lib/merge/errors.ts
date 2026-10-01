import axios from 'axios';
import { MergeError } from './types';
import type { MergeErrorKind } from './types';

function detailFromBody(data: unknown): string | null {
  if (typeof data !== 'object' || data === null) return null;
  const detail = (data as { detail?: unknown }).detail;
  return typeof detail === 'string' ? detail : null;
}

/** Map any thrown value from an API call into a MergeError of the given kind. */
export function toMergeError(err: unknown, kind: MergeErrorKind, ids: readonly string[] = []): MergeError {
  if (err instanceof MergeError) return err;
  if (axios.isAxiosError(err)) {
    const status = err.response?.status ?? null;
    const message = detailFromBody(err.response?.data) ?? err.message ?? 'Request failed';
    return new MergeError(kind, message, status, ids);
  }
  if (err instanceof Error) return new MergeError(kind, err.message, null, ids);
  return new MergeError(kind, String(err), null, ids);
}
