import axios from 'axios';
import { CaptureError, VOICE_SERVER_ERROR_CODES, VoiceError } from './types';
import type { CaptureErrorKind, VoiceErrorKind, VoiceServerErrorCode } from './types';

/** What the modal shows for a failure, and whether Retry makes sense. */
export interface ErrorDescription {
  title: string;
  /** Extra guidance, or null. */
  hint: string | null;
  retryable: boolean;
}

function isServerCode(value: unknown): value is VoiceServerErrorCode {
  return typeof value === 'string' && (VOICE_SERVER_ERROR_CODES as readonly string[]).includes(value);
}

/** `{detail: {error, message}}` as the voice endpoints send it, or nulls. */
function parseDetail(data: unknown): { code: VoiceServerErrorCode | null; message: string | null } {
  if (typeof data !== 'object' || data === null) return { code: null, message: null };
  const detail = (data as { detail?: unknown }).detail;
  if (typeof detail === 'string') return { code: null, message: detail };
  if (typeof detail !== 'object' || detail === null || Array.isArray(detail)) return { code: null, message: null };
  const { error, message } = detail as { error?: unknown; message?: unknown };
  return { code: isServerCode(error) ? error : null, message: typeof message === 'string' ? message : null };
}

/** Per-endpoint overrides of the status fallback, e.g. `{ 413: 'export_too_large' }` for the export. */
export type StatusKinds = Partial<Record<number, VoiceErrorKind>>;

function kindForStatus(status: number, overrides: StatusKinds): VoiceErrorKind {
  const override = overrides[status];
  if (override !== undefined) return override;
  switch (status) {
    case 413:
      return 'audio_too_large';
    case 415:
      return 'unsupported_audio_type';
    case 404:
      return 'not_found';
    case 502:
    case 503:
    case 504:
      return 'server_unavailable';
    default:
      return 'http';
  }
}

/**
 * Map anything a voice API call threw into a VoiceError. A contract code in the body always wins;
 * otherwise the status decides, with `statusKinds` overriding the upload-oriented defaults.
 */
export function toVoiceError(err: unknown, statusKinds: StatusKinds = {}): VoiceError {
  if (err instanceof VoiceError) return err;
  if (axios.isAxiosError(err)) {
    if (err.code === axios.AxiosError.ERR_CANCELED) return new VoiceError('aborted', 'Upload cancelled');
    const response = err.response;
    if (!response) return new VoiceError('network', err.message || 'Network error');
    const { code, message } = parseDetail(response.data);
    const kind = code ?? kindForStatus(response.status, statusKinds);
    return new VoiceError(kind, message ?? err.message ?? `HTTP ${response.status}`, response.status);
  }
  if (err instanceof Error) return new VoiceError('http', err.message);
  return new VoiceError('http', String(err));
}

const VOICE_DESCRIPTIONS: Record<VoiceErrorKind, ErrorDescription> = {
  canvas_not_found: { title: 'This canvas no longer exists', hint: null, retryable: false },
  snippet_not_found: {
    title: 'The snippet was deleted',
    hint: 'Save the audio file if you want to keep the take.',
    retryable: false,
  },
  audio_too_large: { title: 'The recording is too long to upload', hint: 'Record shorter takes (25 MB limit).', retryable: false },
  unsupported_audio_type: { title: 'The server cannot read this audio format', hint: null, retryable: false },
  invalid_meta: { title: 'The upload was rejected', hint: null, retryable: false },
  empty_transcript: { title: 'No words were recognised', hint: 'Try again closer to the microphone.', retryable: false },
  stt_disabled: { title: 'Transcription is turned off on this server', hint: null, retryable: false },
  stt_unavailable: { title: 'The transcription service is not ready', hint: 'It may still be loading; try again shortly.', retryable: true },
  stt_failed: { title: 'Transcription failed', hint: null, retryable: true },
  server_unavailable: { title: 'The server is not responding', hint: null, retryable: true },
  network: { title: 'Could not reach the server', hint: 'Check your connection.', retryable: true },
  aborted: { title: 'The upload was cancelled', hint: null, retryable: true },
  editor_save_failed: { title: 'Saving the open editor failed', hint: 'Nothing was uploaded yet.', retryable: true },
  recording_not_found: { title: 'This recording no longer exists', hint: null, retryable: false },
  invalid_verification: {
    title: 'The transcript cannot be saved',
    hint: 'It must not be empty and must be at most 10 000 characters.',
    retryable: false,
  },
  export_too_large: {
    title: 'The dataset is too large to export',
    hint: 'Export only checked takes, or delete recordings you do not need.',
    retryable: false,
  },
  not_found: { title: 'Not found', hint: null, retryable: false },
  http: { title: 'The upload failed', hint: null, retryable: true },
};

export function describeVoiceError(error: VoiceError): ErrorDescription {
  return VOICE_DESCRIPTIONS[error.kind];
}

/** A 404 on a recording route: the contract code, or a bare 404 from an older backend or proxy. */
export function isRecordingGone(error: VoiceError): boolean {
  return error.kind === 'recording_not_found' || error.kind === 'not_found';
}

const CAPTURE_DESCRIPTIONS: Record<CaptureErrorKind, ErrorDescription> = {
  insecure_context: { title: 'Recording needs HTTPS', hint: null, retryable: false },
  unsupported: { title: 'This browser cannot record audio', hint: null, retryable: false },
  permission_denied: {
    title: 'Microphone access was blocked',
    hint: 'Allow the microphone for this site in the browser, then retry.',
    retryable: true,
  },
  no_microphone: { title: 'No microphone was found', hint: null, retryable: true },
  microphone_busy: { title: 'The microphone is in use or unavailable', hint: null, retryable: true },
  recorder_failed: { title: 'Recording failed', hint: null, retryable: true },
};

export function describeCaptureError(error: CaptureError): ErrorDescription {
  return CAPTURE_DESCRIPTIONS[error.kind];
}

/** Map a getUserMedia / MediaRecorder failure (DOMException names) into a CaptureError. */
export function captureErrorFromException(err: unknown): CaptureError {
  if (err instanceof CaptureError) return err;
  const name = typeof err === 'object' && err !== null ? (err as { name?: unknown }).name : undefined;
  const message = err instanceof Error ? err.message : String(err);
  switch (name) {
    case 'NotAllowedError':
    case 'SecurityError':
      return new CaptureError('permission_denied', message);
    case 'NotFoundError':
    case 'OverconstrainedError':
      return new CaptureError('no_microphone', message);
    case 'NotReadableError':
    case 'AbortError':
      return new CaptureError('microphone_busy', message);
    default:
      return new CaptureError('recorder_failed', message);
  }
}
