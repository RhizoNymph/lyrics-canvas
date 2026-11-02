/**
 * Can this page record, and in which container format. Pure over an injected environment; the browser
 * probe is a thin wrapper so the decision itself is testable.
 */
export interface RecordingEnvironment {
  isSecureContext: boolean;
  hasMediaDevices: boolean;
  hasMediaRecorder: boolean;
}

export type RecordingSupport =
  | { ok: true }
  | { ok: false; reason: 'insecure_context' | 'unsupported'; message: string };

export const HTTPS_REQUIRED_MESSAGE = 'Recording needs HTTPS';
export const UNSUPPORTED_MESSAGE = 'This browser cannot record audio';

export function recordingSupport(env: RecordingEnvironment): RecordingSupport {
  // Browsers hide navigator.mediaDevices outside secure contexts, so both mean "needs HTTPS".
  if (!env.isSecureContext || !env.hasMediaDevices) {
    return { ok: false, reason: 'insecure_context', message: HTTPS_REQUIRED_MESSAGE };
  }
  if (!env.hasMediaRecorder) return { ok: false, reason: 'unsupported', message: UNSUPPORTED_MESSAGE };
  return { ok: true };
}

export function browserRecordingEnvironment(): RecordingEnvironment {
  const g = globalThis as {
    isSecureContext?: boolean;
    navigator?: { mediaDevices?: { getUserMedia?: unknown } };
    MediaRecorder?: unknown;
  };
  return {
    isSecureContext: g.isSecureContext === true,
    hasMediaDevices: typeof g.navigator?.mediaDevices?.getUserMedia === 'function',
    hasMediaRecorder: typeof g.MediaRecorder === 'function',
  };
}

/** Container preference: Opus in WebM (Chrome, Firefox), then MPEG-4/AAC (Safari), then looser fallbacks. */
export const MIME_PREFERENCE = ['audio/webm;codecs=opus', 'audio/mp4', 'audio/webm', 'audio/ogg;codecs=opus'] as const;

/** The first preferred type the recorder supports, or null to let the browser pick its default. */
export function pickMimeType(isTypeSupported: (mime: string) => boolean): string | null {
  for (const mime of MIME_PREFERENCE) {
    if (isTypeSupported(mime)) return mime;
  }
  return null;
}

/** A filename for the multipart part, by container. */
export function uploadFileName(mime: string): string {
  const base = mime.split(';')[0].trim().toLowerCase();
  switch (base) {
    case 'audio/webm':
      return 'take.webm';
    case 'audio/mp4':
      return 'take.m4a';
    case 'audio/ogg':
      return 'take.ogg';
    case 'audio/aac':
      return 'take.aac';
    case 'audio/mpeg':
      return 'take.mp3';
    case 'audio/wav':
    case 'audio/x-wav':
      return 'take.wav';
    default:
      return 'take.bin';
  }
}
