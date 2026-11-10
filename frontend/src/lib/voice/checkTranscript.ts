/**
 * The check transcript view's request state (dataset contract §4), pure. The component performs the
 * returned effect (PUT / DELETE / close) and feeds the outcome back. At most one request is in flight;
 * a dirty Cancel asks before discarding.
 */
import { describeVoiceError } from './errors';
import type { VoiceError } from './types';
import { MAX_VERIFICATION_CODE_POINTS, checkVerificationText } from './verification';

export const CHECK_HINT =
  'Write exactly what was said: this trains transcription, not your lyrics. Editing here does not change the snippet.';

export type CheckError = { kind: 'local'; reason: 'empty' | 'too_long' } | { kind: 'server'; error: VoiceError };

export type CheckState =
  | { kind: 'editing' }
  | { kind: 'saving' }
  | { kind: 'unchecking' }
  | { kind: 'confirm_discard' }
  | { kind: 'failed'; action: 'save' | 'uncheck'; error: CheckError };

export const EDITING: CheckState = { kind: 'editing' };

export type CheckEvent =
  | { type: 'save'; text: string }
  | { type: 'uncheck'; checked: boolean }
  | { type: 'cancel'; dirty: boolean }
  | { type: 'discard' }
  | { type: 'keep_editing' }
  | { type: 'saved' }
  | { type: 'unchecked' }
  | { type: 'request_failed'; error: VoiceError };

export type CheckEffect = { kind: 'none' } | { kind: 'put'; text: string } | { kind: 'delete' } | { kind: 'close' };

export interface CheckStep {
  state: CheckState;
  effect: CheckEffect;
}

const MAX_CHARS_LABEL = String(MAX_VERIFICATION_CODE_POINTS).replace(/\B(?=(\d{3})+$)/g, ' ');

const NONE: CheckEffect = { kind: 'none' };

function idle(state: CheckState): boolean {
  return state.kind === 'editing' || state.kind === 'failed';
}

export function reduceCheck(state: CheckState, event: CheckEvent): CheckStep {
  const stay: CheckStep = { state, effect: NONE };
  switch (event.type) {
    case 'save': {
      if (!idle(state)) return stay;
      const check = checkVerificationText(event.text);
      if (!check.ok) return { state: { kind: 'failed', action: 'save', error: { kind: 'local', reason: check.reason } }, effect: NONE };
      return { state: { kind: 'saving' }, effect: { kind: 'put', text: check.text } };
    }
    case 'uncheck':
      if (!idle(state) || !event.checked) return stay;
      return { state: { kind: 'unchecking' }, effect: { kind: 'delete' } };
    case 'cancel':
      if (state.kind === 'confirm_discard') return { state: EDITING, effect: NONE };
      if (!idle(state)) return stay;
      return event.dirty ? { state: { kind: 'confirm_discard' }, effect: NONE } : { state: EDITING, effect: { kind: 'close' } };
    case 'discard':
      return state.kind === 'confirm_discard' ? { state: EDITING, effect: { kind: 'close' } } : stay;
    case 'keep_editing':
      return state.kind === 'confirm_discard' ? { state: EDITING, effect: NONE } : stay;
    case 'saved':
      return state.kind === 'saving' ? { state: EDITING, effect: { kind: 'close' } } : stay;
    case 'unchecked':
      return state.kind === 'unchecking' ? { state: EDITING, effect: NONE } : stay;
    case 'request_failed':
      if (state.kind !== 'saving' && state.kind !== 'unchecking') return stay;
      return {
        state: { kind: 'failed', action: state.kind === 'saving' ? 'save' : 'uncheck', error: { kind: 'server', error: event.error } },
        effect: NONE,
      };
  }
}

export function checkErrorMessage(error: CheckError): string {
  if (error.kind === 'server') return describeVoiceError(error.error).title;
  return error.reason === 'empty'
    ? 'The transcript is empty: write what was said.'
    : `The transcript is too long (at most ${MAX_VERIFICATION_CODE_POINTS.toLocaleString('fr-FR').replace(/ | /g, ' ')} characters).`;
}
