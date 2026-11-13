import { useState } from 'react';
import { useCanvasStore } from '@/stores/canvasStore';
import { useRhymeStore } from '@/stores/rhymeStore';
import { selectedSnippets } from '@/lib/rhyme/queryFromSelection';
import { lastNonEmptyLineIndex } from '@/lib/rhyme/slotFit';
import { NO_RANGE, clickWord, rangeText } from '@/lib/rhyme/anchorRange';
import type { Snippet } from '@/types';
import type { AnchorRange } from '@/types/rhyme';
import SyllableStrip from './SyllableStrip';
import { displayText, hasWords } from '@/lib/grid/restMarks';
import { mutedStyle, monoStyle } from './sidebarStyles';
import { NO_LINE_PICK, checkPatternRange, clickLineNumber, describeRangeProblem } from '@/lib/rhyme/pattern';
import type { LinePick } from '@/lib/rhyme/pattern';
import { useRhymePatternStore } from '@/stores/rhymePatternStore';

const CHIP_TEXT_MAX = 28;

function truncate(text: string): string {
  return text.length > CHIP_TEXT_MAX ? `${text.slice(0, CHIP_TEXT_MAX - 1)}…` : text;
}

/** The chip label: the picked word range, else the last non-empty line. */
function chipLabel(s: Snippet, range: AnchorRange | undefined): string {
  if (range !== undefined) {
    const picked = rangeText(s, range);
    if (picked !== null && picked.length > 0) return truncate(picked);
  }
  const idx = lastNonEmptyLineIndex(s);
  const text = displayText(idx === null ? s.raw_text : s.grid_data?.lines[idx]?.raw_text ?? s.raw_text).trim();
  return truncate(text);
}

const iconBtnStyle = { background: 'none', border: 'none', color: '#aaa', cursor: 'pointer', padding: 0, fontSize: 12, lineHeight: 1 } as const;

/**
 * The selected snippets acting as anchors, each with its echoed rhyme key. The × removes a
 * snippet from the anchors for this query; a removed chip stays visible, dimmed, until it is
 * restored or deselected. ▸ expands the chip to the snippet's lines as syllable strips: click a
 * start word and an end word on one line to anchor on that word range only (the canvas is not
 * touched); click the start word again, or "whole line", to go back to the whole last line. Clicking two
 * line numbers picks a line range: 2+ lines with words switch the sidebar to Pattern mode for this snippet.
 */
