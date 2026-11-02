/**
 * Voice-take and recording endpoints (contract §2). Built over an injected axios instance so tests can
 * drive it with a mock adapter; `client.ts` binds it to the authenticated app instance. Every function
 * rejects with a `VoiceError` only.
 */
import type { AxiosInstance } from 'axios';
import { toVoiceError } from '@/lib/voice/errors';
import { uploadFileName } from '@/lib/voice/support';
import type { RecordingResponse, Take, VoiceTakeMeta, VoiceTakeResponse, VoiceTakeTarget } from '@/lib/voice/types';

export function buildTakeMeta(take: Take, target: VoiceTakeTarget): VoiceTakeMeta {
  return { breaks_ms: [...take.breaksMs], duration_ms: take.durationMs, target };
}

export function buildTakeForm(take: Take, target: VoiceTakeTarget): FormData {
  const form = new FormData();
  // The part's type is the recorder's mime (parameters included) so the server can validate it.
  const audio = take.blob.type === take.mimeType ? take.blob : new Blob([take.blob], { type: take.mimeType });
  form.append('audio', audio, uploadFileName(take.mimeType));
  form.append('meta', JSON.stringify(buildTakeMeta(take, target)));
  return form;
}

async function wrap<T>(call: () => Promise<{ data: T }>): Promise<T> {
  try {
    return (await call()).data;
  } catch (err) {
    throw toVoiceError(err);
  }
}

export interface VoiceApi {
  createTake(canvasId: string, take: Take, target: VoiceTakeTarget, signal?: AbortSignal): Promise<VoiceTakeResponse>;
  listRecordings(snippetId: string, signal?: AbortSignal): Promise<RecordingResponse[]>;
  fetchAudio(recordingId: string, signal?: AbortSignal): Promise<Blob>;
  deleteRecording(recordingId: string): Promise<void>;
}

export function createVoiceApi(http: AxiosInstance): VoiceApi {
  return {
    createTake: (canvasId, take, target, signal) =>
      wrap(() =>
        http.post<VoiceTakeResponse>(`/api/canvases/${encodeURIComponent(canvasId)}/voice-takes`, buildTakeForm(take, target), {
          // The instance defaults to JSON, which would make axios serialise the FormData to JSON. In the
          // browser axios drops this header for FormData so the boundary is set by the browser.
          headers: { 'Content-Type': 'multipart/form-data' },
          signal,
        }),
      ),
    listRecordings: (snippetId, signal) =>
      wrap(() => http.get<RecordingResponse[]>(`/api/snippets/${encodeURIComponent(snippetId)}/recordings`, { signal })),
    fetchAudio: (recordingId, signal) =>
      wrap(() =>
        http.get<Blob>(`/api/recordings/${encodeURIComponent(recordingId)}/audio`, { responseType: 'blob', signal }),
      ),
    deleteRecording: async (recordingId) => {
      await wrap(() => http.delete(`/api/recordings/${encodeURIComponent(recordingId)}`));
    },
  };
}
