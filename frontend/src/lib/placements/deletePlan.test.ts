import { describe, it, expect } from 'vitest';
import { snippet } from '@/lib/merge/fixtures';
import { deleteEverywhereConfirm, deleteMenu } from './deletePlan';

const here = { id: 'c1', title: 'Canvas' };
const hooks = { id: 'h', title: 'Hooks' };
const album = { id: 'a', title: 'Album' };

const solo = snippet('solo', 'x', { canvases: [here] });
const shared = snippet('shared', 'y', { canvases: [album, here] });
const sharedMore = snippet('more', 'z', { canvases: [album, here, hooks] });

describe('deleteMenu', () => {
  it('a snippet only here deletes as today and has no Delete everywhere', () => {
    expect(deleteMenu([solo], 'c1')).toEqual({ removeLabel: 'Delete snippet', anyShared: false, offerDeleteEverywhere: false });
    expect(deleteMenu([solo, snippet('s2', 'q', { canvases: [here] })], 'c1').removeLabel).toBe('Delete 2 snippets');
  });

  it('any shared target labels the delete "Remove from this canvas" and offers Delete everywhere', () => {
    expect(deleteMenu([solo, shared], 'c1')).toEqual({
      removeLabel: 'Remove from this canvas',
      anyShared: true,
      offerDeleteEverywhere: true,
    });
  });
});

describe('deleteEverywhereConfirm', () => {
  it('names the other canvases once each, sorted', () => {
    expect(deleteEverywhereConfirm([shared, sharedMore, solo], 'c1')).toBe(
      'Delete 3 snippets everywhere? They will also disappear from Album and Hooks. Recordings are deleted too. This cannot be undone.',
    );
  });

  it('one snippet', () => {
    expect(deleteEverywhereConfirm([shared], 'c1')).toBe(
      'Delete this snippet everywhere? It will also disappear from Album. Recordings are deleted too. This cannot be undone.',
    );
  });

  it('three or more canvases are listed with commas', () => {
    const wide = snippet('w', 'w', { canvases: [album, { id: 'b', title: 'B-sides' }, here, hooks] });
    expect(deleteEverywhereConfirm([wide], 'c1')).toContain('from Album, B-sides and Hooks.');
  });
});
