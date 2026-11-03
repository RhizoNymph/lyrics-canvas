import { forwardRef, useId, useImperativeHandle, useLayoutEffect, useMemo, useRef, useState } from 'react';
import type { CSSProperties, KeyboardEvent as ReactKeyboardEvent } from 'react';
import {
  applySuggestion, completionContextAt, cp, cpToUtf16, insertTagAtCaret, suggestionsFor, u16, utf16ToCp,
} from '@/lib/tagExpr';
import type { CompletionTag, Suggestion, TextEdit } from '@/lib/tagExpr';
import { markerFor, menuKeyEffect } from '@/lib/tagFilter';
import { COLORS, errorTextStyle, fieldStyle, monoStyle, popoverStyle } from './tagStyles';

export interface TagExpressionInputHandle {
  /** Insert a tag at the caret (or where the caret last was), joined with ` & ` where needed. */
  insertTag: (name: string) => void;
  focus: () => void;
}

interface Props {
  value: string;
  onChange: (text: string) => void;
  tags: readonly CompletionTag[];
  ariaLabel: string;
  placeholder?: string;
  style?: CSSProperties;
}

const MENU_WIDTH = 200;

let measureCanvas: HTMLCanvasElement | null = null;

/** Horizontal pixel offset of `textBefore` inside the input, for placing the menu at the caret. */
function textOffsetPx(input: HTMLInputElement, textBefore: string): number {
  measureCanvas ??= document.createElement('canvas');
  const ctx = measureCanvas.getContext('2d');
  if (ctx === null) return 0;
  const cs = getComputedStyle(input);
  ctx.font = cs.font || `${cs.fontSize} ${cs.fontFamily}`;
  const x = parseFloat(cs.paddingLeft || '0') + ctx.measureText(textBefore).width - input.scrollLeft;
  return Math.max(0, Math.min(x, input.clientWidth - MENU_WIDTH));
}

/**
 * Single-line tag expression field: live validation (message plus a marker at the error's code point),
 * autocomplete at the caret (keyboard: arrows, Enter/Tab to accept, Esc to close, Ctrl+Space to open).
 * Logic lives in lib/tagExpr and lib/tagFilter; this component only maps DOM carets (UTF-16) to code points.
 */
