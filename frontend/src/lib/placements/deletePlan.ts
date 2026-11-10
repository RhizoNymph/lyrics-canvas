/** Delete vs Remove from this canvas vs Delete everywhere (contract §3 Delete). */
import type { Snippet } from '@/types';
import { isOnOtherCanvases, otherCanvases, sortCanvasRefs } from './canvases';

export interface DeleteMenu {
  /** The plain delete item: "Remove from this canvas" when any target is on other canvases. */
  readonly removeLabel: string;
  readonly anyShared: boolean;
  /** "Delete everywhere" is offered only when it differs from the plain delete (some target is shared). */
  readonly offerDeleteEverywhere: boolean;
}

export function deleteMenu(targets: readonly Snippet[], currentCanvasId: string): DeleteMenu {
  const anyShared = targets.some((s) => isOnOtherCanvases(s, currentCanvasId));
  if (anyShared) return { removeLabel: 'Remove from this canvas', anyShared, offerDeleteEverywhere: true };
  const removeLabel = targets.length > 1 ? `Delete ${targets.length} snippets` : 'Delete snippet';
  return { removeLabel, anyShared, offerDeleteEverywhere: false };
}

function joinTitles(titles: readonly string[]): string {
  if (titles.length <= 1) return titles.join('');
  return `${titles.slice(0, -1).join(', ')} and ${titles[titles.length - 1]}`;
}

/** The Delete everywhere confirmation, naming every other canvas the targets are on. */
export function deleteEverywhereConfirm(targets: readonly Snippet[], currentCanvasId: string): string {
  const byId = new Map(targets.flatMap((s) => otherCanvases(s, currentCanvasId)).map((c) => [c.id, c]));
  const titles = sortCanvasRefs([...byId.values()]).map((c) => c.title);
  const one = targets.length === 1;
  const head = one ? 'Delete this snippet everywhere?' : `Delete ${targets.length} snippets everywhere?`;
  const also = titles.length === 0 ? '' : ` ${one ? 'It' : 'They'} will also disappear from ${joinTitles(titles)}.`;
  return `${head}${also} Recordings are deleted too. This cannot be undone.`;
}
