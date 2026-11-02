/**
 * Wire types for the voice-take API (docs/features/voice_capture.md, contract §2) and the client-side
 * error types for capture and upload. Every server error code in the contract is a member of
 * `VoiceServerErrorCode`; anything else the client can hit (network, aborted, unknown statuses) has
 * its own `VoiceErrorKind`.
 */
import type { Snippet } from '@/types';

export type SplitMode = 'taps' | 'auto' | 'single';

/** Where a take's lines go: a new snippet at a canvas position, or appended to an existing snippet. */
export type VoiceTakeTarget =
  | { kind: 'new'; position_x: number; position_y: number }
  | { kind: 'append'; snippet_id: string };

/** The `meta` multipart field of `POST /api/canvases/{id}/voice-takes`, JSON-encoded. */
export interface VoiceTakeMeta {
  /** Ms since the recorder started for each committed single tap, ascending; may be empty. */
  breaks_ms: number[];
  duration_ms: number;
  target: VoiceTakeTarget;
}

/** A stored recording without its audio bytes. */
export interface RecordingResponse {
  id: string;
  snippet_id: string;
  mime_type: string;
  duration_ms: number;
  byte_size: number;
  /** The take's lines joined by `\n`, as assembled by the server. */
  transcript: string;
  split_mode: SplitMode;
  created_at: string;
}

/** 201 body of `POST /api/canvases/{id}/voice-takes`. */
export interface VoiceTakeResponse {
  snippet: Snippet;
  recording: RecordingResponse;
  lines: string[];
  split_mode: SplitMode;
  /** True when a new snippet was created, false when lines were appended. */
  created: boolean;
}

export const VOICE_SERVER_ERROR_CODES = [
  'canvas_not_found',
  'snippet_not_found',
  'audio_too_large',
  'unsupported_audio_type',
  'invalid_meta',
  'empty_transcript',
  'stt_disabled',
  'stt_unavailable',
  'stt_failed',
] as const;

/** `detail.error` values the voice endpoints return. */
export type VoiceServerErrorCode = (typeof VOICE_SERVER_ERROR_CODES)[number];

/**
 * Why a voice API call failed.
 * - a server code from the contract;
 * - `server_unavailable`: 502/503/504 without a contract code (nginx answered; the backend is down);
 * - `network`: no response at all;
 * - `aborted`: the client cancelled the request;
 * - `editor_save_failed`: flushing the open editor before an append failed, nothing was uploaded;
 * - `not_found`: 404 without a contract code (e.g. a recording that is already gone);
 * - `http`: any other status.
 */
export type VoiceErrorKind =
  | VoiceServerErrorCode
  | 'server_unavailable'
  | 'network'
  | 'aborted'
  | 'editor_save_failed'
  | 'not_found'
  | 'http';

export class VoiceError extends Error {
  readonly kind: VoiceErrorKind;
  /** HTTP status when the server answered; null otherwise. */
  readonly status: number | null;

  constructor(kind: VoiceErrorKind, message: string, status: number | null = null) {
    super(message);
    this.name = 'VoiceError';
    this.kind = kind;
    this.status = status;
  }
}

/** Why the microphone or recorder could not be used. */
export type CaptureErrorKind =
  | 'insecure_context'
  | 'unsupported'
  | 'permission_denied'
  | 'no_microphone'
  | 'microphone_busy'
  | 'recorder_failed';

export class CaptureError extends Error {
  readonly kind: CaptureErrorKind;

  constructor(kind: CaptureErrorKind, message: string) {
    super(message);
    this.name = 'CaptureError';
    this.kind = kind;
  }
}

/** A finished recording held in memory until it is uploaded or discarded. */
export interface Take {
  blob: Blob;
  mimeType: string;
  durationMs: number;
  breaksMs: readonly number[];
}
