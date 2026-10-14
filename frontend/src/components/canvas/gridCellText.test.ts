import { describe, it, expect } from 'vitest';
import { cellLabel, cellTextKind } from './gridCellText';

describe('gridCellText', () => {
  it('classifies rests, stressed and unstressed syllables', () => {
    expect(cellTextKind({ rest: true })).toBe('rest');
    expect(cellTextKind({ syllable: 'la', stress: 1 })).toBe('stressed');
    expect(cellTextKind({ syllable: 'la', stress: 2 })).toBe('stressed');
    expect(cellTextKind({ syllable: 'la', stress: 0 })).toBe('normal');
    expect(cellTextKind({ syllable: 'la' })).toBe('normal');
  });

  it('labels rests with a dot and syllables with their text', () => {
    expect(cellLabel({ rest: true, syllable: 'x' })).toBe('·');
    expect(cellLabel({ syllable: 'la' })).toBe('la');
    expect(cellLabel({})).toBe('');
  });
});
