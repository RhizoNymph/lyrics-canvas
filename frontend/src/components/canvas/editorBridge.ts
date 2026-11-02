/**
 * A narrow handle the open InlineGridEditor registers so code outside it (voice capture) can save its
 * pending edits without closing it, and replace its rows after a server-side change to the snippet.
 * One registration per snippet id; unregistering a stale handle never removes a newer one.
 */
import type { Snippet } from '@/types';

export interface EditorBridge {
  /** Persist the editor's current rows and type (PUT), keeping the editor open. Rejects on failure. */
  flush(): Promise<void>;
  /** Replace the editor's rows from a snippet the server returned. */
  reload(snippet: Snippet): void;
}

const bridges = new Map<string, EditorBridge>();

export function registerEditorBridge(snippetId: string, bridge: EditorBridge): () => void {
  bridges.set(snippetId, bridge);
  return () => {
    if (bridges.get(snippetId) === bridge) bridges.delete(snippetId);
  };
}

export function editorBridge(snippetId: string): EditorBridge | null {
  return bridges.get(snippetId) ?? null;
}
