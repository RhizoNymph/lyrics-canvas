import { useMemo, useRef } from 'react';
import { useCanvasStore } from '@/stores/canvasStore';
import { useTagFilterStore } from '@/stores/tagFilterStore';
import { useTagStore } from '@/stores/tagStore';
import { visibleSnippets } from '@/lib/canvas';
import { mentionsTag } from '@/lib/tagExpr';
import TagExpressionInput from '@/components/tags/TagExpressionInput';
import type { TagExpressionInputHandle } from '@/components/tags/TagExpressionInput';
import PresetPicker from '@/components/tags/PresetPicker';
import { COLORS, errorTextStyle, monoStyle, mutedTextStyle, pillStyle, smallBtnStyle } from '@/components/tags/tagStyles';

/**
 * The canvas page's tag filter (canvas and list view): an expression field with autocomplete, the
 * user's tags as pills that insert at the caret, presets, and clear. An invalid expression keeps the
 * last valid filter applied (state machine in lib/tagFilter/state.ts).
 */
export default function TagExpressionBar() {
  const filter = useTagFilterStore((s) => s.filter);
  const dispatch = useTagFilterStore((s) => s.dispatch);
  const tags = useTagStore((s) => s.tags);
  const loadState = useTagStore((s) => s.loadState);
  const load = useTagStore((s) => s.load);
  const snippets = useCanvasStore((s) => s.snippets);
  const inputRef = useRef<TagExpressionInputHandle>(null);

  const shown = useMemo(() => visibleSnippets(snippets, filter.applied).length, [snippets, filter.applied]);

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        gap: 4,
        padding: '4px 16px',
        borderBottom: '1px solid #2a2a4a',
        background: '#16162a',
        fontSize: 12,
        color: COLORS.text,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 6 }}>
        <span style={{ ...mutedTextStyle, lineHeight: '24px' }}>Filter</span>
        <TagExpressionInput
          ref={inputRef}
          value={filter.text}
          onChange={(text) => dispatch({ type: 'edit', text })}
          tags={tags}
          ariaLabel="Tag filter expression"
          style={{ flex: 1, maxWidth: 640 }}
        />
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
        <PresetPicker currentText={filter.text} onApply={(expression) => dispatch({ type: 'applyPreset', expression })} />
        <span style={{ ...mutedTextStyle, lineHeight: '24px', whiteSpace: 'nowrap' }} aria-live="polite">
          {filter.applied === null ? `${snippets.size} snippets` : `${shown} of ${snippets.size} shown`}
        </span>
      </div>
      {filter.error !== null && (
        <div style={{ ...errorTextStyle, paddingLeft: 38 }}>
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
      <div style={{ display: 'flex', alignItems: 'center', gap: 4, overflowX: 'auto', paddingBottom: 2 }} aria-label="Insert a tag">
        {loadState === 'failed' && (
          <span style={errorTextStyle}>
            Tags failed to load.{' '}
            <button type="button" onClick={() => void load()} style={{ ...smallBtnStyle, padding: '0 6px' }}>Retry</button>
          </span>
        )}
        {loadState === 'ready' && tags.length === 0 && (
          <span style={mutedTextStyle}>No tags yet. Right-click a snippet to tag it.</span>
        )}
        {tags.map((tag) => (
          <button
            type="button"
            key={tag.id}
            // Keep the caret in the field so the tag lands where it was.
            onMouseDown={(e) => e.preventDefault()}
            onClick={() => inputRef.current?.insertTag(tag.name)}
            title={`Insert "${tag.name}" at the caret (${tag.snippet_count} snippets)`}
            style={pillStyle(tag.color, mentionsTag(filter.applied, tag.name))}
          >
            {tag.name}
          </button>
        ))}
      </div>
    </div>
  );
}
