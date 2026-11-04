/** Shared inline styles for the tag picker, expression input and preset picker (dark theme like the toolbar). */
import type { CSSProperties } from 'react';

export const COLORS = {
  panel: '#1e1e3a',
  border: '#3a3a5c',
  field: '#2a2a4a',
  fieldBorder: '#3a3a5a',
  text: '#d0d0e8',
  muted: '#8a8aaa',
  accent: '#6366f1',
  hover: 'rgba(99, 102, 241, 0.15)',
  danger: '#f87171',
} as const;

export const fieldStyle: CSSProperties = {
  background: COLORS.field,
  color: '#e0e0e0',
  border: `1px solid ${COLORS.fieldBorder}`,
  borderRadius: 4,
  padding: '4px 8px',
  fontSize: 12,
  width: '100%',
  boxSizing: 'border-box',
  outline: 'none',
};

export const smallBtnStyle: CSSProperties = {
  background: COLORS.field,
  color: '#ccc',
  border: `1px solid ${COLORS.fieldBorder}`,
  borderRadius: 4,
  padding: '3px 8px',
  cursor: 'pointer',
  fontSize: 11,
  whiteSpace: 'nowrap',
};

export const popoverStyle: CSSProperties = {
  position: 'absolute',
  zIndex: 2100,
  background: COLORS.panel,
  border: `1px solid ${COLORS.border}`,
  borderRadius: 6,
  boxShadow: '0 8px 24px rgba(0,0,0,0.5)',
  color: COLORS.text,
  fontSize: 12,
};

export const errorTextStyle: CSSProperties = { color: COLORS.danger, fontSize: 11 };
export const mutedTextStyle: CSSProperties = { color: COLORS.muted, fontSize: 11 };
export const monoStyle: CSSProperties = { fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace' };

export function pillStyle(color: string, active = false): CSSProperties {
  return {
    display: 'inline-flex',
    alignItems: 'center',
    gap: 4,
    padding: '1px 9px',
    borderRadius: 12,
    fontSize: 11,
    cursor: 'pointer',
    background: active ? color : COLORS.field,
    color: active ? '#fff' : '#bbb',
    border: `1px solid ${color}`,
    whiteSpace: 'nowrap',
    userSelect: 'none',
  };
}
