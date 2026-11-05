import { useMemo } from 'react';
import { useCanvasStore } from '@/stores/canvasStore';
import { useTagStore } from '@/stores/tagStore';
import { pickerTargets } from '@/lib/tags';
import TagChecklist, { fromTagResult } from './TagChecklist';

interface Props {
  /** The snippet the menu was opened on; a multi-selection containing it is edited as a whole. */
  contextSnippetId: string;
}

/**
 * The snippet context menu's tag picker: the shared TagChecklist over the targeted snippets, linking
 * through tagStore.applyToggle (one request per snippet).
 */
export default function TagPicker({ contextSnippetId }: Props) {
  const snippets = useCanvasStore((s) => s.snippets);
  const selection = useCanvasStore((s) => s.selectedSnippetIds);
  const applyToggle = useTagStore((s) => s.applyToggle);
  const targets = useMemo(() => pickerTargets(contextSnippetId, selection, snippets), [contextSnippetId, selection, snippets]);

  return (
    <TagChecklist
      targets={targets}
      onToggle={async (t) => fromTagResult(await applyToggle({ action: t.action, tagId: t.tagId, snippetIds: t.targetIds }))}
      title={`Tags${targets.length > 1 ? ` · ${targets.length} snippets` : ''}`}
      countOf={(tag) => tag.snippet_count}
      countNoun="snippets"
    />
  );
}
