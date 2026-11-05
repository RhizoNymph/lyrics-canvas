import { useRef } from 'react';
import type { CSSProperties } from 'react';
import { useTagStore } from '@/stores/tagStore';
import { mentionsTag } from '@/lib/tagExpr';
import type { FilterAction, FilterState } from '@/lib/tagFilter';
import TagExpressionInput from './TagExpressionInput';
import type { TagExpressionInputHandle } from './TagExpressionInput';
import PresetPicker from './PresetPicker';
import { COLORS, errorTextStyle, monoStyle, mutedTextStyle, pillStyle, smallBtnStyle } from './tagStyles';

interface Props {
  filter: FilterState;
  dispatch: (action: FilterAction) => void;
  /** The count line, e.g. "3 of 12 shown" or "12 words (3 shown)". */
  countLabel: string;
  ariaLabel: string;
  /** Shown when the user has no tags yet. */
  noTagsHint: string;
  /** Sidebar layout: the field takes its own row and the pills wrap. */
  compact?: boolean;
  /** Hover text of a tag pill (e.g. with its snippet or word count). */
  pillTitle?: (tag: { name: string; snippet_count: number; word_count: number }) => string;
  style?: CSSProperties;
}

/**
 * A tag expression filter: the field with live validation and autocomplete, the user's tags as pills
 * that insert at the caret, presets, clear, and a count. Shared by the canvas filter bar and the word
 * bank; the caller owns the state (lib/tagFilter/state.ts), so an invalid text keeps the last valid
 * filter applied.
 */
export default function TagFilterBar({ filter, dispatch, countLabel, ariaLabel, noTagsHint, compact = false, pillTitle, style }: Props) {
  const tags = useTagStore((s) => s.tags);
  const loadState = useTagStore((s) => s.loadState);
  const load = useTagStore((s) => s.load);
  const inputRef = useRef<TagExpressionInputHandle>(null);

  const controls = (
    <>
      <button
        type="button"
        onClick={() => dispatch({ type: 'clear' })}
        disabled={filter.text === '' && filter.appliedText === ''}
        style={{ ...smallBtnStyle, lineHeight: '16px' }}
        title="Clear the filter"
        aria-label="Clear the filter"
      >
        ✕
      </button>
      <PresetPicker currentText={filter.text} onApply={(expression) => dispatch({ type: 'applyPreset', expression })} align={compact ? 'left' : 'right'} />
      <span style={{ ...mutedTextStyle, lineHeight: '24px', whiteSpace: 'nowrap' }} aria-live="polite">
        {countLabel}
      </span>
    </>
  );

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12, color: COLORS.text, ...style }}>
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 6, flexWrap: compact ? 'wrap' : 'nowrap' }}>
        <span style={{ ...mutedTextStyle, lineHeight: '24px' }}>Filter</span>
        <TagExpressionInput
          ref={inputRef}
          value={filter.text}
          onChange={(text) => dispatch({ type: 'edit', text })}
          tags={tags}
          ariaLabel={ariaLabel}
          style={{ flex: 1, maxWidth: compact ? undefined : 640, minWidth: compact ? 0 : undefined }}
        />
        {!compact && controls}
      </div>
      {compact && <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>{controls}</div>}
      {filter.error !== null && (
        <div style={{ ...errorTextStyle, paddingLeft: compact ? 0 : 38 }}>
          Not applied.{' '}
          {filter.appliedText.trim() === '' ? (
            'No filter is active.'
          ) : (
            <>
              Still filtering by <span style={monoStyle}>{filter.appliedText}</span>
            </>
          )}
        </div>
      )}
      <div
        style={{ display: 'flex', alignItems: 'center', gap: 4, paddingBottom: 2, ...(compact ? { flexWrap: 'wrap' } : { overflowX: 'auto' }) }}
        aria-label="Insert a tag"
      >
        {loadState === 'failed' && (
          <span style={errorTextStyle}>
            Tags failed to load.{' '}
            <button type="button" onClick={() => void load()} style={{ ...smallBtnStyle, padding: '0 6px' }}>Retry</button>
          </span>
        )}
        {loadState === 'ready' && tags.length === 0 && <span style={mutedTextStyle}>{noTagsHint}</span>}
        {tags.map((tag) => (
          <button
            type="button"
            key={tag.id}
            // Keep the caret in the field so the tag lands where it was.
            onMouseDown={(e) => e.preventDefault()}
            onClick={() => inputRef.current?.insertTag(tag.name)}
            title={pillTitle ? pillTitle(tag) : `Insert "${tag.name}" at the caret`}
            style={pillStyle(tag.color, mentionsTag(filter.applied, tag.name))}
          >
            {tag.name}
          </button>
        ))}
      </div>
    </div>
  );
}
