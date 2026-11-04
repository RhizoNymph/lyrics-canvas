import { checkWordText, describeWordProblem } from '@/lib/wordBank';
import { useWordBankStore } from '@/stores/wordBankStore';
import { btnStyle } from '@/components/rhyme/sidebarStyles';

/**
 * "+ bank" on a rhyme candidate: adds its text to the word bank (result in the toast). Disabled, with
 * the reason as a tooltip, when the text is not a valid bank entry (too long, digits, ...).
 */
export default function AddToBankButton({ text }: { text: string }) {
  const check = checkWordText(text);
  const reason = check.ok ? null : describeWordProblem(check.problem);
  return (
    // The span carries the tooltip: some browsers show none for a disabled button.
    <span title={reason === null ? `Add "${check.ok ? check.text : text}" to the word bank` : `Cannot add to the word bank: ${reason}`}>
      <button
        type="button"
        disabled={reason !== null}
        onClick={() => void useWordBankStore.getState().addFromText(text, 'rhyme')}
        style={{ ...btnStyle, padding: '2px 8px', fontSize: 11, opacity: reason === null ? 1 : 0.5, cursor: reason === null ? 'pointer' : 'not-allowed' }}
      >
        + bank
      </button>
    </span>
  );
}
