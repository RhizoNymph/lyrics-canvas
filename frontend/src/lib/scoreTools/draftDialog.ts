/**
 * State machine of the draft dialog (pure): the form, the snippet's takes, and the draft phase. The
 * component runs the effects (load takes, call the drafter) and feeds their results back as events.
 */
import { DEFAULT_DRAFT_FORM, buildDraftRequest, recordingChoices } from './draftForm';
import type { DraftForm, DraftFormIssue, DraftSource, RecordingChoice } from './draftForm';
import type { RecordingResponse } from '@/lib/voice/types';
import type { ScoreApiError, ScoreDraftRequest, ScoreDraftResponse } from '@/types/scoreTools';

export type RecordingsState =
  | { kind: 'loading' }
  | { kind: 'loaded'; choices: RecordingChoice[] }
  | { kind: 'failed'; message: string };

export type DraftPhase =
  | { kind: 'editing'; issues: DraftFormIssue[] }
  | { kind: 'drafting'; request: ScoreDraftRequest }
  | { kind: 'drafted'; draft: ScoreDraftResponse }
  | { kind: 'failed'; error: ScoreApiError };

export interface DraftDialogState {
  form: DraftForm;
  recordings: RecordingsState;
  phase: DraftPhase;
}

export type DraftDialogEvent =
  | { type: 'edit'; patch: Partial<DraftForm> }
  | { type: 'recordings_loaded'; recordings: readonly RecordingResponse[] }
  | { type: 'recordings_failed'; message: string }
  | { type: 'submit' }
  | { type: 'drafted'; draft: ScoreDraftResponse }
  | { type: 'failed'; error: ScoreApiError };

const EDITING: DraftPhase = { kind: 'editing', issues: [] };

export function initialDraftDialog(source: DraftSource = 'grid'): DraftDialogState {
  return { form: { ...DEFAULT_DRAFT_FORM, source }, recordings: { kind: 'loading' }, phase: EDITING };
}

export function reduceDraftDialog(state: DraftDialogState, event: DraftDialogEvent): DraftDialogState {
  switch (event.type) {
    case 'edit':
      // Any edit makes a shown draft (or a pending answer) stale.
      return { ...state, form: { ...state.form, ...event.patch }, phase: EDITING };
    case 'recordings_loaded': {
      const choices = recordingChoices(event.recordings);
      const pick = state.form.recordingId ?? choices.find((c) => c.usable)?.id ?? null;
      return { ...state, recordings: { kind: 'loaded', choices }, form: { ...state.form, recordingId: pick } };
    }
    case 'recordings_failed':
      return { ...state, recordings: { kind: 'failed', message: event.message } };
    case 'submit': {
      if (state.phase.kind === 'drafting') return state;
      const built = buildDraftRequest(state.form);
      if (!built.ok) return { ...state, phase: { kind: 'editing', issues: built.issues } };
      return { ...state, phase: { kind: 'drafting', request: built.request } };
    }
    case 'drafted':
      if (state.phase.kind !== 'drafting') return state;
      return { ...state, phase: { kind: 'drafted', draft: event.draft } };
    case 'failed':
      if (state.phase.kind !== 'drafting') return state;
      return { ...state, phase: { kind: 'failed', error: event.error } };
  }
}
