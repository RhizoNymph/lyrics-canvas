import { describe, it, expect } from 'vitest';
import { buildDetectRequest, buildSuggestRequest, slotSpecs } from './request';
import type { PatternParams } from './request';
import type { PatternSession, SlotEditState } from './types';

const session: PatternSession = { canvasId: 'c1', snippetId: 'sn', lineStart: 2, lineEnd: 5 };

const edit: SlotEditState = {
  lines: [{ line: 2, wordCount: 5 }, { line: 3, wordCount: 5 }, { line: 4, wordCount: 5 }],
  slots: [
    { id: 's0', on: true, members: [{ line: 2, start: 0, end: 1 }, { line: 4, start: 1, end: 3 }] },
    { id: 's1', on: false, members: [{ line: 2, start: 2, end: 3 }, { line: 3, start: 2, end: 3 }] },
    { id: 'u0', on: true, members: [{ line: 3, start: 4, end: 5 }] },
  ],
  nextUserId: 1,
};

const params: PatternParams = {
  minScore: 0.5,
  sources: ['notes', 'dictionary'],
  includeRare: false,
  tagFilter: null,
  excludeIds: ['dict:x'],
};

describe('buildDetectRequest', () => {
  it('sends the range and the detection threshold', () => {
    expect(buildDetectRequest(session, 0.75)).toEqual({
      canvas_id: 'c1',
      snippet_id: 'sn',
      line_start: 2,
      line_end: 5,
      weights: null,
      dialect: null,
      min_score: 0.75,
    });
  });
});

describe('slotSpecs', () => {
  it('sends only the on slots, in slot order, members in line order, ids kept, k derived', () => {
    expect(slotSpecs(edit)).toEqual([
      { id: 's0', k: null, members: [{ line_index: 2, word_start: 0, word_end: 1 }, { line_index: 4, word_start: 1, word_end: 3 }] },
      { id: 'u0', k: null, members: [{ line_index: 3, word_start: 4, word_end: 5 }] },
    ]);
  });
});

describe('buildSuggestRequest', () => {
  it('builds the first page with line matches', () => {
    const req = buildSuggestRequest(session, edit, params, { kind: 'first' });
    expect(req).toEqual({
      canvas_id: 'c1',
      snippet_id: 'sn',
      line_start: 2,
      line_end: 5,
      weights: null,
      dialect: null,
      slots: slotSpecs(edit),
      min_score: 0.5,
      sources: ['notes', 'dictionary'],
      include_rare: false,
      exclude_ids: ['dict:x'],
      limit: 30,
      offsets: {},
      line_matches: true,
      line_match_limit: 30,
      line_match_offset: 0,
    });
  });

  it('pages one slot through offsets without line matches', () => {
    const req = buildSuggestRequest(session, edit, params, { kind: 'slot_more', slotId: 'u0', offset: 30 });
    expect(req).toMatchObject({ offsets: { u0: 30 }, line_matches: false, line_match_offset: 0 });
  });

  it('pages the line matches', () => {
    const req = buildSuggestRequest(session, edit, params, { kind: 'line_more', offset: 30 });
    expect(req).toMatchObject({ offsets: {}, line_matches: true, line_match_offset: 30 });
  });

  it('sends the tag filter only when set', () => {
    expect(buildSuggestRequest(session, edit, { ...params, tagFilter: 'a & b' }, { kind: 'first' })).toMatchObject({ tag_filter: 'a & b' });
    expect(buildSuggestRequest(session, edit, params, { kind: 'first' })).not.toHaveProperty('tag_filter');
  });

  it('is null with no on slot or no source', () => {
    const allOff = { ...edit, slots: edit.slots.map((s) => ({ ...s, on: false })) };
    expect(buildSuggestRequest(session, allOff, params, { kind: 'first' })).toBeNull();
    expect(buildSuggestRequest(session, { ...edit, slots: [] }, params, { kind: 'first' })).toBeNull();
    expect(buildSuggestRequest(session, edit, { ...params, sources: [] }, { kind: 'first' })).toBeNull();
  });
});
