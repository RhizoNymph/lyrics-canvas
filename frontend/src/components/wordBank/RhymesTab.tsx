import { useEffect, useMemo, useRef, useState } from 'react';
import { resolveRhymeAnchor } from '@/lib/wordBank';
import { useWordBankStore } from '@/stores/wordBankStore';
import { useWordBankQueryStore } from '@/stores/wordBankQueryStore';
import { smallBtnStyle } from '@/components/tags/tagStyles';
import FamiliesView from './FamiliesView';
import RhymesWithView from './RhymesWithView';

export type RhymeView = 'families' | 'rhymesWith';

const DEBOUNCE_MS = 250;

/** Debounced effect re-run whenever `key` changes. */
function useDebounced(key: string, run: () => void): void {
  const runRef = useRef(run);
  runRef.current = run;
  useEffect(() => {
    const timer = setTimeout(() => runRef.current(), DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [key]);
}

/** The two rhyme views of the filtered bank. Results refetch when the bank, filter or score changes. */
export default function RhymesTab() {
  const [view, setView] = useState<RhymeView>('families');
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      <div role="tablist" aria-label="Rhyme view" style={{ display: 'flex', gap: 4 }}>
        {(['families', 'rhymesWith'] as const).map((v) => (
          <button
            key={v}
            type="button"
            role="tab"
            aria-selected={view === v}
            onClick={() => setView(v)}
            style={{ ...smallBtnStyle, ...(view === v ? { background: '#6366f1', color: '#fff', borderColor: '#6366f1' } : {}) }}
          >
            {v === 'families' ? 'Families' : 'Rhymes with'}
          </button>
        ))}
      </div>
      {view === 'families' ? <FamiliesPane /> : <RhymesWithPane />}
    </div>
  );
}

function FamiliesPane() {
  const revision = useWordBankStore((s) => s.revision);
  const appliedText = useWordBankStore((s) => s.filter.appliedText);
  const selected = useWordBankStore((s) => s.selection.ids);
  const select = useWordBankStore((s) => s.select);
  const state = useWordBankQueryStore((s) => s.families);
  const minScore = useWordBankQueryStore((s) => s.familyMinScore);
  const setMinScore = useWordBankQueryStore((s) => s.setFamilyMinScore);
  const load = useWordBankQueryStore((s) => s.loadFamilies);

  useDebounced(`${revision}|${appliedText}|${minScore}`, () => void load());

  return (
    <FamiliesView
      state={state}
      minScore={minScore}
      selected={selected}
      onMinScore={setMinScore}
      onToggleWord={(id) => select({ type: 'toggle', id })}
      onSelectAll={(ids) => select({ type: 'add', ids })}
      onRetry={() => void load()}
    />
  );
}

function RhymesWithPane() {
  const revision = useWordBankStore((s) => s.revision);
  const appliedText = useWordBankStore((s) => s.filter.appliedText);
  const words = useWordBankStore((s) => s.words);
  const selected = useWordBankStore((s) => s.selection.ids);
  const select = useWordBankStore((s) => s.select);
  const state = useWordBankQueryStore((s) => s.rhymes);
  const text = useWordBankQueryStore((s) => s.rhymeText);
  const setText = useWordBankQueryStore((s) => s.setRhymeText);
  const minScore = useWordBankQueryStore((s) => s.rhymeMinScore);
  const setMinScore = useWordBankQueryStore((s) => s.setRhymeMinScore);
  const load = useWordBankQueryStore((s) => s.loadRhymes);
  const pinned = useRef<string | null>(null);

  const anchor = useMemo(() => resolveRhymeAnchor(text, selected, pinned.current), [text, selected]);
  pinned.current = anchor?.kind === 'word' ? anchor.wordId : pinned.current;
  const anchorKey = anchor === null ? 'none' : anchor.kind === 'word' ? `w:${anchor.wordId}` : `t:${anchor.text}`;
  const anchorWord = anchor?.kind === 'word' ? words.find((w) => w.id === anchor.wordId) : undefined;

  useDebounced(`${anchorKey}|${revision}|${appliedText}|${minScore}`, () => void load(anchor));

  return (
    <RhymesWithView
      state={state}
      text={text}
      selectedWordText={anchorWord?.text ?? null}
      selectedCount={selected.size}
      minScore={minScore}
      selected={selected}
      onText={setText}
      onMinScore={setMinScore}
      onToggleWord={(id) => select({ type: 'toggle', id })}
      onSelectAll={(ids) => select({ type: 'add', ids })}
    />
  );
}
