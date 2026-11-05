import { describe, it, expect } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { WordBankApiError } from '@/lib/wordBank';
import { bw, wtag } from '@/lib/wordBank/fixtures';
import WordList from './WordList';
import WordRow from './WordRow';
import AddWordsBox from './AddWordsBox';
import SelectionBar from './SelectionBar';
import FamiliesView from './FamiliesView';
import RhymesWithView from './RhymesWithView';
import RelatedView, { UNAVAILABLE_MESSAGE, WARMING_MESSAGE } from './RelatedView';
import DraftView from './DraftView';
import { WordBankToastView } from './WordBankToast';
import AddToBankButton from './AddToBankButton';
import ScoreBar from './ScoreBar';
import { countLabel } from './WordBankPanel';
import type { BankFamiliesResponse } from '@/types/wordBank';

const noop = () => undefined;
const asyncNull = async () => null;
const words = [bw('c', 'moon', 3, [wtag('t1', 'love', '#ff0000')]), bw('b', 'neon lights', 2), bw('a', 'tune', 1)];

describe('countLabel', () => {
  it('is "N words (M shown)"', () => {
    expect(countLabel(12, 3)).toBe('12 words (3 shown)');
    expect(countLabel(1, 1)).toBe('1 word (1 shown)');
  });
});

describe('WordList', () => {
  const render = (props: Partial<Parameters<typeof WordList>[0]> = {}) =>
    renderToStaticMarkup(
      <WordList
        words={words}
        bankEmpty={false}
        selected={new Set(['b'])}
        renamingId={null}
        onRowClick={noop}
        onCheckbox={noop}
        onStartRename={noop}
        onRename={asyncNull}
        onCancelRename={noop}
        {...props}
      />,
    );

  it('renders rows in the given order with tag pills', () => {
    const html = render();
    const order = ['moon', 'neon lights', 'tune'].map((t) => html.indexOf(`>${t}<`));
    expect(order.every((i) => i >= 0)).toBe(true);
    expect([...order].sort((x, y) => x - y)).toEqual(order);
    expect(html).toContain('>love<');
    expect(html).toContain('aria-multiselectable');
  });

  it('marks the selection with aria-selected and checked checkboxes', () => {
    const html = render();
    expect(html).toMatch(/aria-selected="true" data-word-id="b"/);
    expect(html).toMatch(/aria-selected="false" data-word-id="a"/);
    expect((html.match(/checked=""/g) ?? []).length).toBe(1);
    expect(html).toContain('aria-label="Select neon lights"');
  });

  it('shows an inline rename field for the renaming row', () => {
    const html = render({ renamingId: 'a' });
    expect(html).toContain('aria-label="New text for tune"');
    expect(html).toContain('value="tune"');
  });

  it('distinguishes an empty bank from an empty filter result', () => {
    expect(render({ words: [], bankEmpty: true })).toContain('No words yet');
    expect(render({ words: [], bankEmpty: false })).toContain('No word matches the filter');
  });
});

describe('WordRow', () => {
  it('has a rename control', () => {
    const html = renderToStaticMarkup(
      <WordRow word={words[0]} selected={false} renaming={false} onClick={noop} onCheckbox={noop} onStartRename={noop} onRename={asyncNull} onCancelRename={noop} />,
    );
    expect(html).toContain('aria-label="Rename moon"');
  });
});

describe('AddWordsBox', () => {
  const render = (props: Partial<Parameters<typeof AddWordsBox>[0]> = {}) =>
    renderToStaticMarkup(
      <AddWordsBox
        text="night, neon lights"
        problem={null}
        busy={false}
        chips={[wtag('t1', 'love', '#ff0000')]}
        onText={noop}
        onSubmit={noop}
        onRemoveChip={noop}
        tagControl={<button>+ tag</button>}
        {...props}
      />,
    );

  it('shows the chips with remove controls and the tag control', () => {
    const html = render();
    expect(html).toContain('>love');
    expect(html).toContain('aria-label="Do not tag with love"');
    expect(html).toContain('+ tag');
    expect(html).not.toMatch(/<button[^>]*disabled[^>]*>Add<\/button>/);
  });

  it('shows the problem inline and blocks submit', () => {
    const html = render({ problem: '"b4d": Only letters' });
    expect(html).toContain('aria-invalid="true"');
    expect(html).toContain('&quot;b4d&quot;: Only letters');
    expect(html).toMatch(/<button[^>]*disabled[^>]*>Add<\/button>/);
  });

  it('blocks submit when empty', () => {
    expect(render({ text: '  ' })).toMatch(/<button[^>]*disabled[^>]*>Add<\/button>/);
  });
});

