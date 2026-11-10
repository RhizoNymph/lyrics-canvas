import { describe, it, expect } from 'vitest';
import { snippet } from '@/lib/merge/fixtures';
import type { Group, Snippet } from '@/types';
import { dropSnapshotsWith, mapSnapshotSnippets } from './history';
import type { HistorySnapshot } from './history';

function snap(...snippets: Snippet[]): HistorySnapshot {
  return { snippets: new Map(snippets.map((s) => [s.id, s])), groups: new Map<string, Group>() };
}

const x = snippet('x', 'x');
const y = snippet('y', 'y');
const z = snippet('z', 'z');

describe('dropSnapshotsWith', () => {
  it('drops every snapshot that holds one of the ids and keeps the rest in order', () => {
    const s1 = snap(y);
    const s2 = snap(x, y);
    const s3 = snap(z);
    const s4 = snap(y, z);
    expect(dropSnapshotsWith([s1, s2, s3, s4], new Set(['x', 'z']))).toEqual([s1]);
    expect(dropSnapshotsWith([s1, s2, s3, s4], new Set(['x']))).toEqual([s1, s3, s4]);
  });

  it('returns the same array when nothing references the ids', () => {
    const stack = [snap(y)];
    expect(dropSnapshotsWith(stack, new Set(['x']))).toBe(stack);
    expect(dropSnapshotsWith(stack, new Set())).toBe(stack);
  });
});

describe('mapSnapshotSnippets', () => {
  it('rewrites the given snippets in every snapshot that holds them', () => {
    const s1 = snap(x, y);
    const s2 = snap(y);
    const out = mapSnapshotSnippets([s1, s2], new Set(['x']), (s) => ({ ...s, raw_text: 'X' }));
    expect(out[0].snippets.get('x')?.raw_text).toBe('X');
    expect(out[0].snippets.get('y')).toBe(y);
    expect(out[1]).toBe(s2);
    // The input snapshot is untouched.
    expect(s1.snippets.get('x')?.raw_text).toBe('x');
  });

  it('keeps a snapshot when the update returns the same snippet', () => {
    const s1 = snap(x);
    const stack = [s1];
    expect(mapSnapshotSnippets(stack, new Set(['x']), (s) => s)).toBe(stack);
  });
});
