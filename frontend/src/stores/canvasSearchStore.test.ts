import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { CANVAS_SEARCH_DEBOUNCE_MS, resetCanvasSearchSession, useCanvasSearchStore } from './canvasSearchStore';

const s = () => useCanvasSearchStore.getState();

beforeEach(() => {
  vi.useFakeTimers();
  resetCanvasSearchSession();
});
afterEach(() => vi.useRealTimers());

describe('canvasSearchStore', () => {
  it('applies the typed text after the debounce', () => {
    s().open('c1');
    s().setText('Cat');
    expect(s().text).toBe('Cat');
    expect(s().query.terms).toEqual([]);
    vi.advanceTimersByTime(CANVAS_SEARCH_DEBOUNCE_MS - 1);
    expect(s().query.terms).toEqual([]);
    vi.advanceTimersByTime(1);
    expect(s().query.terms).toEqual(['cat']);
  });

  it('restarts the debounce on every keystroke', () => {
    s().open('c1');
    s().setText('c');
    vi.advanceTimersByTime(100);
    s().setText('ca');
    vi.advanceTimersByTime(100);
    expect(s().query.terms).toEqual([]);
    vi.advanceTimersByTime(50);
    expect(s().query.terms).toEqual(['ca']);
  });

  it('clear empties the box and the filter at once', () => {
    s().open('c1');
    s().setText('cat');
    vi.advanceTimersByTime(CANVAS_SEARCH_DEBOUNCE_MS);
    s().setText('cats');
    s().clear();
    expect(s().text).toBe('');
    expect(s().query.terms).toEqual([]);
    vi.advanceTimersByTime(CANVAS_SEARCH_DEBOUNCE_MS);
    expect(s().query.terms).toEqual([]);
  });

  it('keeps the query per canvas for the session', () => {
    s().open('c1');
    s().setText('cat');
    s().open('c2');
    expect(s().text).toBe('');
    expect(s().query.terms).toEqual([]);
    vi.advanceTimersByTime(CANVAS_SEARCH_DEBOUNCE_MS);
    expect(s().query.terms).toEqual([]);
    s().open('c1');
    expect(s().text).toBe('cat');
    expect(s().query.terms).toEqual(['cat']);
  });

  it('a pending debounce from another canvas never applies to this one', () => {
    s().open('c1');
    s().setText('cat');
    s().open('c2');
    s().setText('dog');
    vi.advanceTimersByTime(CANVAS_SEARCH_DEBOUNCE_MS);
    expect(s().query.terms).toEqual(['dog']);
    s().open('c1');
    expect(s().query.terms).toEqual(['cat']);
  });

  it('counts focus requests and keeps one reveal request', () => {
    const before = s().focusRequest;
    s().requestFocus();
    expect(s().focusRequest).toBe(before + 1);
    s().requestReveal('s1');
    expect(s().revealSnippetId).toBe('s1');
    s().clearReveal('other');
    expect(s().revealSnippetId).toBe('s1');
    s().clearReveal('s1');
    expect(s().revealSnippetId).toBeNull();
  });
});
