import { useMemo, useState } from 'react';
import { useCanvasStore } from '@/stores/canvasStore';
import { LINES_PAGER, knownSlots, trayOf, useRhymePatternStore } from '@/stores/rhymePatternStore';
import type { PatternView, ReadyView, RhymePatternState, SuggestStatus } from '@/stores/rhymePatternStore';
import { useRhymePatternQuery } from '@/hooks/useRhymePatternQuery';
import { canLoadMoreLines, describePatternError, slotViews, wordActions } from '@/lib/rhyme/pattern';
import type { Member, PatternError, SlotView } from '@/lib/rhyme/pattern';
import type { Snippet } from '@/types';
import { displayText } from '@/lib/grid/restMarks';
import PatternHeader from './PatternHeader';
import PatternLinesView from './PatternLinesView';
import type { PickedWord } from './PatternLinesView';
import SlotLegend from './SlotLegend';
import SlotColumns from './SlotColumns';
import TupleTray from './TupleTray';
import LineMatchList from './LineMatchList';
import PatternControls, { DetectThreshold } from './PatternControls';
import { errorStyle, mutedStyle } from '../sidebarStyles';

const LABEL_MAX = 24;

/** What to say instead of (or above) the slot lists; null when the lists speak for themselves. */
export function patternEmptyMessage(slots: readonly SlotView[], suggest: SuggestStatus): string | null {
  if (slots.length === 0) return "These lines don't share a rhyme at this threshold. Lower the detection threshold, or tap words to make a slot.";
  if (!slots.some((s) => s.on)) return 'Every slot is off. Turn one on to search.';
  if (suggest.kind === 'idle') return 'Turn on notes or dictionary to search.';
  return null;
}

/** The words of a member, from the detect response's lines. */
function memberText(view: ReadyView, m: Member): string {
  const line = view.lines.find((l) => l.line_index === m.line);
  return (line?.words ?? []).slice(m.start, m.end).map((w) => w.text).join(' ');
}

/** The store actions the panel calls (the store itself in the app; stubs in tests). */
export type PanelActions = Pick<
  RhymePatternState,
  'edit' | 'togglePick' | 'dismiss' | 'loadMoreSlot' | 'loadMoreLines' | 'makeSnippet' | 'addPicksToBank' | 'clearPicks' | 'materializeLine' | 'exit' | 'reset'
>;

interface ReadyProps {
  view: ReadyView;
  suggest: SuggestStatus;
  paging: ReadonlySet<string>;
  notice: string | null;
  actionError: PatternError | null;
  actions: PanelActions;
}

function ReadyBody({ view, suggest, paging, notice, actionError, actions }: ReadyProps) {
  const [picked, setPicked] = useState<PickedWord | null>(null);

  const views = useMemo(() => slotViews(view.edit, knownSlots(view)), [view]);
  const onViews = views.filter((v) => v.on);
  const indexById = new Map(views.map((v) => [v.id, v.index]));
  const onIndex = (id: string) => (onViews.some((v) => v.id === id) ? indexById.get(id) ?? null : null);
  const wordMenu = picked === null ? [] : wordActions(view.edit, picked.line, picked.word, (m) => memberText(view, m));
  const empty = patternEmptyMessage(views, suggest);
  const results = view.results;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
      <PatternLinesView
        lines={view.lines}
        edit={view.edit}
        views={views}
        picked={picked}
        actions={wordMenu}
        onWordClick={(w) => setPicked((cur) => (cur !== null && cur.line === w.line && cur.word === w.word ? null : w))}
        onAction={(a) => {
          actions.edit(a.edit);
          setPicked(null);
        }}
        onClosePicker={() => setPicked(null)}
      />
      <SlotLegend views={views} onToggle={(id) => actions.edit({ type: 'toggle', slotId: id })} onDrop={(id) => actions.edit({ type: 'drop', slotId: id })} />
      {notice !== null && <div style={mutedStyle}>{notice}</div>}
      {empty !== null && <div style={mutedStyle}>{empty}</div>}
      {views.length === 0 && <DetectThreshold />}
      {suggest.kind === 'failed' && <div style={errorStyle}>{describePatternError(suggest.error)}</div>}
      {actionError !== null && <div style={errorStyle}>{describePatternError(actionError)}</div>}
      {onViews.length > 0 && suggest.kind !== 'idle' && (
        <SlotColumns
          slots={onViews}
          columns={results?.columns ?? null}
          picks={view.picks}
          paging={paging}
          loading={suggest.kind === 'loading'}
          onPick={actions.togglePick}
          onDismiss={actions.dismiss}
          onMore={(slotId) => void actions.loadMoreSlot(slotId)}
        />
      )}
      <TupleTray
        entries={trayOf(view)}
        indexOf={(id) => indexById.get(id) ?? 0}
        onMakeSnippet={() => void actions.makeSnippet()}
        onAddToBank={() => void actions.addPicksToBank()}
        onClear={actions.clearPicks}
      />
      {results !== null && (
        <LineMatchList
          matches={results.lineMatches}
          total={results.lineMatchTotal}
          slotCount={onViews.length}
          slotIndex={onIndex}
          canMore={canLoadMoreLines(results)}
          loadingMore={paging.has(LINES_PAGER)}
          onMaterialize={(id) => void actions.materializeLine(id)}
          onDismiss={actions.dismiss}
          onMore={() => void actions.loadMoreLines()}
        />
      )}
    </div>
  );
}

