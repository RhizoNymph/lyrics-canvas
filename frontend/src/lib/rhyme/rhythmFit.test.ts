import { describe, it, expect, vi } from 'vitest';
import {
  RHYTHM_FIT_STORAGE_KEY,
  RHYTHM_OFF,
  listPresentation,
  loadRhythmFit,
  parseRhythmFit,
  rhythmEligible,
  rhythmFitField,
  rhythmFitting,
  rhythmNote,
  rhythmPercent,
  saveRhythmFit,
} from './rhythmFit';
import { RHYME_ORDERING } from './order';
import type { KeyValueStorage } from '@/lib/listMode/viewMode';
import type { AnchorRef } from '@/types/rhyme';

const snippetAnchor: AnchorRef = { kind: 'snippet', snippet_id: 's1', line_index: null };
const textAnchor: AnchorRef = { kind: 'text', text: 'door hinge' };

function memory(initial: Record<string, string> = {}): KeyValueStorage & { data: Record<string, string> } {
  const data = { ...initial };
  return {
    data,
    getItem: (k) => (k in data ? data[k] : null),
    setItem: (k, v) => {
      data[k] = v;
    },
  };
}

describe('rhythmFitField', () => {
  it('sends rhythm_fit only when on and a snippet anchor is queried', () => {
    expect(rhythmFitField(true, [snippetAnchor])).toEqual({ rhythm_fit: true });
    expect(rhythmFitField(true, [textAnchor, snippetAnchor])).toEqual({ rhythm_fit: true });
  });

  it('leaves the field out otherwise, so old request bodies are unchanged', () => {
    expect(rhythmFitField(false, [snippetAnchor])).toEqual({});
    expect(rhythmFitField(true, [textAnchor])).toEqual({});
    expect(rhythmFitField(true, [])).toEqual({});
  });
});

describe('rhythmEligible', () => {
  it('needs at least one snippet anchor', () => {
    expect(rhythmEligible([snippetAnchor])).toBe(true);
    expect(rhythmEligible([textAnchor])).toBe(false);
    expect(rhythmEligible([])).toBe(false);
  });
});

describe('rhythmFitting', () => {
  it('is off when not requested, whatever the server says', () => {
    expect(rhythmFitting(false, { rhythm_applied: true, rhythm_unavailable_reason: null })).toEqual(RHYTHM_OFF);
    expect(rhythmFitting(false, {})).toEqual(RHYTHM_OFF);
  });

  it('is applied when requested and the server applied it', () => {
    expect(rhythmFitting(true, { rhythm_applied: true, rhythm_unavailable_reason: null })).toEqual({
      requested: true,
      applied: true,
      unavailable: null,
    });
  });

  it('carries the server reason when requested but not applied', () => {
    expect(rhythmFitting(true, { rhythm_applied: false, rhythm_unavailable_reason: 'no_score' }).unavailable).toBe('no_score');
    expect(rhythmFitting(true, { rhythm_applied: false, rhythm_unavailable_reason: 'slot_unplaced' }).unavailable).toBe(
      'slot_unplaced',
    );
  });

  it('reads a missing or unknown answer (older server) as unavailable for an unknown reason', () => {
    expect(rhythmFitting(true, {})).toEqual({ requested: true, applied: false, unavailable: 'unknown' });
    expect(rhythmFitting(true, { rhythm_applied: false, rhythm_unavailable_reason: null }).unavailable).toBe('unknown');
  });
});

describe('rhythmNote', () => {
  it('says nothing when off or applied', () => {
    expect(rhythmNote(RHYTHM_OFF)).toBeNull();
    expect(rhythmNote({ requested: true, applied: true, unavailable: null })).toBeNull();
  });

  it('names each reason', () => {
    expect(rhythmNote({ requested: true, applied: false, unavailable: 'no_score' })).toMatch(/no score/i);
    expect(rhythmNote({ requested: true, applied: false, unavailable: 'slot_unplaced' })).toMatch(/not placed/i);
    expect(rhythmNote({ requested: true, applied: false, unavailable: 'unknown' })).toMatch(/unavailable/i);
  });
});

describe('rhythmPercent', () => {
  it('rounds to a whole percent and clamps', () => {
    expect(rhythmPercent(0.666)).toBe(67);
    expect(rhythmPercent(1)).toBe(100);
    expect(rhythmPercent(0)).toBe(0);
    expect(rhythmPercent(1.2)).toBe(100);
    expect(rhythmPercent(-0.1)).toBe(0);
  });

  it('is null without a value', () => {
    expect(rhythmPercent(null)).toBeNull();
    expect(rhythmPercent(undefined)).toBeNull();
    expect(rhythmPercent(Number.NaN)).toBeNull();
  });
});

describe('listPresentation', () => {
  it('bands the list whenever rhythm was applied, also in rhyme order', () => {
    expect(listPresentation(RHYME_ORDERING, { requested: true, applied: true, unavailable: null })).toBe('bands');
  });

  it('follows the order otherwise', () => {
    expect(listPresentation(RHYME_ORDERING, RHYTHM_OFF)).toBe('plain');
    expect(listPresentation({ requested: 'meaning', applied: 'meaning' }, RHYTHM_OFF)).toBe('bands');
    expect(listPresentation({ requested: 'meaning', applied: 'rhyme' }, RHYTHM_OFF)).toBe('unavailable');
    expect(listPresentation(RHYME_ORDERING, { requested: true, applied: false, unavailable: 'no_score' })).toBe('plain');
  });

  it('keeps bands when meaning fell back but rhythm still banded', () => {
    expect(listPresentation({ requested: 'meaning', applied: 'rhyme' }, { requested: true, applied: true, unavailable: null })).toBe(
      'bands',
    );
  });
});

describe('persistence', () => {
  it('parses only the two stored values', () => {
    expect(parseRhythmFit('on')).toBe(true);
    expect(parseRhythmFit('off')).toBe(false);
    expect(parseRhythmFit('true')).toBeNull();
    expect(parseRhythmFit(null)).toBeNull();
  });

  it('defaults to off', () => {
    expect(loadRhythmFit(null)).toBe(false);
    expect(loadRhythmFit(memory())).toBe(false);
    expect(loadRhythmFit(memory({ [RHYTHM_FIT_STORAGE_KEY]: 'garbage' }))).toBe(false);
  });

  it('round-trips a saved choice', () => {
    const storage = memory();
    expect(saveRhythmFit(storage, true)).toEqual({ ok: true });
    expect(storage.data[RHYTHM_FIT_STORAGE_KEY]).toBe('on');
    expect(loadRhythmFit(storage)).toBe(true);
    saveRhythmFit(storage, false);
    expect(loadRhythmFit(storage)).toBe(false);
  });

  it('falls back to off when reading throws', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const storage: KeyValueStorage = {
      getItem: () => {
        throw new Error('SecurityError');
      },
      setItem: () => undefined,
    };
    expect(loadRhythmFit(storage)).toBe(false);
    expect(warn).toHaveBeenCalled();
    warn.mockRestore();
  });

  it('reports a typed failure when storage is missing or refuses the write', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    expect(saveRhythmFit(null, true)).toEqual({ ok: false, error: { kind: 'unavailable' } });
    const cause = new Error('QuotaExceededError');
    const refusing: KeyValueStorage = {
      getItem: () => null,
      setItem: () => {
        throw cause;
      },
    };
    expect(saveRhythmFit(refusing, true)).toEqual({ ok: false, error: { kind: 'write_failed', cause } });
    warn.mockRestore();
  });
});
