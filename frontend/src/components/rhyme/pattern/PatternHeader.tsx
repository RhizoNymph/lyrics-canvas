import { btnStyle, mutedStyle } from '../sidebarStyles';

interface Props {
  lineStart: number;
  /** Exclusive. */
  lineEnd: number;
  snippetLabel: string;
  /** Other selected snippets, which pattern mode ignores. */
  ignored: number;
  onBack: () => void;
  onReset: () => void;
}

/** "Lines 3–6 of <snippet>", the ignored-selection note, Back to single and Reset. */
export default function PatternHeader({ lineStart, lineEnd, snippetLabel, ignored, onBack, onReset }: Props) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <span style={{ fontSize: 12 }}>
          Lines {lineStart + 1}–{lineEnd} of <i>{snippetLabel}</i>
        </span>
        <span style={{ flex: 1 }} />
        <button type="button" onClick={onReset} style={{ ...btnStyle, fontSize: 11 }} title="Detect the slots again (discards your slot edits)">
          Reset
        </button>
        <button type="button" onClick={onBack} style={{ ...btnStyle, fontSize: 11 }}>
          Back to single
        </button>
      </div>
      {ignored > 0 && (
        <span style={mutedStyle}>
          {ignored === 1 ? 'The other selected snippet is' : `The other ${ignored} selected snippets are`} ignored in pattern mode.
        </span>
      )}
    </div>
  );
}
