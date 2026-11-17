import { useEffect, useRef, useState } from 'react';
import type { CSSProperties } from 'react';
import { hasScore } from '@/lib/scoreTools/exportFile';
import { SCORE_EXPORT_EXTENSIONS, SCORE_EXPORT_FORMATS, SCORE_EXPORT_LABELS } from '@/types/scoreTools';
import type { ScoreExportFormat } from '@/types/scoreTools';
import type { Score } from '@/types/score';
import { exportScoreWithNotice } from './exportScore';

export interface ScoreExportEntry {
  format: ScoreExportFormat;
  label: string;
}

/** The export entries of a snippet: MIDI and MusicXML when it has a score, none otherwise. */
export function scoreExportEntries(snippet: { readonly id: string; readonly score?: Score | null }): ScoreExportEntry[] {
  if (!hasScore(snippet)) return [];
  return SCORE_EXPORT_FORMATS.map((format) => ({
    format,
    label: `Export ${SCORE_EXPORT_LABELS[format]} (${SCORE_EXPORT_EXTENSIONS[format]})`,
  }));
}

interface ViewProps {
  open: boolean;
  busy: boolean;
  onToggle: () => void;
  onPick: (format: ScoreExportFormat) => void;
  buttonStyle?: CSSProperties;
}

const defaultButton: CSSProperties = {
  background: 'rgba(0,0,0,0.3)',
  color: '#fff',
  border: '1px solid rgba(255,255,255,0.3)',
  borderRadius: 4,
  padding: '1px 6px',
  fontSize: 10,
  cursor: 'pointer',
};

/** "Export" button with a two-entry dropdown (store-free). */
export function ScoreExportMenuView({ open, busy, onToggle, onPick, buttonStyle = defaultButton }: ViewProps) {
  return (
    <span style={{ position: 'relative', display: 'inline-block' }}>
      <button
        type="button"
        aria-haspopup="menu"
        aria-expanded={open}
        disabled={busy}
        onClick={onToggle}
        title="Download the saved score as MIDI or MusicXML"
        style={{ ...buttonStyle, opacity: busy ? 0.6 : 1 }}
      >
        {busy ? 'Exporting…' : 'Export'}
      </button>
      {open && (
        <div
          role="menu"
          style={{
            position: 'absolute',
            right: 0,
            top: '100%',
            marginTop: 2,
            zIndex: 20,
            background: '#1e1e3a',
            border: '1px solid #3a3a5c',
            borderRadius: 6,
            padding: '4px 0',
            minWidth: 150,
            boxShadow: '0 6px 18px rgba(0,0,0,0.5)',
            fontFamily: 'system-ui, sans-serif',
            fontSize: 12,
          }}
        >
          {SCORE_EXPORT_FORMATS.map((format) => (
            <button
              key={format}
              type="button"
              role="menuitem"
              onClick={() => onPick(format)}
              style={{
                display: 'block',
                width: '100%',
                textAlign: 'left',
                background: 'transparent',
                border: 'none',
                color: '#d0d0e8',
                padding: '5px 12px',
                cursor: 'pointer',
                fontSize: 12,
              }}
            >
              {`${SCORE_EXPORT_LABELS[format]} (${SCORE_EXPORT_EXTENSIONS[format]})`}
            </button>
          ))}
        </div>
      )}
    </span>
  );
}

/**
 * The editor header's export menu: shown only for a snippet with a score; exports the saved score and
 * reports the outcome in the bottom notice. Escape or a click elsewhere closes the dropdown.
 */
export default function ScoreExportMenu({
  snippet,
  buttonStyle,
}: {
  snippet: { readonly id: string; readonly score?: Score | null };
  buttonStyle?: CSSProperties;
}) {
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const ref = useRef<HTMLSpanElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  if (!hasScore(snippet)) return null;

  const onPick = (format: ScoreExportFormat) => {
    setOpen(false);
    setBusy(true);
    void exportScoreWithNotice(snippet.id, format).finally(() => setBusy(false));
  };

  return (
    <span ref={ref}>
      <ScoreExportMenuView open={open} busy={busy} onToggle={() => setOpen((o) => !o)} onPick={onPick} buttonStyle={buttonStyle} />
    </span>
  );
}
