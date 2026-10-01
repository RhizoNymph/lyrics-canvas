/** Shared inline styles for the rhyme sidebar pieces. */
import type { CSSProperties } from 'react';

export const SIDEBAR_WIDTH = 380;

export const inputStyle: CSSProperties = {
  background: '#2a2a4a',
  color: '#e0e0e0',
  border: '1px solid #3a3a5a',
  borderRadius: 4,
  padding: '5px 8px',
  fontSize: 12,
  width: '100%',
  boxSizing: 'border-box',
};

export const btnStyle: CSSProperties = {
  background: '#2a2a4a',
  color: '#ccc',
  border: '1px solid #3a3a5a',
  borderRadius: 4,
  padding: '4px 10px',
  cursor: 'pointer',
  fontSize: 12,
  whiteSpace: 'nowrap',
};

export const primaryBtnStyle: CSSProperties = {
  ...btnStyle,
  background: '#6366f1',
  color: '#fff',
  borderColor: '#6366f1',
};

export const labelStyle: CSSProperties = { color: '#8a8aaa', fontSize: 11, display: 'block', marginBottom: 2 };

export const mutedStyle: CSSProperties = { color: '#8a8aaa', fontSize: 11 };

export const errorStyle: CSSProperties = { color: '#f87171', fontSize: 11 };

export const monoStyle: CSSProperties = { fontFamily: 'monospace' };
