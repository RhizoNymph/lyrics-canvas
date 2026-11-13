import { describe, it, expect } from 'vitest';
import { EMPTY_PICKS, pickTexts, prunePicks, togglePick, trayEntries, trayLabel, tupleText } from './tray';

const order = [
  { id: 's0', letter: 'A', on: true },
  { id: 's1', letter: 'B', on: true },
  { id: 's2', letter: 'C', on: true },
];

describe('togglePick', () => {
  it('picks one candidate per slot; another pick replaces it; the same pick again clears', () => {
    let picks = togglePick(EMPTY_PICKS, 's0', { candidateId: 'dict:a', text: 'steady' });
    expect(picks.get('s0')).toEqual({ candidateId: 'dict:a', text: 'steady' });
    picks = togglePick(picks, 's0', { candidateId: 'dict:b', text: 'ready' });
    expect(picks.get('s0')?.text).toBe('ready');
    picks = togglePick(picks, 's0', { candidateId: 'dict:b', text: 'ready' });
    expect(picks.has('s0')).toBe(false);
  });

  it('does not mutate the input', () => {
    const before = togglePick(EMPTY_PICKS, 's0', { candidateId: 'x', text: 'x' });
    togglePick(before, 's1', { candidateId: 'y', text: 'y' });
    expect(before.size).toBe(1);
  });
});

describe('tray', () => {
  const picks = togglePick(togglePick(EMPTY_PICKS, 's0', { candidateId: 'a', text: 'steady' }), 's2', { candidateId: 'c', text: 'strong' });

  it('lists the on slots in order with gaps', () => {
    expect(trayEntries(order, picks)).toEqual([
      { slotId: 's0', letter: 'A', text: 'steady' },
      { slotId: 's1', letter: 'B', text: null },
      { slotId: 's2', letter: 'C', text: 'strong' },
    ]);
    expect(trayLabel(trayEntries(order, picks))).toBe('steady · ___ · strong');
  });

  it('leaves off slots out', () => {
    const entries = trayEntries([order[0], order[1], { ...order[2], on: false }], picks);
    expect(entries.map((e) => e.slotId)).toEqual(['s0', 's1']);
  });

  it('makes the snippet line from the picks in slot order, joined by spaces', () => {
    expect(tupleText(trayEntries(order, picks))).toBe('steady strong');
    expect(tupleText(trayEntries(order, EMPTY_PICKS))).toBe('');
    expect(pickTexts(trayEntries(order, picks))).toEqual(['steady', 'strong']);
  });

  it('prunes picks of slots that are gone', () => {
    expect([...prunePicks(picks, new Set(['s0'])).keys()]).toEqual(['s0']);
    expect(prunePicks(picks, new Set(['s0', 's2']))).toBe(picks);
  });
});
