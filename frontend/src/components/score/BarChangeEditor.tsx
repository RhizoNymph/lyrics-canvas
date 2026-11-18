/** Meter and tempo change at one bar (bar numbers shown 1-based; bar 0 holds the first meter and tempo). */
import { useEffect, useState } from 'react';
import type { GridData } from '@/types';
import type { Score } from '@/types/score';
import { DENS, meterAtBar, setMeterAt, setTempoAt, tempoAtBar } from '@/lib/score';
import type { EditResult } from '@/lib/score';

interface Props {
  score: Score;
  grid: GridData | null;
  bar: number;
  scale: number;
  onBar: (bar: number) => void;
  onResult: (result: EditResult) => boolean;
  onClose: () => void;
}

export default function BarChangeEditor({ score, grid, bar, scale: s, onBar, onResult, onClose }: Props) {
  const meter = meterAtBar(score, bar);
  const tempo = tempoAtBar(score, bar);
  const [num, setNum] = useState(String(meter.num));
  const [den, setDen] = useState(String(meter.den));
  const [bpm, setBpm] = useState(String(tempo.bpm));
  useEffect(() => {
    setNum(String(meter.num));
    setDen(String(meter.den));
    setBpm(String(tempo.bpm));
  }, [bar, meter.num, meter.den, tempo.bpm]);

  const hasMeterChange = score.meters.some((m) => m.bar === bar);
  const hasTempoChange = score.tempos.some((t) => t.bar === bar);
  const input = {
    width: 42 * s,
    background: 'rgba(0,0,0,0.3)',
    color: '#fff',
    border: '1px solid rgba(255,255,255,0.25)',
    borderRadius: 3,
    fontSize: 11 * s,
    padding: `${1 * s}px ${3 * s}px`,
  } as const;
  const button = { ...input, width: 'auto', cursor: 'pointer' } as const;

  return (
    <div
      style={{
        display: 'flex',
        flexWrap: 'wrap',
        alignItems: 'center',
        gap: 6 * s,
        padding: `${4 * s}px ${6 * s}px`,
        marginBottom: 6 * s,
        background: 'rgba(99, 102, 241, 0.1)',
        border: '1px solid rgba(99, 102, 241, 0.3)',
        borderRadius: 4,
        fontSize: 11 * s,
        color: '#c0c0e0',
      }}
    >
      <label>
        Bar{' '}
        <input
          type="number"
          min={1}
          max={2049}
          value={bar + 1}
          style={input}
          onChange={(e) => {
            const value = Number(e.target.value);
            if (Number.isInteger(value) && value >= 1) onBar(value - 1);
          }}
        />
      </label>
      <span>
        Meter <input type="number" min={1} max={32} value={num} style={input} onChange={(e) => setNum(e.target.value)} /> /{' '}
        <select value={den} style={button} onChange={(e) => setDen(e.target.value)}>
          {DENS.map((d) => (
            <option key={d} value={d}>
              {d}
            </option>
          ))}
        </select>
      </span>
      <button type="button" style={button} onClick={() => onResult(setMeterAt(score, grid, bar, { num: Number(num), den: Number(den) }))}>
        Set meter
      </button>
      {hasMeterChange && bar > 0 && (
        <button type="button" style={button} onClick={() => onResult(setMeterAt(score, grid, bar, null))}>
          Remove meter change
        </button>
      )}
      <span>
        Tempo <input type="number" min={20} max={400} step={0.01} value={bpm} style={{ ...input, width: 56 * s }} onChange={(e) => setBpm(e.target.value)} /> bpm
      </span>
      <button type="button" style={button} onClick={() => onResult(setTempoAt(score, grid, bar, Number(bpm)))}>
        Set tempo
      </button>
      {hasTempoChange && bar > 0 && (
        <button type="button" style={button} onClick={() => onResult(setTempoAt(score, grid, bar, null))}>
          Remove tempo change
        </button>
      )}
      <span style={{ flex: 1 }} />
      <button type="button" style={button} onClick={onClose}>
        Done
      </button>
    </div>
  );
}