const TagExpressionInput = forwardRef<TagExpressionInputHandle, Props>(function TagExpressionInput(
  { value, onChange, tags, ariaLabel, placeholder = 'e.g. love & !draft | type:chorus', style },
  ref,
) {
  const inputRef = useRef<HTMLInputElement>(null);
  const listId = useId();
  const [caret16, setCaret16] = useState(value.length);
  const [open, setOpen] = useState(false);
  const [highlight, setHighlight] = useState(0);
  const pendingCaret = useRef<number | null>(null);
  const lastCaret = useRef(value.length);

  const marker = useMemo(() => markerFor(value), [value]);
  const ctx = useMemo(() => completionContextAt(value, utf16ToCp(value, u16(Math.min(caret16, value.length)))), [value, caret16]);
  const suggestions = useMemo(() => (open ? suggestionsFor(ctx, tags) : []), [open, ctx, tags]);
  const menuOpen = open && suggestions.length > 0;

  // Put the caret where a programmatic edit (completion, pill) said it goes.
  useLayoutEffect(() => {
    const input = inputRef.current;
    if (input === null || pendingCaret.current === null) return;
    const at = pendingCaret.current;
    pendingCaret.current = null;
    input.setSelectionRange(at, at);
    setCaret16(at);
    lastCaret.current = at;
  }, [value]);

  const commit = (edit: TextEdit) => {
    pendingCaret.current = cpToUtf16(edit.text, edit.caret);
    onChange(edit.text);
  };

  const syncCaret = () => {
    const input = inputRef.current;
    if (input === null) return;
    const at = input.selectionStart ?? input.value.length;
    setCaret16(at);
    lastCaret.current = at;
  };

  const accept = (s: Suggestion) => {
    commit(applySuggestion(value, ctx, s));
    setOpen(false);
  };

  useImperativeHandle(ref, () => ({
    insertTag: (name) => {
      const input = inputRef.current;
      const at = input !== null && document.activeElement === input ? input.selectionStart ?? value.length : lastCaret.current;
      commit(insertTagAtCaret(value, utf16ToCp(value, u16(Math.min(at, value.length))), name));
      input?.focus();
    },
    focus: () => inputRef.current?.focus(),
  }));

  const onKeyDown = (e: ReactKeyboardEvent<HTMLInputElement>) => {
    if (e.key === ' ' && (e.ctrlKey || e.metaKey)) {
      e.preventDefault();
      setOpen(true);
      setHighlight(0);
      return;
    }
    if (!menuOpen) return;
    const effect = menuKeyEffect(e.key, highlight, suggestions.length);
    switch (effect.kind) {
      case 'move':
        e.preventDefault();
        setHighlight(effect.index);
        break;
      case 'accept':
        e.preventDefault();
        accept(suggestions[effect.index]);
        break;
      case 'close':
        e.preventDefault();
        e.stopPropagation();
        setOpen(false);
        break;
      case 'none':
        break;
    }
  };

  const jumpToError = () => {
    const input = inputRef.current;
    if (input === null || marker === null) return;
    const at = cpToUtf16(value, cp(marker.position));
    input.focus();
    input.setSelectionRange(at, at);
    syncCaret();
  };

  const menuLeft =
    menuOpen && inputRef.current !== null
      ? textOffsetPx(inputRef.current, value.slice(0, cpToUtf16(value, ctx.replaceStart)))
      : 0;
  const optionId = (i: number) => `${listId}-opt-${i}`;

  return (
    <div style={{ position: 'relative', minWidth: 0, ...style }}>
      <input
        ref={inputRef}
        value={value}
        role="combobox"
        aria-label={ariaLabel}
        aria-invalid={marker !== null}
        aria-expanded={menuOpen}
        aria-controls={listId}
        aria-autocomplete="list"
        aria-activedescendant={menuOpen ? optionId(highlight) : undefined}
        placeholder={placeholder}
        spellCheck={false}
        autoComplete="off"
        onChange={(e) => {
          onChange(e.target.value);
          setCaret16(e.target.selectionStart ?? e.target.value.length);
          lastCaret.current = e.target.selectionStart ?? e.target.value.length;
          setOpen(true);
          setHighlight(0);
        }}
        onSelect={syncCaret}
        onKeyDown={onKeyDown}
        onBlur={() => setOpen(false)}
        style={{
          ...fieldStyle,
          ...monoStyle,
          border: `1px solid ${marker !== null ? COLORS.danger : COLORS.fieldBorder}`,
        }}
      />
      {menuOpen && (
        <ul
          id={listId}
          role="listbox"
          aria-label="Suggestions"
          style={{ ...popoverStyle, left: menuLeft, top: '100%', marginTop: 2, width: MENU_WIDTH, listStyle: 'none', padding: '4px 0', maxHeight: 240, overflowY: 'auto' }}
        >
          {suggestions.map((s, i) => (
            <li
              key={`${s.kind}:${s.label}`}
              id={optionId(i)}
              role="option"
              aria-selected={i === highlight}
              // Keep focus (and the caret) in the input.
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => accept(s)}
              onMouseEnter={() => setHighlight(i)}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 6,
                padding: '3px 10px',
                cursor: 'pointer',
                background: i === highlight ? COLORS.hover : 'transparent',
              }}
            >
              {s.kind === 'tag' && <span style={{ width: 8, height: 8, borderRadius: '50%', background: s.color, flexShrink: 0 }} />}
              <span style={{ ...(s.kind === 'tag' ? {} : monoStyle), overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {s.label}
              </span>
              <span style={{ marginLeft: 'auto', color: COLORS.muted, fontSize: 10 }}>
                {s.kind === 'tag' ? 'tag' : s.kind === 'type' ? 'type' : 'operator'}
              </span>
            </li>
          ))}
        </ul>
      )}
      {marker !== null && (
        <div role="alert" style={{ ...errorTextStyle, marginTop: 2, display: 'flex', gap: 6, alignItems: 'baseline', flexWrap: 'wrap' }}>
          <span>{marker.error.message}</span>
          <button
            type="button"
            onClick={jumpToError}
            title="Move the caret to the error"
            style={{ ...monoStyle, background: 'none', border: 'none', padding: 0, color: '#c0c0d8', cursor: 'pointer', fontSize: 11, whiteSpace: 'pre' }}
          >
            {marker.before}
            <span style={{ color: COLORS.danger, textDecoration: 'underline wavy', textUnderlineOffset: 2, background: 'rgba(248,113,113,0.15)' }}>
              {marker.at === '' ? ' ' : marker.at}
            </span>
            {marker.after}
          </button>
        </div>
      )}
    </div>
  );
});

export default TagExpressionInput;
