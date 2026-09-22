import { useState, useCallback } from 'react';
import { nlpApi } from '@/api/client';
import BeatGridDisplay from './BeatGridDisplay';
import type { GridData, SnippetType, GridMode } from '@/types';

interface Props {
  initialText: string;
  initialGridData: GridData | null;
  gridMode: GridMode;
  beatsPerLine: number | null;
  snippetType: SnippetType;
  onSave: (data: { raw_text: string; grid_data: GridData; snippet_type: SnippetType; grid_mode: GridMode; beats_per_line: number | null }) => void;
  onCancel: () => void;
}

export default function BeatGridEditor({
  initialText,
  initialGridData,
  gridMode: initialGridMode,
  beatsPerLine: initialBeats,
  snippetType: initialType,
  onSave,
  onCancel,
}: Props) {
  const [text, setText] = useState(initialText);
  const [gridData, setGridData] = useState<GridData | null>(initialGridData);
  const [snippetType, setSnippetType] = useState<SnippetType>(initialType);
  const [gridMode, setGridMode] = useState<GridMode>(initialGridMode);
  const [beatsPerLine, setBeatsPerLine] = useState<number | null>(initialBeats);
  const [loading, setLoading] = useState(false);

  const syllabify = useCallback(async (inputText: string) => {
    if (!inputText.trim()) {
      setGridData({ lines: [] });
      return;
    }
    setLoading(true);
    try {
      const res = await nlpApi.syllabify(inputText);
      let data: GridData = res.data;

      // Apply fixed mode: pad or truncate cells to beatsPerLine
      if (gridMode === 'fixed' && beatsPerLine && beatsPerLine > 0) {
        data = {
          lines: data.lines.map((line) => {
            const cells = [...line.cells];
            while (cells.length < beatsPerLine) cells.push({ rest: true });
            return { ...line, cells: cells.slice(0, beatsPerLine) };
          }),
        };
      }
      setGridData(data);
    } catch {
      // Fallback: create simple grid from text
      setGridData({
        lines: inputText.split('\n').filter(Boolean).map((line) => ({
          cells: line.split(/\s+/).map((word, i) => ({ syllable: word, stress: 0, word_index: i })),
          syllable_count: line.split(/\s+/).length,
          raw_text: line,
        })),
      });
    } finally {
      setLoading(false);
    }
  }, [gridMode, beatsPerLine]);

  const handleTextChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    setText(e.target.value);
  };

  const handleSyllabify = () => syllabify(text);

  const handleSave = () => {
    onSave({
      raw_text: text,
      grid_data: gridData || { lines: [] },
      snippet_type: snippetType,
      grid_mode: gridMode,
      beats_per_line: gridMode === 'fixed' ? beatsPerLine : null,
    });
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
        <select
          value={snippetType}
          onChange={(e) => setSnippetType(e.target.value as SnippetType)}
          style={{ background: '#2a2a4a', color: '#fff', border: '1px solid #3a3a5a', borderRadius: 4, padding: '4px 8px' }}
        >
          <option value="verse">Verse</option>
          <option value="chorus">Chorus</option>
          <option value="bridge">Bridge</option>
          <option value="hook">Hook</option>
          <option value="freestyle">Freestyle</option>
        </select>

        <select
          value={gridMode}
          onChange={(e) => setGridMode(e.target.value as GridMode)}
          style={{ background: '#2a2a4a', color: '#fff', border: '1px solid #3a3a5a', borderRadius: 4, padding: '4px 8px' }}
        >
          <option value="auto">Auto-expand</option>
          <option value="fixed">Fixed beats</option>
        </select>

        {gridMode === 'fixed' && (
          <input
            type="number"
            value={beatsPerLine || 8}
            onChange={(e) => setBeatsPerLine(parseInt(e.target.value) || 8)}
            min={2}
            max={32}
            style={{ background: '#2a2a4a', color: '#fff', border: '1px solid #3a3a5a', borderRadius: 4, padding: '4px 8px', width: 60 }}
          />
        )}

        <button
          onClick={handleSyllabify}
          disabled={loading}
          style={{
            background: '#6366f1',
            color: '#fff',
            border: 'none',
            borderRadius: 4,
            padding: '6px 16px',
            cursor: 'pointer',
            opacity: loading ? 0.6 : 1,
          }}
        >
          {loading ? 'Processing...' : 'Syllabify'}
        </button>
      </div>

      <textarea
        value={text}
        onChange={handleTextChange}
        rows={6}
        style={{
          background: '#2a2a4a',
          color: '#e0e0e0',
          border: '1px solid #3a3a5a',
          borderRadius: 4,
          padding: 8,
          fontFamily: 'monospace',
          fontSize: 13,
          resize: 'vertical',
        }}
        placeholder="Type lyrics line by line..."
        onKeyDown={(e) => {
          if (e.key === 'Enter' && e.ctrlKey) {
            handleSyllabify();
          }
        }}
      />

      {gridData && gridData.lines.length > 0 && (
        <div style={{ background: '#16162a', borderRadius: 6, padding: 8 }}>
          <BeatGridDisplay gridData={gridData} />
        </div>
      )}

      <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
        <button
          onClick={onCancel}
          style={{ background: '#3a3a5a', color: '#ccc', border: 'none', borderRadius: 4, padding: '6px 16px', cursor: 'pointer' }}
        >
          Cancel
        </button>
        <button
          onClick={handleSave}
          style={{ background: '#6366f1', color: '#fff', border: 'none', borderRadius: 4, padding: '6px 16px', cursor: 'pointer' }}
        >
          Save
        </button>
      </div>
    </div>
  );
}