describe('SelectionBar', () => {
  const render = (count: number, deleteStep: Parameters<typeof SelectionBar>[0]['deleteStep'] = { kind: 'idle' }) =>
    renderToStaticMarkup(
      <SelectionBar count={count} deleteStep={deleteStep} tagControl={<span>TAGS</span>} onMakeSnippet={noop} onDelete={noop} onCancelDelete={noop} onClear={noop} />,
    );

  it('disables Make snippet and Delete without a selection', () => {
    const html = render(0);
    expect(html).toContain('Nothing selected');
    expect(html).toMatch(/<button[^>]*disabled[^>]*>Make snippet<\/button>/);
    expect(html).toMatch(/<button[^>]*disabled[^>]*>Delete<\/button>/);
  });

  it('enables them with one word selected', () => {
    const html = render(1);
    expect(html).toContain('1 selected');
    expect(html).not.toMatch(/<button[^>]*disabled[^>]*>Make snippet<\/button>/);
    expect(html).toContain('TAGS');
    expect(html).toContain('>Clear<');
  });

  it('asks before deleting many words', () => {
    const html = render(7, { kind: 'confirm', count: 7 });
    expect(html).toContain('role="alertdialog"');
    expect(html).toContain('Delete 7 words?');
    expect(html).toContain('>Cancel<');
  });
});

describe('FamiliesView', () => {
  const value: BankFamiliesResponse = {
    families: [
      { label: 'moon', rhyme_key: ['UW1', 'N'], words: [bw('c', 'moon'), bw('a', 'tune'), bw('d', 'June')], cohesion: 0.91 },
      { label: 'night', rhyme_key: ['AY1', 'T'], words: [bw('b', 'night'), bw('e', 'light')], cohesion: 0.88 },
    ],
    unmatched: [bw('z', 'orange')],
  };
  const render = (state: Parameters<typeof FamiliesView>[0]['state']) =>
    renderToStaticMarkup(
      <FamiliesView state={state} minScore={0.8} selected={new Set(['a'])} onMinScore={noop} onToggleWord={noop} onSelectAll={noop} onRetry={noop} />,
    );

  it('shows families in order with select all, unmatched last', () => {
    const html = render({ kind: 'ready', value });
    const iMoon = html.indexOf('aria-label="Family moon"');
    const iNight = html.indexOf('aria-label="Family night"');
    const iUnmatched = html.indexOf('aria-label="Unmatched"');
    expect(iMoon).toBeGreaterThanOrEqual(0);
    expect(iMoon).toBeLessThan(iNight);
    expect(iNight).toBeLessThan(iUnmatched);
    expect((html.match(/>Select all</g) ?? []).length).toBe(2);
    expect(html).toContain('cohesion 0.91');
    expect(html).toContain('aria-checked="true"');
  });

  it('shows the min score slider at its value', () => {
    const html = render({ kind: 'idle' });
    expect(html).toContain('type="range"');
    expect(html).toContain('value="0.8"');
    expect(html).toContain('0.80');
  });

  it('shows too_many_words with a retry', () => {
    const html = render({ kind: 'failed', error: new WordBankApiError({ kind: 'too_many_words' }, 'families', 'x', 422) });
    expect(html).toMatch(/1000/);
    expect(html).toContain('>Retry<');
  });
});

describe('RhymesWithView', () => {
  const base = {
    text: '',
    minScore: 0.5,
    selected: new Set<string>(),
    onText: noop,
    onMinScore: noop,
    onToggleWord: noop,
    onSelectAll: noop,
  };

  it('asks for exactly one word when none is anchored', () => {
    const html = renderToStaticMarkup(<RhymesWithView {...base} state={{ kind: 'idle' }} selectedWordText={null} selectedCount={3} />);
    expect(html).toContain('3 words are selected; select exactly one');
  });

  it('shows matches with score bars and depth', () => {
    const html = renderToStaticMarkup(
      <RhymesWithView
        {...base}
        selectedWordText="moon"
        selectedCount={1}
        state={{
          kind: 'ready',
          value: {
            anchor: { text: 'moon', rhyme_key: ['UW1', 'N'], syllable_count: 1 },
            matches: [
              { word: bw('a', 'tune'), score: 0.93, match_depth: 2 },
              { word: bw('d', 'June'), score: 0.6, match_depth: 1 },
            ],
          },
        }}
      />,
    );
    expect(html).toContain('2 matches for <b>moon</b>');
    expect(html).toContain('aria-valuenow="93"');
    expect(html).toContain('0.93');
    expect(html).toContain('d2');
    expect(html.indexOf('>tune<')).toBeLessThan(html.indexOf('>June<'));
    expect(html).toContain('Rhymes with &quot;moon&quot;');
  });
});

