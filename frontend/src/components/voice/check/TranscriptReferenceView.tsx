import type { TranscriptReference } from '@/lib/voice/recordingView';

export interface TranscriptReferenceViewProps {
  reference: TranscriptReference;
  /** Index into `reference.words` being played, or null. */
  activeWord: number | null;
  /** A word was tapped (timed references only). */
  onWord: (index: number) => void;
}

/**
 * The read-only Whisper reference, one row per line. With timings every word is a button that seeks
 * the player to its start, and the word being played is highlighted; without timings the lines are
 * plain text.
 */
export default function TranscriptReferenceView({ reference, activeWord, onWord }: TranscriptReferenceViewProps) {
  const box = {
    margin: 0,
    padding: '8px 10px',
    background: '#16162e',
    border: '1px solid #2a2a4a',
    borderRadius: 6,
    maxHeight: 180,
    overflowY: 'auto' as const,
    fontSize: 14,
    lineHeight: 1.7,
    listStyle: 'none',
  };
  if (reference.kind === 'untimed') {
    return (
      <ol aria-label="Whisper transcript" data-reference="untimed" style={box}>
        {reference.lines.map((line, i) => (
          <li key={i} style={{ minHeight: '1.7em', color: '#c0c0d8' }}>
            {line}
          </li>
        ))}
      </ol>
    );
  }
  return (
    <ol aria-label="Whisper transcript" data-reference="timed" style={box}>
      {reference.lines.map((line, i) => (
        <li key={i} style={{ minHeight: '1.7em', display: 'flex', flexWrap: 'wrap', columnGap: 4 }}>
          {line.words.map((index) => {
            const word = reference.words[index];
            const active = index === activeWord;
            return (
              <button
                key={index}
                type="button"
                data-word={index}
                aria-current={active ? 'true' : undefined}
                onClick={() => onWord(index)}
                title={word.probability === null ? undefined : `${Math.round(word.probability * 100)}% confident`}
                style={{
                  background: active ? '#6366f1' : 'transparent',
                  color: active ? '#fff' : word.probability !== null && word.probability < 0.5 ? '#fbbf24' : '#c0c0d8',
                  border: 'none',
                  borderRadius: 3,
                  padding: '0 2px',
                  font: 'inherit',
                  cursor: 'pointer',
                }}
              >
                {word.text.trim()}
              </button>
            );
          })}
        </li>
      ))}
    </ol>
  );
}
