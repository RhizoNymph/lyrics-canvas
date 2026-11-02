import { describe, it, expect } from 'vitest';
import { rowKeyAction } from './editorKeys';
import type { RowKeyInput } from './editorKeys';

const input = (over: Partial<RowKeyInput>): RowKeyInput => ({
  key: 'Enter',
  text: 'hello world',
  selectionStart: 5,
  selectionEnd: 5,
  row: 1,
  rowCount: 3,
  composing: false,
  ...over,
});

describe('rowKeyAction: Enter', () => {
  it('splits at the caret', () => {
    expect(rowKeyAction(input({}))).toEqual({ kind: 'split', text: 'hello world', caret: 5 });
  });

  it('splits at the end (a blank row is inserted by splitRow)', () => {
    expect(rowKeyAction(input({ selectionStart: 11, selectionEnd: 11 }))).toEqual({ kind: 'split', text: 'hello world', caret: 11 });
  });

  it('replaces a selected range before splitting', () => {
    expect(rowKeyAction(input({ selectionStart: 5, selectionEnd: 8 }))).toEqual({ kind: 'split', text: 'hellorld', caret: 5 });
  });

  it('is ignored while an IME composition is in progress', () => {
    expect(rowKeyAction(input({ composing: true }))).toEqual({ kind: 'none' });
  });
});

describe('rowKeyAction: Backspace', () => {
  it('deletes an empty row when there is more than one row (also the first row)', () => {
    expect(rowKeyAction(input({ key: 'Backspace', text: '', selectionStart: 0, selectionEnd: 0 }))).toEqual({ kind: 'deleteEmpty' });
    expect(rowKeyAction(input({ key: 'Backspace', text: '', selectionStart: 0, selectionEnd: 0, row: 0 }))).toEqual({ kind: 'deleteEmpty' });
  });

  it('leaves the only row alone', () => {
    expect(rowKeyAction(input({ key: 'Backspace', text: '', selectionStart: 0, selectionEnd: 0, row: 0, rowCount: 1 }))).toEqual({ kind: 'none' });
  });

  it('joins onto the previous row at caret 0 of a non-first row', () => {
    expect(rowKeyAction(input({ key: 'Backspace', selectionStart: 0, selectionEnd: 0 }))).toEqual({ kind: 'joinPrevious' });
  });

  it('does nothing special at caret 0 of the first row', () => {
    expect(rowKeyAction(input({ key: 'Backspace', selectionStart: 0, selectionEnd: 0, row: 0 }))).toEqual({ kind: 'none' });
  });

  it('lets the input delete normally mid-line or with a selection starting at 0', () => {
    expect(rowKeyAction(input({ key: 'Backspace' }))).toEqual({ kind: 'none' });
    expect(rowKeyAction(input({ key: 'Backspace', selectionStart: 0, selectionEnd: 3 }))).toEqual({ kind: 'none' });
  });

  it('is ignored while composing', () => {
    expect(rowKeyAction(input({ key: 'Backspace', selectionStart: 0, selectionEnd: 0, composing: true }))).toEqual({ kind: 'none' });
  });
});

describe('rowKeyAction: Delete', () => {
  it('joins the next row at the caret end of a non-last row', () => {
    expect(rowKeyAction(input({ key: 'Delete', selectionStart: 11, selectionEnd: 11 }))).toEqual({ kind: 'joinNext' });
  });

  it('pulls the next row into an empty row', () => {
    expect(rowKeyAction(input({ key: 'Delete', text: '', selectionStart: 0, selectionEnd: 0 }))).toEqual({ kind: 'joinNext' });
  });

  it('does nothing special on the last row, mid-line or with a selection', () => {
    expect(rowKeyAction(input({ key: 'Delete', selectionStart: 11, selectionEnd: 11, row: 2 }))).toEqual({ kind: 'none' });
    expect(rowKeyAction(input({ key: 'Delete' }))).toEqual({ kind: 'none' });
    expect(rowKeyAction(input({ key: 'Delete', selectionStart: 8, selectionEnd: 11 }))).toEqual({ kind: 'none' });
  });
});

describe('rowKeyAction: other keys', () => {
  it('returns none', () => {
    expect(rowKeyAction(input({ key: 'a' }))).toEqual({ kind: 'none' });
  });
});
