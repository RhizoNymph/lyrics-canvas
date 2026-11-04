/** A 0..1 score as a small bar plus its value (rhyme scores, related-word similarity). */
export default function ScoreBar({ value, label }: { value: number; label: string }) {
  const pct = Math.round(Math.min(1, Math.max(0, value)) * 100);
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }} title={`${label} ${value.toFixed(2)}`}>
      <span
        role="meter"
        aria-label={label}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={pct}
        style={{ width: 48, height: 6, background: '#2a2a4a', borderRadius: 3, overflow: 'hidden', display: 'inline-block' }}
      >
        <span style={{ display: 'block', width: `${pct}%`, height: '100%', background: '#6366f1' }} />
      </span>
      <span style={{ fontFamily: 'monospace', fontSize: 11, color: '#c0c0d8' }}>{value.toFixed(2)}</span>
    </span>
  );
}
