/**
 * Typed failures of the score algorithms (spec/score_format.md section 4). Every class carries a `kind`
 * discriminant so callers can switch on it without instanceof chains.
 */
import type { ScoreInvalidBody, ScoreInvalidReason } from '@/types/score';

export abstract class ScoreError extends Error {
  abstract readonly kind: 'invalid' | 'not_rest_only' | 'text_mismatch' | 'argument';
}

/** A score that breaks format v1 or its grid; the API answers 422 `invalid_score` with the same body. */
export class ScoreInvalid extends ScoreError {
  readonly kind = 'invalid' as const;
  constructor(
    readonly reason: ScoreInvalidReason,
    readonly detail: string,
    readonly line?: number,
    readonly note?: number,
  ) {
    const where = line === undefined ? '' : ` (line ${line}${note === undefined ? '' : `, note ${note}`})`;
    super(`${reason}${where}: ${detail}`);
    this.name = 'ScoreInvalid';
  }

  /** The wire shape `{error: "invalid_score", reason, line?, note?}`. */
  asBody(): ScoreInvalidBody {
    const body: ScoreInvalidBody = { error: 'invalid_score', reason: this.reason };
    if (this.line !== undefined) body.line = this.line;
    if (this.note !== undefined) body.note = this.note;
    return body;
  }
}

/** applyGridRestEdit was given a grid edit that changed syllables, not only rests. */
export class NotRestOnlyEdit extends ScoreError {
  readonly kind = 'not_rest_only' as const;
  constructor(detail: string) {
    super(detail);
    this.name = 'NotRestOnlyEdit';
  }
}

/** spliceLineTexts was given a grid line index the snippet text does not have. */
export class ScoreTextMismatch extends ScoreError {
  readonly kind = 'text_mismatch' as const;
  constructor(
    readonly index: number,
    readonly available: number,
  ) {
    super(`grid line ${index} has no text line (text has ${available} non-blank lines)`);
    this.name = 'ScoreTextMismatch';
  }
}

/** A timing query outside its domain (negative tick, bar below -1). */
export class ScoreArgumentError extends ScoreError {
  readonly kind = 'argument' as const;
  constructor(detail: string) {
    super(detail);
    this.name = 'ScoreArgumentError';
  }
}
