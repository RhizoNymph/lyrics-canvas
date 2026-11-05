import { mutedTextStyle } from '@/components/tags/tagStyles';

interface Props {
  label: string;
  value: number;
  onChange: (value: number) => void;
}

/** Min score control, 0..1 in steps of 0.05. */
export default function MinScoreSlider({ label, value, onChange }: Props) {
  return (
    <label style={{ ...mutedTextStyle, display: 'flex', alignItems: 'center', gap: 6 }}>
      {label}
      <input
        type="range"
        min={0}
        max={1}
        step={0.05}
        value={value}
        onChange={(e) => onChange(Number(e.target.value))}
        aria-label={label}
        style={{ flex: 1 }}
      />
      <span style={{ fontFamily: 'monospace', color: '#c0c0d8', width: 32 }}>{value.toFixed(2)}</span>
    </label>
  );
}