export interface PatternPanelViewProps {
  view: Exclude<PatternView, { kind: 'off' }>;
  suggest: SuggestStatus;
  paging: ReadonlySet<string>;
  notice: string | null;
  actionError: PatternError | null;
  snippetLabel: string;
  /** Other selected snippets (ignored in pattern mode). */
  ignored: number;
  actions: PanelActions;
}

export function PatternPanelView({ view, suggest, paging, notice, actionError, snippetLabel, ignored, actions }: PatternPanelViewProps) {
  const { session } = view;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10, padding: 12, overflowY: 'auto', flex: 1, minHeight: 0 }}>
      <PatternHeader lineStart={session.lineStart} lineEnd={session.lineEnd} snippetLabel={snippetLabel} ignored={ignored} onBack={actions.exit} onReset={actions.reset} />
      {view.kind === 'detecting' && <div style={mutedStyle}>Finding where these lines rhyme…</div>}
      {view.kind === 'detect_failed' && <div style={errorStyle}>{describePatternError(view.error)}</div>}
      {view.kind === 'ready' && <ReadyBody view={view} suggest={suggest} paging={paging} notice={notice} actionError={actionError} actions={actions} />}
      <PatternControls />
    </div>
  );
}

function snippetLabelOf(snippet: Snippet | undefined): string {
  if (snippet === undefined) return '(snippet not on this canvas)';
  const first = displayText(snippet.raw_text).split('\n')[0] ?? '';
  return first.length > LABEL_MAX ? `${first.slice(0, LABEL_MAX - 1)}…` : first;
}

/**
 * The sidebar's Pattern mode: the lines with their slots, one list per on slot, the tuple tray and the
 * whole-line matches. Shown by RhymeSidebar while `rhymePatternStore.view` is not `off`.
 */
export default function PatternPanel() {
  useRhymePatternQuery();
  const view = useRhymePatternStore((s) => s.view);
  const suggest = useRhymePatternStore((s) => s.suggest);
  const paging = useRhymePatternStore((s) => s.paging);
  const notice = useRhymePatternStore((s) => s.notice);
  const actionError = useRhymePatternStore((s) => s.actionError);
  const snippets = useCanvasStore((s) => s.snippets);
  const selectedIds = useCanvasStore((s) => s.selectedSnippetIds);
  if (view.kind === 'off') return null;
  const ignored = [...selectedIds].filter((id) => id !== view.session.snippetId).length;
  return (
    <PatternPanelView
      view={view}
      suggest={suggest}
      paging={paging}
      notice={notice}
      actionError={actionError}
      snippetLabel={snippetLabelOf(snippets.get(view.session.snippetId))}
      ignored={ignored}
      actions={useRhymePatternStore.getState()}
    />
  );
}
