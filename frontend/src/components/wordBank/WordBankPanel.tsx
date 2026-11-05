import { useEffect, useMemo, useState } from 'react';
import { useUIStore } from '@/stores/uiStore';
import { useTagStore } from '@/stores/tagStore';
import { selectVisibleWords, useWordBankStore } from '@/stores/wordBankStore';
import {
  addInputProblem, clickKind, describeWordBankError, draftFromWords, needsDeleteConfirm, selectedInOrder, splitAddInput,
} from '@/lib/wordBank';
import type { SnippetDraft } from '@/lib/wordBank';
import type { TagToggle } from '@/lib/tags';
import TagFilterBar from '@/components/tags/TagFilterBar';
import TagChecklist from '@/components/tags/TagChecklist';
import type { ToggleOutcome } from '@/components/tags/TagChecklist';
import { errorTextStyle, smallBtnStyle } from '@/components/tags/tagStyles';
import AddWordsBox from './AddWordsBox';
import DraftModal from './DraftModal';
import Popover from './Popover';
import RelatedTab from './RelatedTab';
import RhymesTab from './RhymesTab';
import SelectionBar from './SelectionBar';
import type { DeleteStep } from './SelectionBar';
import WordList from './WordList';
import { WORD_BANK_SHORTCUT_LABEL } from '@/lib/wordBank';
import { WORD_BANK_WIDTH, sectionStyle, tabStyle } from './styles';

export type WordBankTab = 'words' | 'rhymes' | 'related';

const TAB_LABELS: Record<WordBankTab, string> = { words: 'Words', rhymes: 'Rhymes', related: 'Related' };
const ADD_BOX_TARGET = 'add-box';

/** "N words (M shown)". */
export function countLabel(total: number, shown: number): string {
  return `${total} ${total === 1 ? 'word' : 'words'} (${shown} shown)`;
}

/**
 * The word bank sidebar (mounted by CanvasPage while `uiStore.rightPanel === 'words'`): the shared tag
 * filter bar, the selection actions, and three tabs (the list with its add box, the rhyme views, related
 * words). Nothing here touches the canvas except "Make snippet".
 */
