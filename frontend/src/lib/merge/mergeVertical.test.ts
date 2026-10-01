import { describe, it, expect } from 'vitest';
import { mergeVertical } from './mergeVertical';
import { line, snippet, tag } from './fixtures';

describe('mergeVertical', () => {
  it('stacks all lines in order and joins the text with newlines', () => {
    const a = snippet('a', 'one two\nthree', { position_x: 10, position_y: 20 });
    const b = snippet('b', 'four', { position_x: 500, position_y: 600 });
    const merged = mergeVertical([a, b]);
    expect(merged.raw_text).toBe('one two\nthree\nfour');
    expect(merged.grid_data.lines.map((l) => l.raw_text)).toEqual(['one two', 'three', 'four']);
    expect(merged.grid_data.lines.map((l) => l.syllable_count)).toEqual([2, 1, 1]);
    expect(merged.grid_data.lines[0].cells).toEqual(a.grid_data!.lines[0].cells);
    expect(merged.source_ids).toEqual(['a', 'b']);
  });

  it('takes type, grid mode, position and group from the first snippet', () => {
    const a = snippet('a', 'one', { snippet_type: 'chorus', position_x: 10, position_y: 20, group_id: 'g1' });
    const b = snippet('b', 'two', { snippet_type: 'verse', position_x: 1, position_y: 2, group_id: 'g2' });
    const merged = mergeVertical([a, b]);
    expect(merged).toMatchObject({
      snippet_type: 'chorus',
      grid_mode: 'auto',
      beats_per_line: null,
      position_x: 10,
      position_y: 20,
      group_id: 'g1',
    });
  });

  it('auto mode keeps ragged syllable counts as they are', () => {
    const merged = mergeVertical([snippet('a', 'one two three'), snippet('b', 'four')]);
    expect(merged.grid_data.lines.map((l) => l.cells.length)).toEqual([3, 1]);
    expect(merged.beats_per_line).toBeNull();
  });

  it('fixed mode pads shorter lines with rests to beats_per_line', () => {
    const a = snippet('a', 'one two', { grid_mode: 'fixed', beats_per_line: 4 });
    const b = snippet('b', 'three', { grid_mode: 'fixed', beats_per_line: 2 });
    const merged = mergeVertical([a, b]);
    expect(merged.grid_mode).toBe('fixed');
    expect(merged.beats_per_line).toBe(4);
    expect(merged.grid_data.lines.map((l) => l.cells.length)).toEqual([4, 4]);
    expect(merged.grid_data.lines[1].cells.slice(1)).toEqual([{ rest: true }, { rest: true }, { rest: true }]);
    expect(merged.grid_data.lines.map((l) => l.syllable_count)).toEqual([2, 1]);
  });

  it('fixed mode extends beats_per_line to the longest line rather than truncating', () => {
    const a = snippet('a', 'one', { grid_mode: 'fixed', beats_per_line: 2 });
    const b = snippet('b', 'two three four five five');
    const merged = mergeVertical([a, b]);
    expect(merged.beats_per_line).toBe(5);
    expect(merged.grid_data.lines.map((l) => l.cells.length)).toEqual([5, 5]);
    expect(merged.grid_data.lines[1].cells.map((c) => c.syllable)).toEqual(['two', 'three', 'four', 'five', 'five']);
  });

  it('keeps existing rests and stress from the originals', () => {
    const a = snippet('a', 'one two', { grid_data: { lines: [line('one two', 'one . two')] } });
    const b = snippet('b', 'three');
    const merged = mergeVertical([a, b]);
    expect(merged.grid_data.lines[0].cells).toEqual([
      { syllable: 'one', stress: 1, word_index: 0 },
      { rest: true },
      { syllable: 'two', stress: 1, word_index: 1 },
    ]);
  });

  it('unions tags in order', () => {
    const a = snippet('a', 'one', { tags: [tag('t1')] });
    const b = snippet('b', 'two', { tags: [tag('t2'), tag('t1')] });
    expect(mergeVertical([a, b]).tag_ids).toEqual(['t1', 't2']);
  });

  it('a snippet without a grid contributes cell-less text lines', () => {
    const a = snippet('a', 'one');
    const b = snippet('b', 'two three', { grid_data: null });
    const merged = mergeVertical([a, b]);
    expect(merged.raw_text).toBe('one\ntwo three');
    expect(merged.grid_data.lines[1]).toEqual({ cells: [], syllable_count: 0, raw_text: 'two three' });
  });

  it('does not share cell objects with the originals', () => {
    const a = snippet('a', 'one');
    const merged = mergeVertical([a, snippet('b', 'two')]);
    expect(merged.grid_data.lines[0].cells).not.toBe(a.grid_data!.lines[0].cells);
  });
});
