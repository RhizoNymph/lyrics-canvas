import { describe, it, expect } from 'vitest';
import { PlacementError } from './errors';
import { removalFailureNotice } from './removal';

const err = (kind: 'network' | 'server') => new PlacementError(kind, 'remove_from_canvas', '');

describe('removalFailureNotice', () => {
  it('is null when everything succeeded', () => {
    expect(removalFailureNotice({ removed: ['a'], deletedForGood: [], failures: [] }, 'remove')).toBeNull();
  });

  it('counts failures and describes the first error', () => {
    expect(
      removalFailureNotice({ removed: [], deletedForGood: [], failures: [{ id: 'a', error: err('network') }] }, 'remove'),
    ).toBe('Could not remove 1 snippet from this canvas: Could not reach the server');
    expect(
      removalFailureNotice(
        { removed: [], deletedForGood: [], failures: [{ id: 'a', error: err('server') }, { id: 'b', error: err('network') }] },
        'delete_everywhere',
      ),
    ).toBe('Could not delete 2 snippets: The server failed; try again');
  });
});
