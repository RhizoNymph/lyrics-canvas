import { MAX_RELATED_TRIES, describeWordBankError } from '@/lib/wordBank';
import type { RelatedState } from '@/stores/wordBankQueryStore';
import { errorTextStyle, fieldStyle, mutedTextStyle, smallBtnStyle } from '@/components/tags/tagStyles';
import ScoreBar from './ScoreBar';
import { rowStyle } from './styles';

interface Props {
  query: string;
  state: RelatedState;
  onQuery: (q: string) => void;
  onSearch: () => void;
  onAdd: (text: string) => void;
}

export const WARMING_MESSAGE = 'Building related-word index…';
export const UNAVAILABLE_MESSAGE = 'Related words are unavailable on this server';

/** "Related words": search by meaning; "+" adds a result with the add box's tags, "in bank" otherwise. */
export default function RelatedView({ query, state, onQuery, onSearch, onAdd }: Props) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      <div style={{ display: 'flex', gap: 6 }}>
        <input
          aria-label="Related words"
          placeholder="Related words: a word, phrase or theme"
          value={query}
          onChange={(e) => onQuery(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault();
              onSearch();
            }
          }}
          style={fieldStyle}
        />
        <button type="button" onClick={onSearch} disabled={query.trim() === ''} style={smallBtnStyle}>
          Search
        </button>
      </div>
      <RelatedStatus state={state} />
      {state.kind === 'ready' && (
        <div role="list" aria-label="Related words">
          {state.value.results.length === 0 && <div style={mutedTextStyle}>No related words.</div>}
          {state.value.results.map((r) => (
            <div key={r.text} role="listitem" style={{ ...rowStyle(false), cursor: 'default' }}>
              <span style={{ fontSize: 13, flex: 1 }}>{r.text}</span>
              <ScoreBar value={r.similarity} label="similarity" />
              {r.bank_word_id !== null ? (
                <span style={{ ...mutedTextStyle, width: 44, textAlign: 'center' }}>in bank</span>
              ) : (
                <button type="button" aria-label={`Add ${r.text} to the word bank`} onClick={() => onAdd(r.text)} style={{ ...smallBtnStyle, width: 44 }}>
                  +
                </button>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function RelatedStatus({ state }: { state: RelatedState }) {
  switch (state.kind) {
    case 'idle':
    case 'ready':
      return null;
    case 'invalid':
      return <div style={errorTextStyle}>At most 64 characters</div>;
    case 'loading':
      return <div style={mutedTextStyle}>Searching…</div>;
    case 'warming':
      return (
        <div style={mutedTextStyle} role="status">
          {WARMING_MESSAGE} Retrying in {state.retryAfterSeconds} s ({state.attempt + 1} of {MAX_RELATED_TRIES}).
        </div>
      );
    case 'failed':
      if (state.error.kind === 'related_words_unavailable') return <div style={errorTextStyle}>{UNAVAILABLE_MESSAGE}</div>;
      if (state.error.kind === 'related_words_warming') {
        return <div style={errorTextStyle}>{WARMING_MESSAGE} Still not ready; try again later.</div>;
      }
      return <div style={errorTextStyle}>{describeWordBankError(state.error)}</div>;
  }
}
