import { useRhymeStore } from '@/stores/rhymeStore';
import { useTagFilterStore } from '@/stores/tagFilterStore';
import { useTagStore } from '@/stores/tagStore';
import TagExpressionInput from '@/components/tags/TagExpressionInput';
import PresetPicker from '@/components/tags/PresetPicker';
import { smallBtnStyle } from '@/components/tags/tagStyles';
import { labelStyle, mutedStyle } from './sidebarStyles';

/**
 * "Only my lines matching": restricts the notes source to snippets matching a tag expression (sent as
 * `tag_filter`; the dictionary source is never filtered). Invalid text blocks the query locally.
 */
export default function NotesTagFilter() {
  const text = useRhymeStore((s) => s.tagFilterText);
  const setText = useRhymeStore((s) => s.setTagFilterText);
  const notesOn = useRhymeStore((s) => s.sources.notes);
  const canvasFilter = useTagFilterStore((s) => s.filter.appliedText);
  const tags = useTagStore((s) => s.tags);

  return (
    <div>
      <label style={labelStyle}>Only my lines matching</label>
      <TagExpressionInput value={text} onChange={setText} tags={tags} ariaLabel="Only my lines matching" placeholder="any tag (empty = all my lines)" />
      <div style={{ display: 'flex', gap: 6, marginTop: 4, alignItems: 'center' }}>
        <PresetPicker currentText={text} onApply={setText} align="left" />
        <button
          type="button"
          onClick={() => setText(canvasFilter)}
          disabled={canvasFilter.trim() === '' || canvasFilter === text}
          style={{ ...smallBtnStyle, opacity: canvasFilter.trim() === '' ? 0.5 : 1 }}
          title={canvasFilter.trim() === '' ? 'The canvas has no filter' : `Use: ${canvasFilter}`}
        >
          Use canvas filter
        </button>
        {text !== '' && (
          <button type="button" onClick={() => setText('')} style={smallBtnStyle} title="Search all my lines">
            Clear
          </button>
        )}
      </div>
      {!notesOn && text.trim() !== '' && (
        <div style={{ ...mutedStyle, marginTop: 2 }}>Applies to the notes source, which is off.</div>
      )}
    </div>
  );
}
