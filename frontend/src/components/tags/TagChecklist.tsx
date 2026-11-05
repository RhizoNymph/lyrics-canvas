import { useMemo, useState } from 'react';
import type { KeyboardEvent as ReactKeyboardEvent } from 'react';
import { useTagStore } from '@/stores/tagStore';
import {
  TAG_COLOR_CHOICES, checkState, createOffer, describeNameProblem, describeTagError, isHexColor, planTagToggle, searchTags,
} from '@/lib/tags';
import type { CheckState, TagResult, TagToggle, Taggable } from '@/lib/tags';
import { moveHighlight } from '@/lib/tagFilter';
import type { Tag } from '@/types';
import { COLORS, errorTextStyle, fieldStyle, mutedTextStyle, smallBtnStyle } from './tagStyles';

/** What a link/unlink did: nothing to report, or a message to show inline. */
export type ToggleOutcome = { ok: true } | { ok: false; message: string };

interface Props {
  /** What the checklist edits (snippets, bank words, or the add box's pending tags). */
  targets: readonly Taggable[];
  /** Apply a link or unlink to the targets in `toggle.targetIds`. */
  onToggle: (toggle: TagToggle) => Promise<ToggleOutcome>;
  /** Header, e.g. "Tags · 3 snippets". */
  title: string;
  /** The count shown per tag row and in the delete confirmation (snippets or words carrying it). */
  countOf: (tag: Tag) => number;
  countNoun: string;
  autoFocus?: boolean;
}

interface Editing {
  id: string;
  name: string;
  color: string;
  confirmDelete: boolean;
}

/** A tag store result as an outcome for the inline error line. */
export function fromTagResult(result: TagResult<unknown>): ToggleOutcome {
  return result.ok ? { ok: true } : { ok: false, message: describeTagError(result.error) };
}

const CHECK_GLYPH: Record<CheckState, string> = { all: '✓', some: '–', none: '' };

/**
 * Searchable checklist of the user's tags over any targets (snippets or word bank words). Toggling
 * links/unlinks immediately (on every target; a mixed tag links where missing), "Create '<query>'"
 * creates and links, and each tag can be renamed, recolored or deleted in place. Logic lives in
 * lib/tags/picker.ts.
 */
