import { describe, it, expect } from 'vitest';
import { snippet } from '@/lib/merge/fixtures';
import type { Canvas, TransferResponse } from '@/types';
import { TRANSFER_MAX, checkTransferIds, pickerCanvases, showOnTarget, transferNotice } from './transfer';

function canvas(id: string, title: string, updated_at: string): Canvas {
  return { id, owner_id: 'u', title, is_public: false, share_token: null, viewport_state: null, created_at: '', updated_at };
}

const list = [
  canvas('old', 'Old hooks', '2026-01-01T00:00:00Z'),
  canvas('cur', 'Current', '2026-11-01T00:00:00Z'),
  canvas('new', 'New verses', '2026-11-08T00:00:00Z'),
  canvas('mid', 'Mid HOOKS', '2026-06-01T00:00:00Z'),
];

describe('pickerCanvases', () => {
  it('lists the other canvases, newest updated first', () => {
    expect(pickerCanvases(list, 'cur', '').map((c) => c.id)).toEqual(['new', 'mid', 'old']);
  });

  it('filters by a case-insensitive title substring, ignoring surrounding spaces', () => {
    expect(pickerCanvases(list, 'cur', '  hooks ').map((c) => c.id)).toEqual(['mid', 'old']);
    expect(pickerCanvases(list, 'cur', 'nothing')).toEqual([]);
  });

  it('never offers the current canvas', () => {
    expect(pickerCanvases(list, 'cur', 'current')).toEqual([]);
  });
});

describe('checkTransferIds', () => {
  it('accepts 1..500 distinct ids', () => {
    expect(checkTransferIds(['a', 'b', 'a'])).toEqual({ ok: true, ids: ['a', 'b'] });
    const many = Array.from({ length: TRANSFER_MAX }, (_, i) => `s${i}`);
    expect(checkTransferIds(many)).toEqual({ ok: true, ids: many });
  });

  it('refuses none or more than 500', () => {
    expect(checkTransferIds([])).toEqual({ ok: false, kind: 'nothing_selected' });
    expect(checkTransferIds(Array.from({ length: TRANSFER_MAX + 1 }, (_, i) => `s${i}`))).toEqual({
      ok: false,
      kind: 'too_many_snippets',
    });
  });
});

function response(overrides: Partial<TransferResponse> = {}): TransferResponse {
  return { target_canvas_id: 't', placed: [], already_on_target: [], removed_from_source: false, ...overrides };
}

describe('transferNotice', () => {
  it('moved', () => {
    expect(transferNotice('move', 'Hooks', response({ placed: ['a', 'b', 'c'], removed_from_source: true }))).toBe(
      'Moved 3 snippets to Hooks',
    );
    expect(transferNotice('move', 'Hooks', response({ placed: ['a'], removed_from_source: true }))).toBe(
      'Moved 1 snippet to Hooks',
    );
  });

  it('moved with some already there', () => {
    expect(
      transferNotice('move', 'Hooks', response({ placed: ['a', 'b'], already_on_target: ['c'], removed_from_source: true })),
    ).toBe('Moved 3 snippets to Hooks (1 was already there)');
  });

  it('shown', () => {
    expect(transferNotice('show', 'Hooks', response({ placed: ['a', 'b'], already_on_target: ['c'] }))).toBe(
      'Showing 3 snippets on Hooks (1 was already there)',
    );
    expect(transferNotice('show', 'Hooks', response({ placed: ['a'] }))).toBe('Showing 1 snippet on Hooks');
    expect(transferNotice('show', 'Hooks', response({ already_on_target: ['a', 'b'] }))).toBe(
      'Showing 2 snippets on Hooks (2 were already there)',
    );
  });
});

describe('showOnTarget', () => {
  const target = { id: 't', title: 'Target' };

  it('adds the target to the canvases of every transferred snippet still here', () => {
    const a = snippet('a', 'a', { canvases: [{ id: 'c1', title: 'Canvas' }] });
    const b = snippet('b', 'b', { canvases: [{ id: 'c1', title: 'Canvas' }, target] });
    const other = snippet('o', 'o');
    const map = new Map([a, b, other].map((s) => [s.id, s]));
    const next = showOnTarget(map, ['a', 'b', 'gone'], target);
    expect(next.get('a')?.canvases).toEqual([{ id: 'c1', title: 'Canvas' }, target]);
    expect(next.get('b')).toBe(b);
    expect(next.get('o')).toBe(other);
    expect(next.has('gone')).toBe(false);
    expect(map.get('a')).toBe(a);
  });
});
