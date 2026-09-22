import { useState } from 'react';
import { useCanvasStore } from '@/stores/canvasStore';

export default function TagFilterBar() {
  const { tags, activeTagFilters, toggleTagFilter, createTag, deleteTag } = useCanvasStore();
  const [showCreate, setShowCreate] = useState(false);
  const [newName, setNewName] = useState('');
  const [newColor, setNewColor] = useState('#6366f1');

  const tagArray = Array.from(tags.values());

  const handleCreate = async () => {
    if (!newName.trim()) return;
    await createTag(newName.trim(), newColor);
    setNewName('');
    setShowCreate(false);
  };

  if (tagArray.length === 0 && !showCreate) {
    return (
      <div style={{ padding: '4px 16px', borderBottom: '1px solid #2a2a4a', background: '#16162a' }}>
        <button
          onClick={() => setShowCreate(true)}
          style={{ background: 'none', border: 'none', color: '#6366f1', cursor: 'pointer', fontSize: 12 }}
        >
          + Add Tag
        </button>
      </div>
    );
  }

  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 6,
        padding: '4px 16px',
        borderBottom: '1px solid #2a2a4a',
        background: '#16162a',
        flexWrap: 'wrap',
        minHeight: 32,
      }}
    >
      {tagArray.map((tag) => (
        <span
          key={tag.id}
          onClick={() => toggleTagFilter(tag.id)}
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 4,
            padding: '2px 10px',
            borderRadius: 12,
            fontSize: 11,
            cursor: 'pointer',
            background: activeTagFilters.has(tag.id) ? tag.color : '#2a2a4a',
            color: activeTagFilters.has(tag.id) ? '#fff' : '#aaa',
            border: `1px solid ${tag.color}`,
          }}
        >
          {tag.name}
          <span
            onClick={(e) => { e.stopPropagation(); deleteTag(tag.id); }}
            style={{ marginLeft: 4, cursor: 'pointer', opacity: 0.5 }}
          >
            ×
          </span>
        </span>
      ))}

      {showCreate ? (
        <div style={{ display: 'flex', gap: 4, alignItems: 'center' }}>
          <input
            value={newName}
            onChange={(e) => setNewName(e.target.value)}
            placeholder="Tag name"
            autoFocus
            onKeyDown={(e) => e.key === 'Enter' && handleCreate()}
            style={{ background: '#2a2a4a', color: '#e0e0e0', border: '1px solid #3a3a5a', borderRadius: 4, padding: '2px 8px', fontSize: 11, width: 100 }}
          />
          <input
            type="color"
            value={newColor}
            onChange={(e) => setNewColor(e.target.value)}
            style={{ width: 24, height: 24, padding: 0, border: 'none', cursor: 'pointer' }}
          />
          <button onClick={handleCreate} style={{ background: '#6366f1', color: '#fff', border: 'none', borderRadius: 4, padding: '2px 8px', fontSize: 11, cursor: 'pointer' }}>
            Add
          </button>
          <button onClick={() => setShowCreate(false)} style={{ background: 'none', border: 'none', color: '#888', cursor: 'pointer', fontSize: 14 }}>
            ×
          </button>
        </div>
      ) : (
        <button
          onClick={() => setShowCreate(true)}
          style={{ background: 'none', border: 'none', color: '#6366f1', cursor: 'pointer', fontSize: 12 }}
        >
          + Tag
        </button>
      )}
    </div>
  );
}
