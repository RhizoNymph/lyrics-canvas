import { describe, it, expect } from 'vitest';
import { CHECK_HINT, EDITING, checkErrorMessage, reduceCheck } from './checkTranscript';
import type { CheckState } from './checkTranscript';
import { VoiceError } from './types';

const err = new VoiceError('invalid_verification', 'bad', 422);

describe('reduceCheck', () => {
  it('save with valid text sends the normalized text', () => {
    expect(reduceCheck(EDITING, { type: 'save', text: ' hi \r\nthere ' })).toEqual({
      state: { kind: 'saving' },
      effect: { kind: 'put', text: ' hi\nthere' },
    });
  });

  it('save with empty or too long text fails locally without a request', () => {
    expect(reduceCheck(EDITING, { type: 'save', text: ' \n ' })).toEqual({
      state: { kind: 'failed', action: 'save', error: { kind: 'local', reason: 'empty' } },
      effect: { kind: 'none' },
    });
    expect(reduceCheck(EDITING, { type: 'save', text: 'x'.repeat(10_001) }).state).toMatchObject({
      error: { kind: 'local', reason: 'too_long' },
    });
  });

  it('save is allowed unchanged (confirming Whisper was right)', () => {
    expect(reduceCheck(EDITING, { type: 'save', text: 'ok' }).effect).toEqual({ kind: 'put', text: 'ok' });
  });

  it('ignores actions while a request is in flight', () => {
    const saving: CheckState = { kind: 'saving' };
    expect(reduceCheck(saving, { type: 'save', text: 'x' })).toEqual({ state: saving, effect: { kind: 'none' } });
    expect(reduceCheck(saving, { type: 'uncheck', checked: true })).toEqual({ state: saving, effect: { kind: 'none' } });
    expect(reduceCheck(saving, { type: 'cancel', dirty: false })).toEqual({ state: saving, effect: { kind: 'none' } });
  });

  it('a saved correction closes the view', () => {
    expect(reduceCheck({ kind: 'saving' }, { type: 'saved' })).toEqual({ state: EDITING, effect: { kind: 'close' } });
  });

  it('a failed request shows its typed error and allows another try', () => {
    const failed = reduceCheck({ kind: 'saving' }, { type: 'request_failed', error: err });
    expect(failed.state).toEqual({ kind: 'failed', action: 'save', error: { kind: 'server', error: err } });
    expect(reduceCheck(failed.state, { type: 'save', text: 'x' }).effect.kind).toBe('put');
    expect(reduceCheck({ kind: 'unchecking' }, { type: 'request_failed', error: err }).state).toMatchObject({ action: 'uncheck' });
  });

  it('uncheck sends DELETE only for a checked recording and stays open', () => {
    expect(reduceCheck(EDITING, { type: 'uncheck', checked: true })).toEqual({ state: { kind: 'unchecking' }, effect: { kind: 'delete' } });
    expect(reduceCheck(EDITING, { type: 'uncheck', checked: false })).toEqual({ state: EDITING, effect: { kind: 'none' } });
    expect(reduceCheck({ kind: 'unchecking' }, { type: 'unchecked' })).toEqual({ state: EDITING, effect: { kind: 'none' } });
  });

  it('cancel closes at once when clean and asks first when dirty', () => {
    expect(reduceCheck(EDITING, { type: 'cancel', dirty: false })).toEqual({ state: EDITING, effect: { kind: 'close' } });
    const asking = reduceCheck(EDITING, { type: 'cancel', dirty: true });
    expect(asking).toEqual({ state: { kind: 'confirm_discard' }, effect: { kind: 'none' } });
    expect(reduceCheck(asking.state, { type: 'discard' })).toEqual({ state: EDITING, effect: { kind: 'close' } });
    expect(reduceCheck(asking.state, { type: 'keep_editing' })).toEqual({ state: EDITING, effect: { kind: 'none' } });
    // A second cancel (Escape again) while asking keeps editing.
    expect(reduceCheck(asking.state, { type: 'cancel', dirty: true })).toEqual({ state: EDITING, effect: { kind: 'none' } });
  });

  it('cancel from a failed state also honours dirtiness', () => {
    const failed: CheckState = { kind: 'failed', action: 'save', error: { kind: 'server', error: err } };
    expect(reduceCheck(failed, { type: 'cancel', dirty: false }).effect).toEqual({ kind: 'close' });
    expect(reduceCheck(failed, { type: 'cancel', dirty: true }).state).toEqual({ kind: 'confirm_discard' });
  });

  it('ignores completions that do not match the state', () => {
    expect(reduceCheck(EDITING, { type: 'saved' })).toEqual({ state: EDITING, effect: { kind: 'none' } });
    expect(reduceCheck(EDITING, { type: 'request_failed', error: err })).toEqual({ state: EDITING, effect: { kind: 'none' } });
  });
});

describe('checkErrorMessage', () => {
  it('describes local and server failures', () => {
    expect(checkErrorMessage({ kind: 'local', reason: 'empty' })).toMatch(/empty/i);
    expect(checkErrorMessage({ kind: 'local', reason: 'too_long' })).toMatch(/10 000/);
    expect(checkErrorMessage({ kind: 'server', error: err })).toBe('The transcript cannot be saved');
    expect(checkErrorMessage({ kind: 'server', error: new VoiceError('recording_not_found', 'x', 404) })).toBe(
      'This recording no longer exists',
    );
    expect(checkErrorMessage({ kind: 'server', error: new VoiceError('network', 'x') })).toBe('Could not reach the server');
  });

  it('carries the contract hint text', () => {
    expect(CHECK_HINT).toBe(
      'Write exactly what was said: this trains transcription, not your lyrics. Editing here does not change the snippet.',
    );
  });
});
