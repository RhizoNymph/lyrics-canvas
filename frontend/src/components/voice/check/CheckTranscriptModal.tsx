import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { KeyboardEvent as ReactKeyboardEvent } from 'react';
import { voiceApi } from '@/api/client';
import { checkModalKeyAction } from '@/lib/voice/checkKeys';
import { EDITING, reduceCheck } from '@/lib/voice/checkTranscript';
import type { CheckEvent, CheckState } from '@/lib/voice/checkTranscript';
import { toVoiceError } from '@/lib/voice/errors';
import { correctionPrefill, referenceOf, verificationOf } from '@/lib/voice/recordingView';
import { SEEK_STEP_MS, wordIndexAt } from '@/lib/voice/timing';
import type { RecordingResponse } from '@/lib/voice/types';
import { useFocusTrap } from '../useFocusTrap';
import CheckTranscriptView from './CheckTranscriptView';
import { useRecordingPlayer } from './useRecordingPlayer';

interface Props {
  recording: RecordingResponse;
  /** The server's updated recording after a save or uncheck. */
  onUpdated: (recording: RecordingResponse) => void;
  onClose: () => void;
}

/**
 * Check transcript (dataset contract §4): listen to a take and save what was actually said as the
 * recording's `verified_text`. The snippet is never touched. Requests go through `reduceCheck`; the
 * player through `useRecordingPlayer`. Mounted by RecordingsSection in a portal on document.body.
 */
export default function CheckTranscriptModal({ recording, onUpdated, onClose }: Props) {
  const reference = useMemo(() => referenceOf(recording), [recording]);
  // The prefill is taken once per opened recording; a save/uncheck response must not reset the field.
  const [initial] = useState(() => correctionPrefill(recording));
  const [text, setText] = useState(initial);
  const [check, setCheck] = useState<CheckState>(EDITING);
  const checkRef = useRef<CheckState>(EDITING);
  const textRef = useRef(text);
  textRef.current = text;
  const mounted = useRef(true);
  const surfaceRef = useRef<HTMLDivElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const onTrapKeyDown = useFocusTrap(surfaceRef);
  const player = useRecordingPlayer(recording.id, recording.duration_ms);
  const checked = verificationOf(recording).kind === 'checked';
  const dirty = text !== initial;

  useEffect(() => {
    mounted.current = true;
    textareaRef.current?.focus();
    return () => {
      mounted.current = false;
    };
  }, []);

  const dispatchRef = useRef<(event: CheckEvent) => void>(() => undefined);
  const dispatch = useCallback(
    (event: CheckEvent) => {
      if (!mounted.current) return;
      const step = reduceCheck(checkRef.current, event);
      checkRef.current = step.state;
      setCheck(step.state);
      const fail = (err: unknown) => {
        const error = toVoiceError(err);
        console.warn('recordings: verification request failed', { recordingId: recording.id, kind: error.kind, status: error.status });
        dispatchRef.current({ type: 'request_failed', error });
      };
      switch (step.effect.kind) {
        case 'none':
          return;
        case 'close':
          onClose();
          return;
        case 'put':
          voiceApi.saveVerification(recording.id, step.effect.text).then((updated) => {
            if (!mounted.current) return;
            onUpdated(updated);
            dispatchRef.current({ type: 'saved' });
          }, fail);
          return;
        case 'delete':
          voiceApi.clearVerification(recording.id).then((updated) => {
            if (!mounted.current) return;
            onUpdated(updated);
            dispatchRef.current({ type: 'unchecked' });
          }, fail);
          return;
      }
    },
    [recording.id, onClose, onUpdated],
  );
  dispatchRef.current = dispatch;

  const onKeyDown = (e: ReactKeyboardEvent<HTMLDivElement>) => {
    // Keys never reach the editor or the canvas page behind the modal.
    e.stopPropagation();
    if (e.key === 'Tab') {
      onTrapKeyDown(e);
      return;
    }
    const action = checkModalKeyAction(e);
    switch (action) {
      case 'native':
        return;
      case 'none':
        e.preventDefault();
        return;
      case 'save':
        e.preventDefault();
        dispatch({ type: 'save', text: textRef.current });
        return;
      case 'toggle_play':
        e.preventDefault();
        player.toggle();
        return;
      case 'seek_back':
        e.preventDefault();
        player.seekBy(-SEEK_STEP_MS);
        return;
      case 'seek_forward':
        e.preventDefault();
        player.seekBy(SEEK_STEP_MS);
        return;
      case 'cancel':
        e.preventDefault();
        dispatch({ type: 'cancel', dirty: textRef.current !== initial });
        return;
    }
  };

  const activeWord =
    reference.kind === 'timed' && player.state.kind === 'ready' ? wordIndexAt(reference.words, player.state.positionMs) : null;

  return (
    <CheckTranscriptView
      recording={recording}
      checked={checked}
      reference={reference}
      activeWord={activeWord}
      player={player.state}
      text={text}
      check={check}
      surfaceRef={surfaceRef}
      textareaRef={textareaRef}
      onTextChange={setText}
      onWord={(index) => {
        if (reference.kind === 'timed') player.playFrom(reference.words[index].start_ms);
      }}
      onTogglePlay={player.toggle}
      onSeek={player.seek}
      onSave={() => dispatch({ type: 'save', text })}
      onUncheck={() => dispatch({ type: 'uncheck', checked })}
      onCancel={() => dispatch({ type: 'cancel', dirty })}
      onDiscard={() => dispatch({ type: 'discard' })}
      onKeepEditing={() => {
        dispatch({ type: 'keep_editing' });
        textareaRef.current?.focus();
      }}
      onKeyDown={onKeyDown}
    />
  );
}
