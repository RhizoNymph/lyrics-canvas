import type { PatternLine } from '@/types/rhymePattern';
import type { SlotEditState, SlotView, WordAction } from '@/lib/rhyme/pattern';
import { memberAt } from '@/lib/rhyme/pattern';
import { slotAlpha, slotColor } from '../rhymeColors';
import { monoStyle, mutedStyle } from '../sidebarStyles';
import WordPicker from './WordPicker';

export interface PickedWord {
  readonly line: number;
  readonly word: number;
}

interface Props {
  lines: readonly PatternLine[];
  edit: SlotEditState;
  views: readonly SlotView[];
  picked: PickedWord | null;
  /** The picker's entries for `picked`. */
  actions: readonly WordAction[];
  onWordClick: (word: PickedWord) => void;
  onAction: (action: WordAction) => void;
  onClosePicker: () => void;
}

/**
 * The pattern lines, each word a button: slot members carry their slot's colour (partial slots lighter,
 * off slots faint) and letter. Tapping a word opens the picker under its line.
 */
export default function PatternLinesView({ lines, edit, views, picked, actions, onWordClick, onAction, onClosePicker }: Props) {
  const viewOf = new Map(views.map((v) => [v.id, v]));
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
      {lines.map((line) => (
        <div key={line.line_index}>
          <div style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
            <span style={{ ...mutedStyle, ...monoStyle, flex: '0 0 18px' }}>{line.line_index + 1}</span>
            <span style={{ display: 'inline-flex', flexWrap: 'wrap', gap: 3 }}>
              {line.words.length === 0 && <span style={mutedStyle}>(no words)</span>}
              {line.words.map((w) => {
                const held = memberAt(edit, line.line_index, w.word_index);
                const view = held === null ? undefined : viewOf.get(held.slotId);
                const isPicked = picked !== null && picked.line === line.line_index && picked.word === w.word_index;
                const colour = view === undefined ? null : slotColor(view.index);
                return (
                  <button
                    key={w.word_index}
                    type="button"
                    data-slot={view?.letter}
                    onClick={() => onWordClick({ line: line.line_index, word: w.word_index })}
                    title={view === undefined ? 'Tap to add to a slot' : `Slot ${view.letter}`}
                    style={{
                      fontSize: 12,
                      padding: '1px 5px',
                      borderRadius: 3,
                      cursor: 'pointer',
                      border: isPicked ? '1px solid #ffffff' : '1px solid transparent',
                      background: colour ?? '#242448',
                      color: colour === null ? '#d0d0e8' : '#0b1120',
                      opacity: view === undefined ? 1 : slotAlpha(view.on, view.full),
                      fontWeight: view === undefined ? 'normal' : 'bold',
                    }}
                  >
                    {w.text}
                    {view !== undefined && <sup style={{ fontSize: 8, marginLeft: 1 }}>{view.letter}</sup>}
                  </button>
                );
              })}
            </span>
          </div>
          {picked !== null && picked.line === line.line_index && (
            <WordPicker word={line.words.find((w) => w.word_index === picked.word)?.text ?? ''} actions={actions} onAction={onAction} onClose={onClosePicker} />
          )}
        </div>
      ))}
    </div>
  );
}
