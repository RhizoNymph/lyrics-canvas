import { describe, it, expect, beforeEach } from 'vitest';
import { usePlacementNoticeStore } from './placementNoticeStore';

beforeEach(() => usePlacementNoticeStore.setState({ notice: null }));

describe('placementNoticeStore', () => {
  it('shows a notice with an optional canvas to open', () => {
    usePlacementNoticeStore.getState().show('Moved 1 snippet to T', 'info', { id: 't', title: 'T' });
    expect(usePlacementNoticeStore.getState().notice).toMatchObject({
      text: 'Moved 1 snippet to T',
      tone: 'info',
      open: { id: 't', title: 'T' },
    });
  });

  it('a newer notice replaces the old one, and dismissing the old id keeps the newer', () => {
    const { show, dismiss } = usePlacementNoticeStore.getState();
    show('first', 'info');
    const first = usePlacementNoticeStore.getState().notice!.id;
    show('second', 'error');
    dismiss(first);
    expect(usePlacementNoticeStore.getState().notice).toMatchObject({ text: 'second', tone: 'error', open: null });
    dismiss(usePlacementNoticeStore.getState().notice!.id);
    expect(usePlacementNoticeStore.getState().notice).toBeNull();
  });
});
