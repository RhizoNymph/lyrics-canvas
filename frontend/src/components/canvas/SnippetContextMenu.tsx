import { useState, useRef, useEffect, useCallback } from 'react';
import { useCanvasStore } from '@/stores/canvasStore';
import { useUIStore } from '@/stores/uiStore';

const TAG_COLORS = [
  '#ef4444', '#f59e0b', '#22c55e', '#3b82f6',
  '#8b5cf6', '#ec4899', '#14b8a6', '#f97316',
];

export default function SnippetContextMenu() {
  const { contextMenu, closeContextMenu } = useUIStore();
  const { snippets, tags, addTagToSnippet, removeTagFromSnippet, createTag, updateTag, deleteTag, deleteSnippet } = useCanvasStore();

  const [creating, setCreating] = useState(false);
  const [newTagName, setNewTagName] = useState('');
  const [newTagColor, setNewTagColor] = useState(TAG_COLORS[0]);
  const [editingTagId, setEditingTagId] = useState<string | null>(null);
  const [editTagName, setEditTagName] = useState('');
  const [editTagColor, setEditTagColor] = useState('');
  const menuRef = useRef<HTMLDivElement>(null);
  const createInputRef = useRef<HTMLInputElement>(null);
  const editInputRef = useRef<HTMLInputElement>(null);

  // Guards to prevent double-fire from Enter + blur / blur + cleanup
  const createGuard = useRef(false);
  const editGuard = useRef(false);

  // Click-outside to close
  useEffect(() => {
    if (!contextMenu) return;
    const handler = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        closeContextMenu();
      }
    };
    const id = setTimeout(() => document.addEventListener('mousedown', handler), 50);
    return () => {
      clearTimeout(id);
      document.removeEventListener('mousedown', handler);
    };
  }, [contextMenu, closeContextMenu]);

  // Track pending values via refs for the cleanup effect
  const pendingCreateRef = useRef({ name: '', color: TAG_COLORS[0] });
  pendingCreateRef.current = { name: newTagName, color: newTagColor };
  const pendingEditRef = useRef<{ id: string; name: string; color: string } | null>(null);
  pendingEditRef.current = editingTagId ? { id: editingTagId, name: editTagName, color: editTagColor } : null;
  const snippetIdRef = useRef<string | null>(null);
  if (contextMenu) snippetIdRef.current = contextMenu.snippetId;

  // Flush pending create/edit when menu closes
  useEffect(() => {
    return () => {
      const sid = snippetIdRef.current;

      // Flush pending create
      if (!createGuard.current) {
        const pending = pendingCreateRef.current;
        if (pending.name.trim() && sid) {
          createGuard.current = true;
          const store = useCanvasStore.getState();
          store.createTag(pending.name.trim(), pending.color).then((tagId) => {
            createGuard.current = false;
            if (tagId) store.addTagToSnippet(sid, tagId);
          });
        }
      }

      // Flush pending edit
      if (!editGuard.current) {
        const pendingEdit = pendingEditRef.current;
        if (pendingEdit) {
          editGuard.current = true;
          const tag = useCanvasStore.getState().tags.get(pendingEdit.id);
          if (tag && (pendingEdit.name.trim() !== tag.name || pendingEdit.color !== tag.color)) {
            const data: { name?: string; color?: string } = {};
            if (pendingEdit.name.trim() && pendingEdit.name.trim() !== tag.name) data.name = pendingEdit.name.trim();
            if (pendingEdit.color !== tag.color) data.color = pendingEdit.color;
            if (Object.keys(data).length > 0) {
              updateTag(pendingEdit.id, data).then(() => { editGuard.current = false; });
            } else {
              editGuard.current = false;
            }
          } else {
            editGuard.current = false;
          }
        }
      }
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [contextMenu]);

  // Focus input when creating
  useEffect(() => {
    if (creating) createInputRef.current?.focus();
  }, [creating]);

  // Focus input when editing
  useEffect(() => {
    if (editingTagId) editInputRef.current?.focus();
  }, [editingTagId]);

  // Reset state when menu opens for a new snippet
  useEffect(() => {
    setCreating(false);
    setNewTagName('');
    setEditingTagId(null);
    createGuard.current = false;
    editGuard.current = false;
  }, [contextMenu?.snippetId]);

  const handleToggleTag = useCallback(async (tagId: string, assigned: boolean) => {
    if (!contextMenu) return;
    if (assigned) {
      await removeTagFromSnippet(contextMenu.snippetId, tagId);
    } else {
      await addTagToSnippet(contextMenu.snippetId, tagId);
    }
  }, [contextMenu, addTagToSnippet, removeTagFromSnippet]);

  const handleCreateTag = useCallback(async () => {
    if (createGuard.current) return;
    const name = newTagName.trim();
    if (!name) return;
    createGuard.current = true;
    try {
      const tagId = await createTag(name, newTagColor);
      setNewTagName('');
      setCreating(false);
      // Auto-assign to the current snippet
      if (tagId && contextMenu) {
        await addTagToSnippet(contextMenu.snippetId, tagId);
      }
    } finally {
      createGuard.current = false;
    }
  }, [newTagName, newTagColor, createTag, addTagToSnippet, contextMenu]);

  const handleSaveEdit = useCallback(async () => {
    if (editGuard.current || !editingTagId) return;
    const tag = tags.get(editingTagId);
    if (!tag) { setEditingTagId(null); return; }
    const data: { name?: string; color?: string } = {};
    if (editTagName.trim() && editTagName.trim() !== tag.name) data.name = editTagName.trim();
    if (editTagColor !== tag.color) data.color = editTagColor;
    editGuard.current = true;
    try {
      if (Object.keys(data).length > 0) {
        await updateTag(editingTagId, data);
      }
      setEditingTagId(null);
    } finally {
      editGuard.current = false;
    }
  }, [editingTagId, editTagName, editTagColor, tags, updateTag]);

  const startEditTag = useCallback((tagId: string) => {
    const tag = tags.get(tagId);
    if (!tag) return;
    setEditingTagId(tagId);
    setEditTagName(tag.name);
    setEditTagColor(tag.color);
  }, [tags]);

  const handleDeleteTag = useCallback(async (tagId: string) => {
    setEditingTagId(null);
    await deleteTag(tagId);
  }, [deleteTag]);

  const handleDelete = useCallback(async () => {
    if (!contextMenu) return;
    await deleteSnippet(contextMenu.snippetId);
    closeContextMenu();
  }, [contextMenu, deleteSnippet, closeContextMenu]);

  if (!contextMenu) return null;

  const snippet = snippets.get(contextMenu.snippetId);
  if (!snippet) return null;

  const allTags = Array.from(tags.values());
  const assignedTagIds = new Set(snippet.tags.map((t) => t.id));

  const inputStyle = {
    flex: 1,
    minWidth: 0,
    background: 'rgba(255,255,255,0.06)',
    border: '1px solid #4a4a6a',
    borderRadius: 4,
    color: '#d0d0e8',
    padding: '2px 6px',
    fontSize: 12,
    outline: 'none',
  } as const;

  return (
    <div
      ref={menuRef}
      style={{
        position: 'fixed',
        left: contextMenu.x,
        top: contextMenu.y,
        zIndex: 2000,
        background: '#1e1e3a',
        border: '1px solid #3a3a5c',
        borderRadius: 8,
        padding: '4px 0',
        minWidth: 220,
        boxShadow: '0 8px 24px rgba(0,0,0,0.5)',
        fontFamily: 'system-ui, sans-serif',
        fontSize: 13,
        color: '#d0d0e8',
      }}
      onClick={(e) => e.stopPropagation()}
    >
      {/* Tags section header */}
      {allTags.length > 0 && (
        <div style={{ padding: '6px 12px 4px', fontSize: 11, color: '#7a7a9a', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.05em' }}>
          Tags
        </div>
      )}

      {/* Tag list */}
      {allTags.map((tag) => {
        const assigned = assignedTagIds.has(tag.id);
        const isEditing = editingTagId === tag.id;

        if (isEditing) {
          return (
            <div key={tag.id} style={{ padding: '4px 12px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 4, marginBottom: 4 }}>
                {TAG_COLORS.map((c) => (
                  <span
                    key={c}
                    onMouseDown={(e) => e.preventDefault()}
                    onClick={() => setEditTagColor(c)}
                    style={{
                      width: 14,
                      height: 14,
                      borderRadius: '50%',
                      background: c,
                      cursor: 'pointer',
                      border: editTagColor === c ? '2px solid #fff' : '2px solid transparent',
                      flexShrink: 0,
                    }}
                  />
                ))}
                <span
                  onMouseDown={(e) => e.preventDefault()}
                  onClick={() => handleDeleteTag(tag.id)}
                  style={{
                    marginLeft: 'auto',
                    fontSize: 11,
                    color: '#ef4444',
                    cursor: 'pointer',
                    padding: '0 2px',
                  }}
                  title="Delete tag"
                >
                  ✕
                </span>
              </div>
              <input
                ref={editInputRef}
                value={editTagName}
                onChange={(e) => setEditTagName(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') handleSaveEdit();
                  if (e.key === 'Escape') setEditingTagId(null);
                }}
                onBlur={handleSaveEdit}
                style={inputStyle}
              />
            </div>
          );
        }

        return (
          <div
            key={tag.id}
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 8,
              padding: '6px 12px',
              cursor: 'pointer',
              background: assigned ? 'rgba(99, 102, 241, 0.1)' : 'transparent',
            }}
            onMouseEnter={(e) => (e.currentTarget.style.background = 'rgba(99, 102, 241, 0.15)')}
            onMouseLeave={(e) => (e.currentTarget.style.background = assigned ? 'rgba(99, 102, 241, 0.1)' : 'transparent')}
          >
            {/* Checkbox */}
            <span
              onClick={() => handleToggleTag(tag.id, assigned)}
              style={{
                width: 16,
                height: 16,
                borderRadius: 3,
                border: assigned ? 'none' : '2px solid #4a4a6a',
                background: assigned ? '#6366f1' : 'transparent',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontSize: 11,
                color: '#fff',
                flexShrink: 0,
              }}
            >
              {assigned ? '✓' : ''}
            </span>
            {/* Color dot */}
            <span
              onClick={() => handleToggleTag(tag.id, assigned)}
              style={{
                width: 10,
                height: 10,
                borderRadius: '50%',
                background: tag.color,
                flexShrink: 0,
              }}
            />
            {/* Tag name — click to toggle, double-click to edit */}
            <span
              onClick={() => handleToggleTag(tag.id, assigned)}
              onDoubleClick={(e) => { e.stopPropagation(); startEditTag(tag.id); }}
              style={{ flex: 1 }}
            >
              {tag.name}
            </span>
            {/* Edit button */}
            <span
              onClick={(e) => { e.stopPropagation(); startEditTag(tag.id); }}
              style={{
                fontSize: 11,
                color: '#5a5a7a',
                cursor: 'pointer',
                padding: '0 2px',
              }}
              onMouseEnter={(e) => (e.currentTarget.style.color = '#9090b8')}
              onMouseLeave={(e) => (e.currentTarget.style.color = '#5a5a7a')}
              title="Edit tag"
            >
              ✎
            </span>
          </div>
        );
      })}

      {/* Create tag */}
      {!creating ? (
        <div
          onClick={() => setCreating(true)}
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            padding: '6px 12px',
            cursor: 'pointer',
            color: '#8888aa',
          }}
          onMouseEnter={(e) => (e.currentTarget.style.background = 'rgba(99, 102, 241, 0.15)')}
          onMouseLeave={(e) => (e.currentTarget.style.background = 'transparent')}
        >
          <span style={{ width: 16, textAlign: 'center', fontSize: 15 }}>+</span>
          <span>New tag...</span>
        </div>
      ) : (
        <div style={{ padding: '4px 12px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 4, marginBottom: 4 }}>
            {TAG_COLORS.map((c) => (
              <span
                key={c}
                onMouseDown={(e) => e.preventDefault()}
                onClick={() => setNewTagColor(c)}
                style={{
                  width: 14,
                  height: 14,
                  borderRadius: '50%',
                  background: c,
                  cursor: 'pointer',
                  border: newTagColor === c ? '2px solid #fff' : '2px solid transparent',
                  flexShrink: 0,
                }}
              />
            ))}
          </div>
          <input
            ref={createInputRef}
            value={newTagName}
            onChange={(e) => setNewTagName(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') handleCreateTag();
              if (e.key === 'Escape') { setCreating(false); setNewTagName(''); }
            }}
            onBlur={handleCreateTag}
            placeholder="Tag name"
            style={inputStyle}
          />
        </div>
      )}

      {/* Divider */}
      <div style={{ height: 1, background: '#3a3a5c', margin: '4px 0' }} />

      {/* Delete snippet */}
      <div
        onClick={handleDelete}
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          padding: '6px 12px',
          cursor: 'pointer',
          color: '#ef4444',
        }}
        onMouseEnter={(e) => (e.currentTarget.style.background = 'rgba(239, 68, 68, 0.1)')}
        onMouseLeave={(e) => (e.currentTarget.style.background = 'transparent')}
      >
        <span style={{ width: 16, textAlign: 'center', fontSize: 13 }}>✕</span>
        <span>Delete snippet</span>
      </div>
    </div>
  );
}
