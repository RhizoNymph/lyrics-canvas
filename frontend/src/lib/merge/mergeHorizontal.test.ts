import { describe, it, expect } from 'vitest';
import { mergeHorizontal } from './mergeHorizontal';
import { line, snippet, tag } from './fixtures';

describe('mergeHorizontal', () => {
  it('joins line i of each snippet with a space and concatenates the cells', () => {
    const a = snippet('a', 'one two\nthree');
    const b = snippet('b', 'four\nfive six');
    const merged = mergeHorizontal([a, b]);
    expect(merged.raw_text).toBe('one two four\nthree five six');
    expect(merged.grid_data.lines.map((l) => l.raw_text)).toEqual(['one two four', 'three five six']);
    expect(merged.grid_data.lines.map((l) => l.cells.map((c) => c.syllable))).toEqual([
      ['one', 'two', 'four'],
      ['three', 'five', 'six'],
    ]);
    expect(merged.grid_data.lines.map((l) => l.syllable_count)).toEqual([3, 3]);
  });

  it('re-bases word_index past the words already on the line', () => {
    const a = snippet('a', 'one two');
    const b = snippet('b', 'three');
    const c = snippet('c', 'four five');
    const merged = mergeHorizontal([a, b, c]);
    expect(merged.grid_data.lines[0].cells.map((cell) => cell.word_index)).toEqual([0, 1, 2, 3, 4]);
  });

  it('re-bases by the text word count, not by the cells, so words without cells still count', () => {
    // "one two" has text for two words but only a cell for the first
    const a = snippet('a', 'one two', { grid_data: { lines: [line('one two', 'one')] } });
    const b = snippet('b', 'three');
    const merged = mergeHorizontal([a, b]);
    expect(merged.grid_data.lines[0].cells.map((cell) => cell.word_index)).toEqual([0, 2]);
  });

  it('a missing line in the middle contributes nothing; later contributors follow directly', () => {
    const a = snippet('a', 'a1\na2\na3');
    const b = snippet('b', 'b1');
    const c = snippet('c', 'c1\nc2');
    const merged = mergeHorizontal([a, b, c]);
    expect(merged.raw_text).toBe('a1 b1 c1\na2 c2\na3');
    expect(merged.grid_data.lines[1].cells).toEqual([
      { syllable: 'a2', stress: 1, word_index: 0 },
      { syllable: 'c2', stress: 1, word_index: 1 },
    ]);
  });

  it('keeps rests (no word_index) in place', () => {
    const a = snippet('a', 'one', { grid_data: { lines: [line('one', 'one .')] } });
    const b = snippet('b', 'two', { grid_data: { lines: [line('two', '. two')] } });
    const merged = mergeHorizontal([a, b]);
    expect(merged.grid_data.lines[0].cells).toEqual([
      { syllable: 'one', stress: 1, word_index: 0 },
      { rest: true },
      { rest: true },
      { syllable: 'two', stress: 1, word_index: 1 },
    ]);
    expect(merged.grid_data.lines[0].syllable_count).toBe(2);
  });

  it('takes type, grid mode, position and group from the first snippet', () => {
    const a = snippet('a', 'one', { snippet_type: 'hook', position_x: 10, position_y: 20, group_id: 'g1' });
    const b = snippet('b', 'two', { snippet_type: 'verse', position_x: 1, position_y: 2, group_id: 'g2' });
    expect(mergeHorizontal([a, b])).toMatchObject({
      snippet_type: 'hook',
      grid_mode: 'auto',
      beats_per_line: null,
      position_x: 10,
      position_y: 20,
      group_id: 'g1',
      source_ids: ['a', 'b'],
    });
  });

  it('fixed mode pads to the longest joined line', () => {
    const a = snippet('a', 'one\ntwo three', { grid_mode: 'fixed', beats_per_line: 2 });
    const b = snippet('b', 'four five');
    const merged = mergeHorizontal([a, b]);
    expect(merged.beats_per_line).toBe(3);
    expect(merged.grid_data.lines.map((l) => l.cells.length)).toEqual([3, 3]);
    expect(merged.grid_data.lines[1].cells.at(-1)).toEqual({ rest: true });
  });

  it('unions tags and uses cell-less text lines for a snippet without a grid', () => {
    const a = snippet('a', 'one', { tags: [tag('t1')] });
    const b = snippet('b', 'two', { grid_data: null, tags: [tag('t2')] });
    const merged = mergeHorizontal([a, b]);
    expect(merged.tag_ids).toEqual(['t1', 't2']);
    expect(merged.raw_text).toBe('one two');
    expect(merged.grid_data.lines[0].cells.map((c) => c.syllable)).toEqual(['one']);
  });
});