export default function TagChecklist({ targets, onToggle, title, countOf, countNoun, autoFocus = true }: Props) {
  const tags = useTagStore((s) => s.tags);
  const loadState = useTagStore((s) => s.loadState);
  const load = useTagStore((s) => s.load);
  const createTag = useTagStore((s) => s.createTag);
  const updateTag = useTagStore((s) => s.updateTag);
  const deleteTag = useTagStore((s) => s.deleteTag);

  const [query, setQuery] = useState('');
  const [highlight, setHighlight] = useState(0);
  const [editing, setEditing] = useState<Editing | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const matches = useMemo(() => searchTags(tags, query), [tags, query]);
  const offer = createOffer(query, tags);
  const itemCount = matches.length + (offer.kind === 'create' ? 1 : 0);

  const run = async (op: () => Promise<ToggleOutcome>): Promise<boolean> => {
    if (busy) return false;
    setBusy(true);
    setError(null);
    const outcome = await op();
    setBusy(false);
    if (!outcome.ok) setError(outcome.message);
    return outcome.ok;
  };

  const toggle = (tag: Tag) => run(() => onToggle(planTagToggle(tag.id, targets)));

  const create = (name: string) =>
    run(async () => {
      const created = await createTag(name, TAG_COLOR_CHOICES[tags.length % TAG_COLOR_CHOICES.length]);
      if (!created.ok) return fromTagResult(created);
      setQuery('');
      return onToggle({ action: 'link', tagId: created.value.id, targetIds: targets.map((s) => s.id) });
    });

  const saveEdit = async () => {
    if (editing === null) return;
    const tag = tags.find((t) => t.id === editing.id);
    if (tag === undefined) {
      setEditing(null);
      return;
    }
    const patch: { name?: string; color?: string } = {};
    if (editing.name.trim() !== tag.name) patch.name = editing.name;
    if (editing.color !== tag.color && isHexColor(editing.color)) patch.color = editing.color;
    if (Object.keys(patch).length === 0) {
      setEditing(null);
      return;
    }
    if (await run(async () => fromTagResult(await updateTag(editing.id, patch)))) setEditing(null);
  };

  const removeTag = async () => {
    if (editing === null) return;
    if (!editing.confirmDelete) {
      setEditing({ ...editing, confirmDelete: true });
      return;
    }
    if (await run(async () => fromTagResult(await deleteTag(editing.id)))) setEditing(null);
  };

  const onSearchKey = (e: ReactKeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      setHighlight((h) => moveHighlight(h, itemCount, e.key === 'ArrowDown' ? 1 : -1));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      const i = highlight >= 0 && highlight < itemCount ? highlight : 0;
      if (i < matches.length) void toggle(matches[i]);
      else if (offer.kind === 'create') void create(offer.name);
    }
  };

  const rowStyle = (active: boolean) => ({
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    padding: '5px 12px',
    cursor: 'pointer',
    background: active ? COLORS.hover : 'transparent',
  });

  return (
    <div onClick={(e) => e.stopPropagation()}>
      <div style={{ padding: '6px 12px 4px', fontSize: 11, color: '#7a7a9a', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.05em' }}>
        {title}
      </div>
      <div style={{ padding: '0 12px 4px' }}>
        <input
          autoFocus={autoFocus}
          aria-label="Search or create tags"
          placeholder="Search or create…"
          value={query}
          onChange={(e) => {
            setQuery(e.target.value);
            setHighlight(0);
            setError(null);
          }}
          onKeyDown={onSearchKey}
          style={fieldStyle}
        />
      </div>
      {loadState === 'failed' && (
        <div style={{ ...errorTextStyle, padding: '2px 12px' }}>
          Tags failed to load.{' '}
          <button type="button" onClick={() => void load()} style={{ ...smallBtnStyle, padding: '0 6px' }}>Retry</button>
        </div>
      )}
      <div role="listbox" aria-label="Tags" aria-multiselectable style={{ maxHeight: 260, overflowY: 'auto' }}>
        {matches.map((tag, i) => {
          if (editing?.id === tag.id) {
            return (
              <div key={tag.id} style={{ padding: '4px 12px' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 4, marginBottom: 4 }}>
                  {TAG_COLOR_CHOICES.map((c) => (
                    <button
                      key={c}
                      type="button"
                      aria-label={`Color ${c}`}
                      onClick={() => setEditing({ ...editing, color: c })}
                      style={{
                        width: 14, height: 14, borderRadius: '50%', background: c, cursor: 'pointer', padding: 0, flexShrink: 0,
                        border: editing.color.toLowerCase() === c ? '2px solid #fff' : '2px solid transparent',
                      }}
                    />
                  ))}
                  <input
                    type="color"
                    aria-label="Custom color"
                    value={isHexColor(editing.color) ? editing.color : '#6366f1'}
                    onChange={(e) => setEditing({ ...editing, color: e.target.value })}
                    style={{ width: 20, height: 18, padding: 0, border: 'none', background: 'none', cursor: 'pointer' }}
                  />
                </div>
                <input
                  autoFocus
                  aria-label="Tag name"
                  value={editing.name}
                  onChange={(e) => setEditing({ ...editing, name: e.target.value, confirmDelete: false })}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') void saveEdit();
                    if (e.key === 'Escape') {
                      e.stopPropagation();
                      setEditing(null);
                    }
                  }}
                  style={fieldStyle}
                />
                <div style={{ display: 'flex', gap: 4, marginTop: 4 }}>
                  <button type="button" disabled={busy} onClick={() => void saveEdit()} style={smallBtnStyle}>Save</button>
                  <button type="button" onClick={() => setEditing(null)} style={smallBtnStyle}>Cancel</button>
                  <button
                    type="button"
                    disabled={busy}
                    onClick={() => void removeTag()}
                    style={{ ...smallBtnStyle, marginLeft: 'auto', color: COLORS.danger }}
                    title={`Delete the tag everywhere (${tag.snippet_count} snippets, ${tag.word_count} words)`}
                  >
                    {editing.confirmDelete ? `Delete from ${tag.snippet_count + tag.word_count}?` : 'Delete'}
                  </button>
                </div>
              </div>
            );
          }
          const state = checkState(tag.id, targets);
          return (
            <div
              key={tag.id}
              role="option"
              aria-selected={state === 'all'}
              aria-checked={state === 'some' ? 'mixed' : state === 'all'}
              style={rowStyle(i === highlight)}
              onMouseEnter={() => setHighlight(i)}
              onClick={() => void toggle(tag)}
            >
              <span
                style={{
                  width: 16, height: 16, borderRadius: 3, flexShrink: 0, display: 'flex', alignItems: 'center', justifyContent: 'center',
                  fontSize: 11, color: '#fff',
                  border: state === 'none' ? '2px solid #4a4a6a' : 'none',
                  background: state === 'all' ? COLORS.accent : state === 'some' ? '#4a4a8a' : 'transparent',
                }}
              >
                {CHECK_GLYPH[state]}
              </span>
              <span style={{ width: 10, height: 10, borderRadius: '50%', background: tag.color, flexShrink: 0 }} />
              <span style={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{tag.name}</span>
              <span style={{ ...mutedTextStyle, fontSize: 10 }} title={`${countOf(tag)} ${countNoun}`}>{countOf(tag)}</span>
              <button
                type="button"
                title="Edit tag"
                aria-label={`Edit ${tag.name}`}
                onClick={(e) => {
                  e.stopPropagation();
                  setError(null);
                  setEditing({ id: tag.id, name: tag.name, color: tag.color, confirmDelete: false });
                }}
                style={{ background: 'none', border: 'none', color: '#6a6a8a', cursor: 'pointer', fontSize: 11, padding: '0 2px' }}
              >
                ✎
              </button>
            </div>
          );
        })}
        {offer.kind === 'create' && (
          <div
            role="option"
            aria-selected={false}
            style={{ ...rowStyle(highlight === matches.length), color: '#a5a6f6' }}
            onMouseEnter={() => setHighlight(matches.length)}
            onClick={() => void create(offer.name)}
          >
            <span style={{ width: 16, textAlign: 'center', fontSize: 15 }}>+</span>
            <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>Create '{offer.name}'</span>
          </div>
        )}
        {offer.kind === 'invalid' && <div style={{ ...errorTextStyle, padding: '2px 12px' }}>{describeNameProblem(offer.problem)}</div>}
        {matches.length === 0 && offer.kind === 'none' && tags.length === 0 && (
          <div style={{ ...mutedTextStyle, padding: '2px 12px' }}>No tags yet. Type a name to create one.</div>
        )}
      </div>
      {error && <div style={{ ...errorTextStyle, padding: '4px 12px' }}>{error}</div>}
    </div>
  );
}
