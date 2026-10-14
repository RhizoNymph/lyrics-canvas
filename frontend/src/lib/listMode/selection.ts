/**
 * Click semantics for list rows, writing to the canvas's shared selection:
 * - plain click: select only that row and open its editor;
 * - ctrl/cmd click: toggle that row (same as a shift-click on the canvas);
 * - shift click: add the visible range from the anchor to the row (list convention, still additive
 *   like the canvas); with no anchor in the visible list it falls back to the canvas toggle.
 */
export interface ListClickModifiers {
  shift: boolean;
  /** Ctrl on Windows/Linux, Cmd on macOS. */
  toggle: boolean;
}

export interface ListSelectionState {
  selection: ReadonlySet<string>;
  /** Row the last plain or toggle click landed on; shift ranges pivot on it. */
  anchor: string | null;
  /** Visible row ids in rendered order. */
  order: ReadonlyArray<string>;
}

export interface ListClickResult {
  selection: Set<string>;
  anchor: string | null;
  openEditor: boolean;
}

function toggled(selection: ReadonlySet<string>, id: string): Set<string> {
  const next = new Set(selection);
  if (next.has(id)) next.delete(id);
  else next.add(id);
  return next;
}

export function listClick(state: ListSelectionState, id: string, mods: ListClickModifiers): ListClickResult {
  if (mods.shift) {
    const from = state.anchor === null ? -1 : state.order.indexOf(state.anchor);
    const to = state.order.indexOf(id);
    if (from < 0 || to < 0) {
      return { selection: toggled(state.selection, id), anchor: id, openEditor: false };
    }
    const [lo, hi] = from <= to ? [from, to] : [to, from];
    const next = new Set(state.selection);
    for (const rowId of state.order.slice(lo, hi + 1)) next.add(rowId);
    return { selection: next, anchor: state.anchor, openEditor: false };
  }
  if (mods.toggle) {
    return { selection: toggled(state.selection, id), anchor: id, openEditor: false };
  }
  return { selection: new Set([id]), anchor: id, openEditor: true };
}
