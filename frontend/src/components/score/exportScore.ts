import { scoreApi } from '@/api/client';
import { saveBlobAs } from '@/components/voice/dataset/saveBlob';
import { exportNotice } from '@/lib/scoreTools/exportFile';
import type { ExportOutcome } from '@/lib/scoreTools/exportFile';
import { toScoreApiError } from '@/lib/scoreTools/errors';
import { usePlacementNoticeStore } from '@/stores/placementNoticeStore';
import type { ScoreApi } from '@/api/score';
import type { ScoreExportFormat } from '@/types/scoreTools';

export interface ExportDeps {
  exportScore: ScoreApi['exportScore'];
  save: (blob: Blob, filename: string) => void;
}

const DEFAULT_DEPS: ExportDeps = { exportScore: (id, format) => scoreApi.exportScore(id, format), save: saveBlobAs };

/** Download the snippet's saved score in `format` and save it under the server's file name. Never rejects. */
export async function exportScoreFile(
  snippetId: string,
  format: ScoreExportFormat,
  deps: ExportDeps = DEFAULT_DEPS,
): Promise<ExportOutcome> {
  let download: Awaited<ReturnType<ExportDeps['exportScore']>>;
  try {
    download = await deps.exportScore(snippetId, format);
  } catch (err) {
    const error = toScoreApiError(err, 'export');
    console.warn('score export: failed', { snippetId, format, kind: error.kind, status: error.status });
    return { ok: false, error };
  }
  deps.save(download.blob, download.filename);
  console.info('score export: saved', { snippetId, format, filename: download.filename, bytes: download.blob.size });
  return { ok: true, filename: download.filename };
}

/** Export and report the outcome in the bottom notice (the UI entry points call this). */
export async function exportScoreWithNotice(snippetId: string, format: ScoreExportFormat): Promise<void> {
  const notice = exportNotice(await exportScoreFile(snippetId, format));
  usePlacementNoticeStore.getState().show(notice.text, notice.tone);
}
