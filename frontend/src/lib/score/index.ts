/** TS port of the score format v1 algorithms (spec/score_format.md), checked by spec/score_vectors.json. */
export { ScoreError, ScoreInvalid, NotRestOnlyEdit, ScoreTextMismatch, ScoreArgumentError } from './errors';
export { asInteger, asNumber, isJsonObject, jsonEqual } from './json';
export type { JsonObject } from './json';
export {
  isRest,
  gridLines,
  lineCells,
  syllablePositions,
  syllableCount,
  syllableCounts,
  restsBetween,
  wordGroups,
  rebuildLineText,
} from './gridView';
export type { CellJson, LineJson, GridJson, GridObjectJson, WordGroup } from './gridView';
export {
  checkScore,
  validateScore,
  isValidBpm,
  isValidMeterValue,
  meterBarLength,
  parseSnap,
  DENS,
  BPM_MIN,
  BPM_MAX,
  NUM_MIN,
  NUM_MAX,
  MAX_METERS,
  MAX_TEMPOS,
} from './validate';
export type { ValidateResult } from './validate';
export {
  PICKUP_BAR,
  meterAtBar,
  tempoAtBar,
  meterUnit,
  barLength,
  barStartTick,
  barAtTick,
  meterAtTick,
  beatUnit,
  isCompound,
  metricWeight,
  tickToMs,
  noteEnd,
  endTick,
  barCount,
} from './timing';
export type { BarPosition } from './timing';
export { normalizeWord, wordKey, wordKeys, lineKey, lcsPairs } from './normalize';
export { applyRestLink, isRestOnlyEdit, applyGridRestEdit, notesBySyllable } from './restLink';
export type { GridEditResult } from './restLink';
export { reconcile } from './reconcile';
export { draftFromGrid, defaultAccent } from './draft';
export { changedLineTexts, spliceLineTexts } from './text';
export { planContentSave, scoreContentOf, ScoreSaveError } from './saveFlow';
export type { ScoreContent, ContentSavePlan } from './saveFlow';
export { syllableTexts } from './gridView';
export {
  noteWindow,
  findNote,
  placeNote,
  moveNote,
  resizeNote,
  cycleAccent,
  unplaceNote,
  setMeterAt,
  setTempoAt,
  setPickup,
  setSnap,
  snapTick,
  unplacedSyllables,
} from './edit';
export type { EditRefusal, EditResult } from './edit';
export { staffBars, layoutStaff, rowAtTick, xOfTick, tickAtX, beatTicks, noteSegments, lineStarts } from './layout';
export type { StaffBar, StaffRow, StaffLayout, LayoutOptions, BeatTick, NoteSegment } from './layout';
export { ACCENT_GAIN, playbackPlan, msToTick } from './playback';
export type { PlannedClick, PlannedNote, PlaybackPlan } from './playback';
export { NO_SCORE, commitTrack, followGrid } from './track';
export type { ScoreTrack, FollowResult } from './track';
export { emptyHistory, record, undo, redo, HISTORY_LIMIT } from './history';
export type { History } from './history';
