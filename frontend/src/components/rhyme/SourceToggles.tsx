import { useRhymeStore } from '@/stores/rhymeStore';

/** The notes / dictionary / rare-words checkboxes, shared by the normal and the pattern view. */
export default function SourceToggles() {
  const sources = useRhymeStore((s) => s.sources);
  const toggleSource = useRhymeStore((s) => s.toggleSource);
  const includeRare = useRhymeStore((s) => s.includeRare);
  const setIncludeRare = useRhymeStore((s) => s.setIncludeRare);
  return (
    <div style={{ display: 'flex', gap: 12, fontSize: 12, flexWrap: 'wrap' }}>
      <label style={{ cursor: 'pointer' }}>
        <input type="checkbox" checked={sources.notes} onChange={() => toggleSource('notes')} /> notes
      </label>
      <label style={{ cursor: 'pointer' }}>
        <input type="checkbox" checked={sources.dictionary} onChange={() => toggleSource('dictionary')} /> dictionary
      </label>
      <label style={{ cursor: 'pointer' }} title="Include words below Zipf frequency 3">
        <input type="checkbox" checked={includeRare} onChange={(e) => setIncludeRare(e.target.checked)} /> rare words
      </label>
    </div>
  );
}