export default function AnchorChips() {
  const selectedIds = useCanvasStore((s) => s.selectedSnippetIds);
  const snippets = useCanvasStore((s) => s.snippets);
  const anchors = useRhymeStore((s) => s.anchors);
  const excluded = useRhymeStore((s) => s.excludedAnchorIds);
  const ranges = useRhymeStore((s) => s.anchorRanges);
  const excludeAnchor = useRhymeStore((s) => s.excludeAnchor);
  const restoreAnchor = useRhymeStore((s) => s.restoreAnchor);
  const setAnchorRange = useRhymeStore((s) => s.setAnchorRange);
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set());
  /** Chips whose next click closes a range (first word already picked). */
  const [awaitingEnd, setAwaitingEnd] = useState<Set<string>>(() => new Set());
  /** Line-number clicks toward a pattern range, on one chip at a time. */
  const [linePick, setLinePick] = useState<{ snippetId: string; pick: LinePick; problem: string | null } | null>(null);
  const enterPattern = useRhymePatternStore((s) => s.enter);

  const selected = selectedSnippets(selectedIds, snippets).filter((s) => hasWords(s.raw_text));
  if (selected.length === 0) {
    return <div style={mutedStyle}>No snippet selected. Select one on the canvas or type a phrase.</div>;
  }

  const toggleExpanded = (id: string) =>
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  const setAwaiting = (id: string, on: boolean) =>
    setAwaitingEnd((prev) => {
      if (prev.has(id) === on) return prev;
      const next = new Set(prev);
      if (on) next.add(id);
      else next.delete(id);
      return next;
    });

  const onWordClick = (id: string, lineIndex: number, wordIndex: number) => {
    const current = { range: ranges.get(id) ?? null, awaitingEnd: awaitingEnd.has(id) };
    const next = clickWord(current, lineIndex, wordIndex);
    setAwaiting(id, next.awaitingEnd);
    setAnchorRange(id, next.range);
  };

  const onLineNumberClick = (s: Snippet, lineIndex: number) => {
    const pick = linePick !== null && linePick.snippetId === s.id ? linePick.pick : NO_LINE_PICK;
    const next = clickLineNumber(pick, lineIndex);
    if (next.range === null) {
      setLinePick(next.pick.kind === 'none' ? null : { snippetId: s.id, pick: next.pick, problem: null });
      return;
    }
    const check = checkPatternRange(s.grid_data?.lines ?? [], next.range);
    if (!check.ok) {
      setLinePick({ snippetId: s.id, pick: NO_LINE_PICK, problem: describeRangeProblem(check.reason) });
      return;
    }
    setLinePick(null);
    enterPattern(s.id, check.range);
  };

  const resetRange = (id: string) => {
    setAwaiting(id, false);
    setAnchorRange(id, null);
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
      {selected.map((s) => {
        const off = excluded.has(s.id);
        const range = ranges.get(s.id);
        const open = expanded.has(s.id);
        const key = anchors.find((a) => a.snippet_id === s.id)?.rhyme_key.join(' ');
        const lines = s.grid_data?.lines ?? [];
        return (
          <div key={s.id} style={{ display: 'flex', flexDirection: 'column', gap: 4, maxWidth: '100%' }}>
            <span
              title={displayText(s.raw_text)}
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 6,
                alignSelf: 'flex-start',
                background: off ? 'transparent' : '#2a2a4a',
                border: `1px solid ${off ? '#3a3a5a' : '#0ea5e9'}`,
                borderRadius: 12,
                padding: '2px 8px',
                fontSize: 11,
                opacity: off ? 0.5 : 1,
                maxWidth: '100%',
              }}
            >
              <button onClick={() => toggleExpanded(s.id)} title={open ? 'Hide lines' : 'Pick part of a line'} style={iconBtnStyle}>
                {open ? '▾' : '▸'}
              </button>
              <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{chipLabel(s, range)}</span>
              {range !== undefined && <span style={{ ...mutedStyle, fontSize: 10 }}>part</span>}
              {key && !off && <span style={{ ...monoStyle, color: '#8a8aaa' }}>{key}</span>}
              <button
                onClick={() => (off ? restoreAnchor(s.id) : excludeAnchor(s.id))}
                title={off ? 'Use as anchor again' : 'Remove from this query'}
                style={iconBtnStyle}
              >
                {off ? '+' : '×'}
              </button>
            </span>
            {open && (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 4, paddingLeft: 8, borderLeft: '2px solid #2a2a4a' }}>
                {lines.length === 0 && <span style={mutedStyle}>No beat grid for this snippet.</span>}
                {lines.map((line, i) => (
                  <div key={i} style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
                    <button
                      type="button"
                      onClick={() => onLineNumberClick(s, i)}
                      title="Click two line numbers to find rhymes across those lines (pattern)"
                      style={{
                        ...iconBtnStyle,
                        ...monoStyle,
                        flex: '0 0 16px',
                        fontSize: 11,
                        color: linePick?.snippetId === s.id && linePick.pick.kind === 'first' && linePick.pick.line === i ? '#0ea5e9' : '#8a8aaa',
                        textDecoration: 'underline dotted',
                      }}
                    >
                      {i + 1}
                    </button>
                    <SyllableStrip
                      line={line}
                      selected={range !== undefined && range.lineIndex === i ? range : null}
                      onWordClick={(w) => onWordClick(s.id, i, w)}
                    />
                  </div>
                ))}
                {linePick?.snippetId === s.id && (
                  <span style={mutedStyle}>
                    {linePick.problem ?? (linePick.pick.kind === 'first' ? `Now click the last line number (from line ${linePick.pick.line + 1}).` : '')}
                  </span>
                )}
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <span style={mutedStyle}>
                    {range === undefined
                      ? 'Click a start word, then an end word on the same line; or two line numbers for a pattern across lines.'
                      : awaitingEnd.has(s.id)
                        ? 'Now click the end word (or the start word again to clear).'
                        : `Anchored on "${rangeText(s, range) ?? ''}".`}
                  </span>
                  {range !== undefined && (
                    <button onClick={() => resetRange(s.id)} title="Anchor on the whole last line again" style={{ ...iconBtnStyle, textDecoration: 'underline' }}>
                      whole line
                    </button>
                  )}
                </div>
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}
