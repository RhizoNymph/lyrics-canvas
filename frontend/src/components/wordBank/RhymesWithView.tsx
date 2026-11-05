import { describeWordBankError } from '@/lib/wordBank';
import type { BankRhymesResponse } from '@/types/wordBank';
import type { Remote } from '@/stores/wordBankQueryStore';
import { errorTextStyle, fieldStyle, mutedTextStyle, smallBtnStyle } from '@/components/tags/tagStyles';
import MinScoreSlider from './MinScoreSlider';
import ScoreBar from './ScoreBar';
import { rowStyle } from './styles';

interface Props {
  state: Remote<BankRhymesResponse>;
  /** The typed anchor text. */
  text: string;
  /** The single selected word used when nothing is typed, or null. */
  selectedWordText: string | null;
  /** How many words are selected (the anchor needs exactly one, or typed text). */
  selectedCount: number;
  minScore: number;
  selected: ReadonlySet<string>;
  onText: (text: string) => void;
  onMinScore: (score: number) => void;
  onToggleWord: (id: string) => void;
  onSelectAll: (ids: readonly string[]) => void;
}

/** Bank words ranked against one anchor (the one selected word, or typed text), with score bars. */
export default function RhymesWithView({ state, text, selectedWordText, selectedCount, minScore, selected, onText, onMinScore, onToggleWord, onSelectAll }: Props) {
  const hasAnchor = text.trim() !== '' || selectedWordText !== null;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      <input
        aria-label="Rhymes with"
        value={text}
        placeholder={selectedWordText !== null ? `Rhymes with "${selectedWordText}" (or type)` : 'Type a word, or select exactly one'}
        onChange={(e) => onText(e.target.value)}
        style={fieldStyle}
      />
      <MinScoreSlider label="Min score" value={minScore} onChange={onMinScore} />
      {!hasAnchor && (
        <div style={mutedTextStyle}>
          {selectedCount > 1 ? `${selectedCount} words are selected; select exactly one or type a word.` : 'Select exactly one word or type one.'}
        </div>
      )}
      {hasAnchor && state.kind === 'loading' && <div style={mutedTextStyle}>Ranking…</div>}
      {hasAnchor && state.kind === 'failed' && <div style={errorTextStyle}>{describeWordBankError(state.error)}</div>}
      {hasAnchor && state.kind === 'ready' && (
        <>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={mutedTextStyle}>
              {state.value.matches.length} matches for <b>{state.value.anchor.text}</b>
              {state.value.anchor.rhyme_key.length > 0 && <span style={{ fontFamily: 'monospace' }}> ({state.value.anchor.rhyme_key.join(' ')})</span>}
            </span>
            <span style={{ flex: 1 }} />
            {state.value.matches.length > 0 && (
              <button type="button" onClick={() => onSelectAll(state.value.matches.map((m) => m.word.id))} style={smallBtnStyle}>
                Select all
              </button>
            )}
          </div>
          <div role="listbox" aria-label="Rhyme matches" aria-multiselectable>
            {state.value.matches.map((m) => (
              <div
                key={m.word.id}
                role="option"
                aria-selected={selected.has(m.word.id)}
                onClick={() => onToggleWord(m.word.id)}
                style={rowStyle(selected.has(m.word.id))}
              >
                <span style={{ fontSize: 13, flex: 1 }}>{m.word.text}</span>
                <ScoreBar value={m.score} label="score" />
                <span style={{ ...mutedTextStyle, fontFamily: 'monospace' }}>d{m.match_depth}</span>
              </div>
            ))}
          </div>
        </>
      )}
    </div>
  );
}
