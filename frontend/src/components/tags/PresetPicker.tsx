import { useEffect, useRef, useState } from 'react';
import { useTagStore } from '@/stores/tagStore';
import { checkPresetExpression, describeTagError } from '@/lib/tags';
import type { TagPreset } from '@/types';
import { COLORS, errorTextStyle, fieldStyle, monoStyle, mutedTextStyle, popoverStyle, smallBtnStyle } from './tagStyles';

interface Props {
  /** The expression text of the field this picker serves (saved / overwritten from here). */
  currentText: string;
  /** Called with the preset's expression; the picker closes. */
  onApply: (expression: string) => void;
  /** Open the popover to the left instead of the right edge of the button. */
  align?: 'left' | 'right';
}

type Mode =
  | { kind: 'list' }
  | { kind: 'save'; name: string; error: string | null }
  | { kind: 'rename'; id: string; name: string; error: string | null };

/**
 * The user's saved filter expressions: one click applies; save the current text as a new preset (name
 * prompt; a taken name shows inline); rename, overwrite with the current text, or delete (confirmed).
 */
export default function PresetPicker({ currentText, onApply, align = 'right' }: Props) {
  const presets = useTagStore((s) => s.presets);
  const createPreset = useTagStore((s) => s.createPreset);
  const updatePreset = useTagStore((s) => s.updatePreset);
  const deletePreset = useTagStore((s) => s.deletePreset);
  const [open, setOpen] = useState(false);
  const [mode, setMode] = useState<Mode>({ kind: 'list' });
  const [confirmDelete, setConfirmDelete] = useState<string | null>(null);
  const [rowError, setRowError] = useState<{ id: string; message: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);

  const current = checkPresetExpression(currentText);
  const currentProblem = current.ok
    ? null
    : current.problem === 'empty_preset'
      ? 'Type an expression first'
      : 'The current expression is not valid';

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, [open]);

  const close = () => {
    setOpen(false);
    setMode({ kind: 'list' });
    setConfirmDelete(null);
    setRowError(null);
  };

  const apply = (p: TagPreset) => {
    onApply(p.expression);
    close();
  };

  const save = async () => {
    if (mode.kind !== 'save' || busy) return;
    setBusy(true);
    const result = await createPreset(mode.name, currentText);
    setBusy(false);
    if (result.ok) setMode({ kind: 'list' });
    else setMode({ ...mode, error: describeTagError(result.error) });
  };

  const rename = async () => {
    if (mode.kind !== 'rename' || busy) return;
    setBusy(true);
    const result = await updatePreset(mode.id, { name: mode.name });
    setBusy(false);
    if (result.ok) setMode({ kind: 'list' });
    else setMode({ ...mode, error: describeTagError(result.error) });
  };

  const overwrite = async (p: TagPreset) => {
    if (busy) return;
    setBusy(true);
    const result = await updatePreset(p.id, { expression: currentText });
    setBusy(false);
    setRowError(result.ok ? null : { id: p.id, message: describeTagError(result.error) });
  };

  const remove = async (p: TagPreset) => {
    if (confirmDelete !== p.id) {
      setConfirmDelete(p.id);
      return;
    }
    setBusy(true);
    const result = await deletePreset(p.id);
    setBusy(false);
    setConfirmDelete(null);
    setRowError(result.ok ? null : { id: p.id, message: describeTagError(result.error) });
  };

  const iconBtn = { ...smallBtnStyle, padding: '1px 6px' };

  return (
    <div ref={rootRef} style={{ position: 'relative', flexShrink: 0 }}>
      <button
        type="button"
        aria-haspopup="dialog"
        aria-expanded={open}
        onClick={() => (open ? close() : setOpen(true))}
        style={{ ...smallBtnStyle, ...(open ? { borderColor: COLORS.accent } : {}) }}
        title="Saved filter expressions"
      >
        Presets ▾
      </button>
      {open && (
        <div
          role="dialog"
          aria-label="Tag filter presets"
          style={{ ...popoverStyle, top: '100%', marginTop: 4, [align]: 0, width: 300, padding: '6px 0' }}
        >
          {presets.length === 0 && <div style={{ ...mutedTextStyle, padding: '4px 10px' }}>No presets yet.</div>}
          <div style={{ maxHeight: 260, overflowY: 'auto' }}>
            {presets.map((p) =>
              mode.kind === 'rename' && mode.id === p.id ? (
                <div key={p.id} style={{ padding: '4px 10px' }}>
                  <div style={{ display: 'flex', gap: 4 }}>
                    <input
                      autoFocus
                      aria-label="Preset name"
                      value={mode.name}
                      onChange={(e) => setMode({ ...mode, name: e.target.value, error: null })}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') void rename();
                        if (e.key === 'Escape') setMode({ kind: 'list' });
                      }}
                      style={fieldStyle}
                    />
                    <button type="button" disabled={busy} onClick={() => void rename()} style={smallBtnStyle}>Save</button>
                    <button type="button" onClick={() => setMode({ kind: 'list' })} style={smallBtnStyle}>Cancel</button>
                  </div>
                  {mode.error && <div style={{ ...errorTextStyle, marginTop: 2 }}>{mode.error}</div>}
                </div>
              ) : (
                <div key={p.id} style={{ padding: '3px 10px' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                    <button
                      type="button"
                      onClick={() => apply(p)}
                      title={`Apply: ${p.expression}`}
                      style={{ flex: 1, minWidth: 0, textAlign: 'left', background: 'none', border: 'none', color: COLORS.text, cursor: 'pointer', padding: '2px 0' }}
                    >
                      <div style={{ fontSize: 12, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{p.name}</div>
                      <div style={{ ...mutedTextStyle, ...monoStyle, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        {p.expression}
                      </div>
                    </button>
                    <button type="button" onClick={() => setMode({ kind: 'rename', id: p.id, name: p.name, error: null })} style={iconBtn} title="Rename">✎</button>
                    <button
                      type="button"
                      disabled={!current.ok || busy}
                      onClick={() => void overwrite(p)}
                      style={{ ...iconBtn, opacity: current.ok ? 1 : 0.4 }}
                      title={current.ok ? `Overwrite with: ${current.normalized}` : currentProblem ?? ''}
                    >
                      ⤓
                    </button>
                    <button
                      type="button"
                      disabled={busy}
                      onClick={() => void remove(p)}
                      style={{ ...iconBtn, color: COLORS.danger }}
                      title={confirmDelete === p.id ? 'Click again to delete' : 'Delete'}
                    >
                      {confirmDelete === p.id ? 'Delete?' : '✕'}
                    </button>
                  </div>
                  {rowError?.id === p.id && <div style={errorTextStyle}>{rowError.message}</div>}
                </div>
              ),
            )}
          </div>
          <div style={{ borderTop: `1px solid ${COLORS.border}`, marginTop: 4, padding: '6px 10px 2px' }}>
            {mode.kind === 'save' ? (
              <>
                <div style={{ display: 'flex', gap: 4 }}>
                  <input
                    autoFocus
                    aria-label="New preset name"
                    placeholder="Preset name"
                    value={mode.name}
                    onChange={(e) => setMode({ ...mode, name: e.target.value, error: null })}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') void save();
                      if (e.key === 'Escape') setMode({ kind: 'list' });
                    }}
                    style={fieldStyle}
                  />
                  <button type="button" disabled={busy} onClick={() => void save()} style={smallBtnStyle}>Save</button>
                  <button type="button" onClick={() => setMode({ kind: 'list' })} style={smallBtnStyle}>Cancel</button>
                </div>
                <div style={{ ...mutedTextStyle, ...monoStyle, marginTop: 2 }}>{current.ok ? current.normalized : ''}</div>
                {mode.error && <div style={{ ...errorTextStyle, marginTop: 2 }}>{mode.error}</div>}
              </>
            ) : (
              <button
                type="button"
                disabled={!current.ok}
                onClick={() => setMode({ kind: 'save', name: '', error: null })}
                style={{ ...smallBtnStyle, opacity: current.ok ? 1 : 0.5, width: '100%' }}
                title={currentProblem ?? 'Save the current expression under a name'}
              >
                Save as preset…
              </button>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
