import { useCanvasStore } from '@/stores/canvasStore';
import { useRhymeStore } from '@/stores/rhymeStore';
import { selectedSnippets } from '@/lib/rhyme/queryFromSelection';
import { lastNonEmptyLineIndex } from '@/lib/rhyme/slotFit';
import type { Snippet } from '@/types';
import { mutedStyle, monoStyle } from './sidebarStyles';

const CHIP_TEXT_MAX = 28;

function chipLabel(s: Snippet): string {
  const idx = lastNonEmptyLineIndex(s);
  const text = (idx === null ? s.raw_text : s.grid_data?.lines[idx]?.raw_text ?? s.raw_text).trim();
  return text.length > CHIP_TEXT_MAX ? `${text.slice(0, CHIP_TEXT_MAX - 1)}…` : text;
}

/**
 * The selected snippets acting as anchors, each with its echoed rhyme key. The × removes a
 * snippet from the anchors for this query; a removed chip stays visible, dimmed, until it is
 * restored or deselected.
 */
export default function AnchorChips() {
  const selectedIds = useCanvasStore((s) => s.selectedSnippetIds);
  const snippets = useCanvasStore((s) => s.snippets);
  const anchors = useRhymeStore((s) => s.anchors);
  const excluded = useRhymeStore((s) => s.excludedAnchorIds);
  const excludeAnchor = useRhymeStore((s) => s.excludeAnchor);
  const restoreAnchor = useRhymeStore((s) => s.restoreAnchor);

  const selected = selectedSnippets(selectedIds, snippets).filter((s) => s.raw_text.trim().length > 0);
  if (selected.length === 0) {
    return <div style={mutedStyle}>No snippet selected. Select one on the canvas or type a phrase.</div>;
  }

  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
      {selected.map((s) => {
        const off = excluded.has(s.id);
        const key = anchors.find((a) => a.snippet_id === s.id)?.rhyme_key.join(' ');
        return (
          <span
            key={s.id}
            title={s.raw_text}
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 6,
              background: off ? 'transparent' : '#2a2a4a',
              border: `1px solid ${off ? '#3a3a5a' : '#0ea5e9'}`,
              borderRadius: 12,
              padding: '2px 8px',
              fontSize: 11,
              opacity: off ? 0.5 : 1,
              maxWidth: '100%',
            }}
          >
            <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{chipLabel(s)}</span>
            {key && !off && <span style={{ ...monoStyle, color: '#8a8aaa' }}>{key}</span>}
            <button
              onClick={() => (off ? restoreAnchor(s.id) : excludeAnchor(s.id))}
              title={off ? 'Use as anchor again' : 'Remove from this query'}
              style={{ background: 'none', border: 'none', color: '#aaa', cursor: 'pointer', padding: 0, fontSize: 12, lineHeight: 1 }}
            >
              {off ? '+' : '×'}
            </button>
          </span>
        );
      })}
    </div>
  );
}
