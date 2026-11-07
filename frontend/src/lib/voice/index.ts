export * from './types';
export { toVoiceError, describeVoiceError, describeCaptureError, captureErrorFromException } from './errors';
export type { ErrorDescription } from './errors';
export { DOUBLE_TAP_WINDOW_MS, IDLE_GESTURE, reduceGesture } from './gesture';
export type { GestureState, GestureEvent, GestureEffect, GestureStep } from './gesture';
export { IDLE_RECORDER, reduceRecorder, lineNumber, canCancel } from './recorder';
export type { RecorderState, RecorderKind, RecorderEvent } from './recorder';
export { INITIAL_VAD, MIN_SPEECH_RMS, MIN_SPEECH_MS, SPEECH_RATIO, speechThreshold, stepVad } from './vad';
export type { VadState, VadSample, VadStep } from './vad';
export { rmsOf, meterLevel, METER_FLOOR_DB } from './level';
export {
  SILENCE_CHOICES,
  DEFAULT_SILENCE_SECONDS,
  SILENCE_STORAGE_KEY,
  parseSilenceSeconds,
  silenceLabel,
  loadSilenceSeconds,
  saveSilenceSeconds,
} from './silenceSetting';
export type { SilenceSeconds, SilenceStorageError, SaveSilenceResult } from './silenceSetting';
export {
  HTTPS_REQUIRED_MESSAGE,
  UNSUPPORTED_MESSAGE,
  MIME_PREFERENCE,
  recordingSupport,
  browserRecordingEnvironment,
  pickMimeType,
  uploadFileName,
} from './support';
export type { RecordingEnvironment, RecordingSupport } from './support';
export { ORIGIN, resolveCaptureTarget, toWireTarget } from './target';
export type { CaptureTarget, TargetInput } from './target';
export { formatDuration, transcriptPreview, formatRecordedAt } from './format';
export { IDLE_PLAYBACK, reducePlayback } from './playback';
export type { PlaybackState, PlaybackEvent, PlaybackEffect, PlaybackStep } from './playback';
export { RECORD_SHORTCUT_LABEL, isRecordShortcut, modalKeyAction, trapFocusIndex } from './keys';
export type { KeyLike, ModalKeyAction } from './keys';
export { VoiceSession } from './session';
export type { CaptureHandle, RecordedAudio, SessionDeps, SessionSnapshot } from './session';
export { startMediaCapture, browserCaptureEnvironment } from './mediaCapture';
export type { CaptureEnvironment, RecorderLike, RecorderCtor, AudioContextLike, AnalyserLike } from './mediaCapture';
export { VOCAB_HINTS_STORAGE_KEY, DEFAULT_VOCAB_HINTS, parseVocabHints, loadVocabHints, saveVocabHints } from './vocabHintsSetting';
export type { VocabHintsStorageError, SaveVocabHintsResult } from './vocabHintsSetting';
export { verificationOf, whisperLines, correctionPrefill, referenceOf, withVerification } from './recordingView';
export type { Verification, TimedLine, TranscriptReference } from './recordingView';
export { SEEK_STEP_MS, wordIndexAt, clampSeekMs } from './timing';
export { MAX_VERIFICATION_CODE_POINTS, normalizeVerificationText, checkVerificationText } from './verification';
export type { VerificationCheck } from './verification';
export { fallbackExportFilename, filenameFromContentDisposition, formatAudioTotal, formatDatasetStats } from './dataset';
export { PLAYBACK_TOGGLE_LABEL, checkModalKeyAction } from './checkKeys';
export type { CheckKeyAction } from './checkKeys';
export { CHECK_HINT, EDITING, reduceCheck, checkErrorMessage } from './checkTranscript';
export type { CheckError, CheckState, CheckEvent, CheckEffect, CheckStep } from './checkTranscript';
export { isRecordingGone } from './errors';
export type { StatusKinds } from './errors';
