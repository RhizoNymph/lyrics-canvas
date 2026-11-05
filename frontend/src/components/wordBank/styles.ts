/** Shared inline styles for the word bank panel (same dark theme as the rhyme sidebar and tag picker). */
import type { CSSProperties } from 'react';
import { COLORS } from '@/components/tags/tagStyles';

export const WORD_BANK_WIDTH = 380;

export const sectionStyle: CSSProperties = { padding: '8px 12px', borderBottom: '1px solid #2a2a4a' };

export function tabStyle(active: boolean): CSSProperties {
  return {
    flex: 1,
    background: active ? '#2a2a4a' : 'transparent',
    color: active ? '#fff' : '#9a9aba',
    border: 'none',
    borderBottom: active ? `2px solid ${COLORS.accent}` : '2px solid transparent',
    padding: '6px 0',
    cursor: 'pointer',
    fontSize: 12,
  };
}

export function rowStyle(selected: boolean): CSSProperties {
  return {
    display: 'flex',
    alignItems: 'center',
    gap: 6,
    padding: '4px 8px',
    borderRadius: 4,
    cursor: 'pointer',
    userSelect: 'none',
    background: selected ? 'rgba(99, 102, 241, 0.22)' : 'transparent',
  };
}

export function chipStyle(selected: boolean): CSSProperties {
  return {
    display: 'inline-flex',
    alignItems: 'center',
    padding: '1px 8px',
    borderRadius: 10,
    fontSize: 12,
    cursor: 'pointer',
    border: `1px solid ${selected ? COLORS.accent : '#3a3a5a'}`,
    background: selected ? 'rgba(99, 102, 241, 0.3)' : '#22223f',
    color: '#e0e0f0',
    userSelect: 'none',
  };
}

export const tagDotPillStyle = (color: string): CSSProperties => ({
  display: 'inline-flex',
  alignItems: 'center',
  padding: '0 6px',
  borderRadius: 8,
  fontSize: 10,
  color: '#ddd',
  border: `1px solid ${color}`,
  whiteSpace: 'nowrap',
});
