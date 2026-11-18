import { describe, it, expect } from 'vitest';
import type { GridData } from '@/types';
import { initEditorState, replaceGridData, rawTextForSave } from './editorState';

const saved: GridData = {
  lines: [
    { cells: [{ syllable: 'I', stress: 0, word_index: 0, word: 'I' }, { syllable: 'go', stress: 0, word_index: 1, word: 'go' }], syllable_count: 2, raw_text: 'I go' },
    { cells: [{ syllable: 'now', stress: 0, word_index: 0, word: 'now' }], syllable_count: 1, raw_text: 'now' },
  ],
};

describe('replaceGridData', () => {
  it('replaces only the changed lines, keeping blank rows', () => {
    const state = initEditorState('I go\n\nnow', saved);
    const first = state.gridData!.lines[0];
    const linked: GridData = {
      lines: [
        { ...first, cells: [first.cells[0], { rest: true }, first.cells[1]], raw_text: 'I _ go' },
        state.gridData!.lines[1],
      ],
    };
    const next = replaceGridData(state, linked);
    expect(next.lines).toEqual(['I _ go', '', 'now']);
    expect(next.gridData!.lines[1]).toBe(state.gridData!.lines[1]);
    expect(next.arranged.has(0)).toBe(true);
    expect(rawTextForSave(next)).toBe('I _ go\n\nnow');
  });

  it('returns the same state for the same lines or a different line count', () => {
    const state = initEditorState('I go\nnow', saved);
    expect(replaceGridData(state, { lines: [...state.gridData!.lines] })).toBe(state);
    expect(replaceGridData(state, { lines: [state.gridData!.lines[0]] })).toBe(state);
  });
});
