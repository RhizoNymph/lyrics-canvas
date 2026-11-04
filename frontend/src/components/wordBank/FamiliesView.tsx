import { describeWordBankError } from '@/lib/wordBank';
import type { BankFamiliesResponse } from '@/types/wordBank';
import type { Remote } from '@/stores/wordBankQueryStore';
import { errorTextStyle, mutedTextStyle, smallBtnStyle } from '@/components/tags/tagStyles';
import MinScoreSlider from './MinScoreSlider';
import { chipStyle } from './styles';

interface Props {
  state: Remote<BankFamiliesResponse>;
  minScore: number;
  selected: ReadonlySet<string>;
  onMinScore: (score: number) => void;
  onToggleWord: (id: string) => void;
  onSelectAll: (ids: readonly string[]) => void;
  onRetry: () => void;
}

/** Rhyme families of the filtered bank (largest first), each with "Select all"; unmatched words last. */
export default function FamiliesView({ state, minScore, selected, onMinScore, onToggleWord, onSelectAll, onRetry }: Props) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      <MinScoreSlider label="Min score" value={minScore} onChange={onMinScore} />
      {state.kind === 'loading' && <div style={mutedTextStyle}>Grouping…</div>}
      {state.kind === 'failed' && (
        <div style={errorTextStyle}>
          {describeWordBankError(state.error)}{' '}
          <button type="button" onClick={onRetry} style={{ ...smallBtnStyle, padding: '0 6px' }}>Retry</button>
        </div>
      )}
      {state.kind === 'ready' && (
        <>
          {state.value.families.length === 0 && <div style={mutedTextStyle}>No families at this score. Lower the min score.</div>}
          {state.value.families.map((f) => (
            <section key={`${f.label}:${f.words[0]?.id ?? ''}`} aria-label={`Family ${f.label}`} style={{ borderLeft: '3px solid #6366f1', paddingLeft: 8 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 4 }}>
                <span style={{ fontWeight: 'bold', fontSize: 13 }}>{f.label}</span>
                <span style={{ ...mutedTextStyle, fontFamily: 'monospace' }}>{f.rhyme_key.join(' ')}</span>
                <span style={mutedTextStyle}>
                  {f.words.length} words · cohesion {f.cohesion.toFixed(2)}
                </span>
                <span style={{ flex: 1 }} />
                <button type="button" onClick={() => onSelectAll(f.words.map((w) => w.id))} style={smallBtnStyle}>
                  Select all
                </button>
              </div>
              <WordChips words={f.words} selected={selected} onToggle={onToggleWord} />
            </section>
          ))}
          {state.value.unmatched.length > 0 && (
            <section aria-label="Unmatched" style={{ paddingLeft: 11 }}>
              <div style={{ ...mutedTextStyle, marginBottom: 4 }}>Unmatched ({state.value.unmatched.length})</div>
              <WordChips words={state.value.unmatched} selected={selected} onToggle={onToggleWord} />
            </section>
          )}
        </>
      )}
    </div>
  );
}

export function WordChips({
  words,
  selected,
  onToggle,
}: {
  words: readonly { id: string; text: string }[];
  selected: ReadonlySet<string>;
  onToggle: (id: string) => void;
}) {
  return (
    <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }}>
      {words.map((w) => (
        <span
          key={w.id}
          role="checkbox"
          aria-checked={selected.has(w.id)}
          tabIndex={0}
          onClick={() => onToggle(w.id)}
          onKeyDown={(e) => {
            if (e.key === ' ' || e.key === 'Enter') {
              e.preventDefault();
              onToggle(w.id);
            }
          }}
          style={chipStyle(selected.has(w.id))}
        >
          {w.text}
        </span>
      ))}
    </div>
  );
}