export default function WordBankPanel() {
  const toggleWordBank = useUIStore((s) => s.toggleWordBank);
  const tags = useTagStore((s) => s.tags);
  const words = useWordBankStore((s) => s.words);
  const filter = useWordBankStore((s) => s.filter);
  const loadState = useWordBankStore((s) => s.loadState);
  const loadError = useWordBankStore((s) => s.loadError);
  const selection = useWordBankStore((s) => s.selection);
  const addTagIds = useWordBankStore((s) => s.addTagIds);
  const { open, load, dispatchFilter, select, setAddTagIds, addFromText, renameWord, deleteWords, setTag } = useWordBankStore.getState();

  const [tab, setTab] = useState<WordBankTab>('words');
  const [addText, setAddText] = useState('');
  const [addError, setAddError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [renamingId, setRenamingId] = useState<string | null>(null);
  const [deleteStep, setDeleteStep] = useState<DeleteStep>({ kind: 'idle' });
  const [actionError, setActionError] = useState<string | null>(null);
  const [draft, setDraft] = useState<SnippetDraft | null>(null);

  useEffect(() => {
    void open();
  }, [open]);

  const visible = useMemo(() => selectVisibleWords({ words, filter }), [words, filter]);
  const order = useMemo(() => visible.map((w) => w.id), [visible]);
  const selectedWords = useMemo(() => selectedInOrder(words, selection.ids), [words, selection.ids]);
  const chips = useMemo(() => tags.filter((t) => addTagIds.includes(t.id)), [tags, addTagIds]);

  // A changed selection invalidates a pending delete confirmation.
  useEffect(() => setDeleteStep({ kind: 'idle' }), [selection.ids]);

  const liveProblem = addInputProblem(addText);
  const problem = liveProblem?.message ?? addError;

  const submitAdd = async () => {
    setAdding(true);
    setAddError(null);
    const result = await addFromText(addText, 'box', addTagIds);
    setAdding(false);
    if (result.ok) setAddText('');
    else setAddError(describeWordBankError(result.error, splitAddInput(addText)));
  };

  const onSelectionToggle = async (t: TagToggle): Promise<ToggleOutcome> => {
    const result = await setTag(t.targetIds, t.tagId, t.action);
    return result.ok ? { ok: true } : { ok: false, message: describeWordBankError(result.error) };
  };

  const onAddBoxToggle = async (t: TagToggle): Promise<ToggleOutcome> => {
    setAddTagIds(t.action === 'link' ? [...addTagIds, t.tagId] : addTagIds.filter((id) => id !== t.tagId));
    return { ok: true };
  };

  const onDelete = async () => {
    const ids = selectedWords.map((w) => w.id);
    if (deleteStep.kind === 'idle' && needsDeleteConfirm(ids.length)) {
      setDeleteStep({ kind: 'confirm', count: ids.length });
      return;
    }
    setDeleteStep({ kind: 'busy' });
    setActionError(null);
    const result = await deleteWords(ids);
    setDeleteStep({ kind: 'idle' });
    if (!result.ok) setActionError(describeWordBankError(result.error));
  };

  const onRename = async (id: string, text: string): Promise<string | null> => {
    const result = await renameWord(id, text);
    if (result.ok) {
      setRenamingId(null);
      return null;
    }
    return describeWordBankError(result.error);
  };

  return (
    <aside
      data-word-bank
      aria-label="Word bank"
      style={{
        width: WORD_BANK_WIDTH,
        flexShrink: 0,
        background: '#1a1a2e',
        borderLeft: '1px solid #2a2a4a',
        display: 'flex',
        flexDirection: 'column',
        overflow: 'hidden',
        color: '#e0e0e0',
      }}
    >
      <div style={{ ...sectionStyle, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <span style={{ fontWeight: 'bold', fontSize: 14 }}>Word bank</span>
        <button
          onClick={toggleWordBank}
          title={`Close (${WORD_BANK_SHORTCUT_LABEL})`}
          aria-label="Close the word bank"
          style={{ background: 'none', border: 'none', color: '#888', cursor: 'pointer', fontSize: 18 }}
        >
          ×
        </button>
      </div>

      <div style={sectionStyle}>
        <TagFilterBar
          compact
          filter={filter}
          dispatch={dispatchFilter}
          countLabel={countLabel(words.length, visible.length)}
          ariaLabel="Word bank filter expression"
          noTagsHint="No tags yet. Tag words from the selection's Tags menu."
          pillTitle={(tag) => `Insert "${tag.name}" at the caret (${tag.word_count} words)`}
        />
      </div>

      <div style={sectionStyle}>
        <SelectionBar
          count={selectedWords.length}
          deleteStep={deleteStep}
          tagControl={
            <Popover label="Tags" disabled={selectedWords.length === 0} title="Tag the selected words">
              <TagChecklist
                targets={selectedWords}
                onToggle={onSelectionToggle}
                title={`Tags · ${selectedWords.length} ${selectedWords.length === 1 ? 'word' : 'words'}`}
                countOf={(t) => t.word_count}
                countNoun="words"
              />
            </Popover>
          }
          onMakeSnippet={() => setDraft(draftFromWords(selectedWords))}
          onDelete={() => void onDelete()}
          onCancelDelete={() => setDeleteStep({ kind: 'idle' })}
          onClear={() => select({ type: 'clear' })}
        />
        {actionError !== null && <div style={{ ...errorTextStyle, marginTop: 4 }}>{actionError}</div>}
      </div>

      <div role="tablist" aria-label="Word bank views" style={{ display: 'flex', borderBottom: '1px solid #2a2a4a' }}>
        {(Object.keys(TAB_LABELS) as WordBankTab[]).map((t) => (
          <button key={t} type="button" role="tab" aria-selected={tab === t} onClick={() => setTab(t)} style={tabStyle(tab === t)}>
            {TAB_LABELS[t]}
          </button>
        ))}
      </div>

      <div style={{ flex: 1, overflowY: 'auto', padding: '8px 12px 12px' }}>
        {tab === 'words' && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            <AddWordsBox
              text={addText}
              problem={problem}
              busy={adding}
              chips={chips}
              onText={(t) => {
                setAddText(t);
                setAddError(null);
              }}
              onSubmit={() => void submitAdd()}
              onRemoveChip={(id) => setAddTagIds(addTagIds.filter((x) => x !== id))}
              tagControl={
                <Popover label="+ tag" title="Tags the added words get">
                  <TagChecklist
                    targets={[{ id: ADD_BOX_TARGET, tags: addTagIds.map((id) => ({ id })) }]}
                    onToggle={onAddBoxToggle}
                    title="Tag new words with"
                    countOf={(t) => t.word_count}
                    countNoun="words"
                  />
                </Popover>
              }
            />
            {loadState === 'loading' && words.length === 0 && <div style={{ color: '#8a8aaa', fontSize: 11 }}>Loading…</div>}
            {loadState === 'failed' && loadError !== null && (
              <div style={errorTextStyle}>
                {describeWordBankError(loadError)}{' '}
                <button type="button" onClick={() => void load()} style={{ ...smallBtnStyle, padding: '0 6px' }}>Retry</button>
              </div>
            )}
            <WordList
              words={visible}
              bankEmpty={words.length === 0 && loadState !== 'loading'}
              selected={selection.ids}
              renamingId={renamingId}
              onRowClick={(id, e) => {
                const kind = clickKind(e);
                if (kind === 'range') {
                  e.preventDefault();
                  select({ type: 'range', id, order });
                } else select({ type: kind, id });
              }}
              onCheckbox={(id) => select({ type: 'toggle', id })}
              onStartRename={setRenamingId}
              onRename={onRename}
              onCancelRename={() => setRenamingId(null)}
            />
          </div>
        )}
        {tab === 'rhymes' && <RhymesTab />}
        {tab === 'related' && <RelatedTab />}
      </div>

      {draft !== null && <DraftModal initial={draft} onClose={() => setDraft(null)} />}
    </aside>
  );
}
