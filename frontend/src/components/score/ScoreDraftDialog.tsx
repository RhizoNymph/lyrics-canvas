import { useCallback, useEffect, useReducer, useRef } from 'react';
import { createPortal } from 'react-dom';
import type { KeyboardEvent as ReactKeyboardEvent } from 'react';
import { scoreApi, voiceApi } from '@/api/client';
import { initialDraftDialog, reduceDraftDialog } from '@/lib/scoreTools/draftDialog';
import type { DraftSource } from '@/lib/scoreTools/draftForm';
import { runDraft } from '@/lib/scoreTools/drafter';
import type { LocalGridDrafter } from '@/lib/scoreTools/drafter';
import { toScoreApiError } from '@/lib/scoreTools/errors';
import { buildDraftRequest } from '@/lib/scoreTools/draftForm';
import { describeVoiceError, toVoiceError } from '@/lib/voice/errors';
import { useFocusTrap } from '@/components/voice/useFocusTrap';
import type { Snippet } from '@/types';
import type { Score } from '@/types/score';
import ScoreDraftView from './ScoreDraftView';

export interface ScoreDraftDialogProps {
  /** The snippet to draft a score for (its id addresses the draft endpoint and its takes). */
  snippet: Pick<Snippet, 'id'>;
  /** The chosen draft; the caller puts it in the editor and saves it with the snippet. Closing follows. */
  onDraft: (score: Score) => void;
  onClose: () => void;
  initialSource?: DraftSource;
  /**
   * Grid drafts over the caller's live grid (the editor passes the `draftFromGrid` port). Omitted, the
   * server drafts from the snippet's stored grid.
   */
  draftGrid?: LocalGridDrafter;
}

/**
 * "Make score": draft a score from the grid (note length, meter, tempo) or from one of the snippet's
 * voice takes (meter, optional tempo; shows the estimated tempo and the unplaced / unmatched counts).
 * Nothing is saved here: "Use score" hands the draft to `onDraft`. Closing aborts every request. The
 * overlay carries `data-keep-editor-open`, so the inline editor stays open behind it.
 */
export default function ScoreDraftDialog({ snippet, onDraft, onClose, initialSource = 'grid', draftGrid }: ScoreDraftDialogProps) {
  const [state, dispatch] = useReducer(reduceDraftDialog, initialSource, initialDraftDialog);
  const surfaceRef = useRef<HTMLDivElement>(null);
  const onTrapKeyDown = useFocusTrap(surfaceRef);
  const draftAbort = useRef<AbortController | null>(null);

  // The snippet's takes, for the recording source (loaded once on open).
  useEffect(() => {
    const abort = new AbortController();
    voiceApi.listRecordings(snippet.id, abort.signal).then(
      (recordings) => dispatch({ type: 'recordings_loaded', recordings }),
      (err: unknown) => {
        const error = toVoiceError(err);
        if (error.kind === 'aborted') return;
        console.warn('score draft: takes failed', { snippetId: snippet.id, kind: error.kind, status: error.status });
        dispatch({ type: 'recordings_failed', message: describeVoiceError(error).title });
      },
    );
    return () => {
      abort.abort();
      draftAbort.current?.abort();
    };
  }, [snippet.id]);

  const onSubmit = useCallback(() => {
    const built = buildDraftRequest(state.form);
    dispatch({ type: 'submit' });
    if (!built.ok || state.phase.kind === 'drafting') return;
    draftAbort.current?.abort();
    const abort = new AbortController();
    draftAbort.current = abort;
    const request = built.request;
    runDraft(snippet.id, request, { draft: scoreApi.draft, local: draftGrid }, abort.signal).then(
      (draft) => {
        if (abort.signal.aborted) return;
        console.info('score draft: drafted', {
          snippetId: snippet.id,
          source: request.source,
          unplaced: draft.unplaced,
          unmatchedWords: draft.unmatched_words,
          bpmEstimated: draft.bpm_estimated,
        });
        dispatch({ type: 'drafted', draft });
      },
      (err: unknown) => {
        if (abort.signal.aborted) return;
        const error = toScoreApiError(err, 'draft');
        if (error.kind === 'aborted') return;
        console.warn('score draft: failed', { snippetId: snippet.id, source: request.source, kind: error.kind, status: error.status });
        dispatch({ type: 'failed', error });
      },
    );
  }, [state.form, state.phase.kind, snippet.id, draftGrid]);

  const onUse = useCallback(() => {
    if (state.phase.kind !== 'drafted') return;
    onDraft(state.phase.draft.score);
    onClose();
  }, [state.phase, onDraft, onClose]);

  const onKeyDown = (e: ReactKeyboardEvent<HTMLDivElement>) => {
    if (e.key === 'Tab') onTrapKeyDown(e);
    else if (e.key === 'Escape') {
      e.preventDefault();
      e.stopPropagation();
      onClose();
    }
  };

  // A portal on document.body: the editor's own overflow and click handling do not reach it.
  return createPortal(
    <ScoreDraftView
      state={state}
      onEvent={(event) => {
        // An edit makes a pending draft stale: drop its answer.
        if (event.type === 'edit') draftAbort.current?.abort();
        dispatch(event);
      }}
      onSubmit={onSubmit}
      onUse={onUse}
      onClose={onClose}
      surfaceRef={surfaceRef}
      onKeyDown={onKeyDown}
    />,
    document.body,
  );
}
