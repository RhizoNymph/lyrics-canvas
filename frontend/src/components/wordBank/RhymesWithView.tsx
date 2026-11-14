import { describeWordBankError } from '@/lib/wordBank';
import type { BankRhymesResponse } from '@/types/wordBank';
import type { Remote } from '@/stores/wordBankQueryStore';
import { errorTextStyle, fieldStyle, mutedTextStyle, smallBtnStyle } from '@/components/tags/tagStyles';
import MinScoreSlider from './MinScoreSlider';
import ScoreBar from './ScoreBar';
import { rowStyle } from './styles';
import { orderPresentation, withBandHeaders } from '@/lib/rhyme/order';
import type { RhymeOrder } from '@/lib/rhyme/order';
import RhymeOrderToggle from '@/components/rhyme/order/RhymeOrderToggle';
import { BandHeader, OrderNote, SimilarityBadge } from '@/components/rhyme/order/orderParts';

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
  /** The order the shown result was asked for (default "rhyme"); with the response's `order_applied` it picks headers or the "unavailable" note. */
  requestedOrder?: RhymeOrder;
}

/** Bank words ranked against one anchor (the one selected word, or typed text), with score bars. */
export default function RhymesWithView({ state, text, selectedWordText, selectedCount, minScore, selected, onText, onMinScore, onToggleWord, onSelectAll, requestedOrder = 'rhyme' }: Props) {
  const hasAnchor = text.trim() !== '' || selectedWordText !== null;
  const presentation = state.kind === 'ready' ? orderPresentation(requestedOrder, state.value.order_applied) : 'plain';
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
      <RhymeOrderToggle />
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
          <OrderNote presentation={presentation} />
          <div role="listbox" aria-label="Rhyme matches" aria-multiselectable>
            {withBandHeaders(state.value.matches, presentation).map((item) => {
              if (item.kind === 'header') return <BandHeader key={item.key} band={item.band} />;
              const m = item.row;
              return (
              <div
                key={m.word.id}
                role="option"
                aria-selected={selected.has(m.word.id)}
                onClick={() => onToggleWord(m.word.id)}
                style={rowStyle(selected.has(m.word.id))}
              >
                <span style={{ fontSize: 13, flex: 1 }}>{m.word.text}</span>
                <SimilarityBadge similarity={m.similarity} />
                <ScoreBar value={m.score} label="score" />
                <span style={{ ...mutedTextStyle, fontFamily: 'monospace' }}>d{m.match_depth}</span>
              </div>
              );
            })}
          </div>
        </>
      )}
    </div>
  );
}