describe('RelatedView', () => {
  const render = (state: Parameters<typeof RelatedView>[0]['state']) =>
    renderToStaticMarkup(<RelatedView query="sea" state={state} onQuery={noop} onSearch={noop} onAdd={noop} />);

  it('shows "+" or "in bank" per result with the similarity', () => {
    const html = render({
      kind: 'ready',
      q: 'sea',
      value: { query: 'sea', results: [{ text: 'ocean', similarity: 0.71, bank_word_id: null }, { text: 'wave', similarity: 0.6, bank_word_id: 'w9' }] },
    });
    expect(html).toContain('aria-label="Add ocean to the word bank"');
    expect(html).toContain('in bank');
    expect(html).toContain('0.71');
    expect(html).not.toContain('aria-label="Add wave to the word bank"');
  });

  it('shows the warming message with the retry count', () => {
    const html = render({ kind: 'warming', q: 'sea', attempt: 2, retryAfterSeconds: 10 });
    expect(html).toContain(WARMING_MESSAGE);
    expect(html).toContain('Retrying in 10 s (3 of 10)');
  });

  it('shows the unavailable message', () => {
    const html = render({ kind: 'failed', q: 'sea', error: new WordBankApiError({ kind: 'related_words_unavailable' }, 'related', 'x', 503) });
    expect(html).toContain(UNAVAILABLE_MESSAGE);
  });
});

describe('DraftView', () => {
  const render = (props: Partial<Parameters<typeof DraftView>[0]> = {}) =>
    renderToStaticMarkup(
      <DraftView text={'moon\ntune'} type="freestyle" busy={false} error={null} onText={noop} onType={noop} onCreate={noop} onCancel={noop} {...props} />,
    );

  it('is a modal with the editable text and the type select defaulting to freestyle', () => {
    const html = render();
    expect(html).toContain('role="dialog"');
    expect(html).toContain('aria-modal="true"');
    expect(html).toContain('moon\ntune');
    expect(html).toContain('<option value="freestyle" selected="">freestyle</option>');
    expect(html).toContain('<option value="verse">verse</option>');
    expect(html).not.toMatch(/<button[^>]*disabled[^>]*>Create<\/button>/);
  });

  it('cannot create an empty draft and shows errors', () => {
    expect(render({ text: '  ' })).toMatch(/<button[^>]*disabled[^>]*>Create<\/button>/);
    expect(render({ error: 'Could not reach the server' })).toContain('Could not reach the server');
  });
});

describe('WordBankToastView', () => {
  it('uses status for results and alert for failures', () => {
    expect(renderToStaticMarkup(<WordBankToastView notice={{ id: 1, tone: 'info', text: 'Added 2, 1 already in bank' }} onDismiss={noop} />)).toMatch(
      /role="status"[\s\S]*Added 2, 1 already in bank/,
    );
    expect(renderToStaticMarkup(<WordBankToastView notice={{ id: 2, tone: 'error', text: 'x' }} onDismiss={noop} />)).toContain('role="alert"');
  });
});

describe('AddToBankButton', () => {
  it('is enabled for a valid word', () => {
    const html = renderToStaticMarkup(<AddToBankButton text="moonlight" />);
    expect(html).not.toContain('disabled');
    expect(html).toContain('Add &quot;moonlight&quot; to the word bank');
  });

  it('is disabled with the reason for digits or too long text', () => {
    const digits = renderToStaticMarkup(<AddToBankButton text="24 hours" />);
    expect(digits).toContain('disabled');
    expect(digits).toContain('Cannot add to the word bank');
    const long = renderToStaticMarkup(<AddToBankButton text={'a '.repeat(10)} />);
    expect(long).toContain('At most 4 words');
  });
});

describe('ScoreBar', () => {
  it('clamps to 0..100', () => {
    expect(renderToStaticMarkup(<ScoreBar value={1.4} label="score" />)).toContain('aria-valuenow="100"');
  });
});
