/**
 * Rest marks: `_` in lyric text is an intentional empty cell of the beat grid (contract
 * rest-search §1, vectors in spec/rest_mark_vectors.json). Every `_` is one rest and also separates
 * words; whitespace separates tokens; punctuation stays on its word. `wordIndex` counts word tokens
 * only, so rests never shift word indexes.
 *
 * Display text drops the marks: what export, rhyme anchors, the word bank and search consume.
 */

export const REST_MARK = '_';

export type LineToken =
  | { readonly kind: 'word'; readonly text: string; readonly wordIndex: number }
  | { readonly kind: 'rest' };

/** Split a line into word and rest tokens (`_` → ` _ `, then split on whitespace). */
export function tokenizeLine(line: string): LineToken[] {
  const tokens: LineToken[] = [];
  let wordIndex = 0;
  for (const piece of line.split(REST_MARK).join(` ${REST_MARK} `).split(/\s+/)) {
    if (piece === '') continue;
    if (piece === REST_MARK) tokens.push({ kind: 'rest' });
    else tokens.push({ kind: 'word', text: piece, wordIndex: wordIndex++ });
  }
  return tokens;
}

/** The line's words in order (what `line.split()` used to give, minus the rest marks). */
export function lineWords(line: string): string[] {
  const words: string[] = [];
  for (const token of tokenizeLine(line)) if (token.kind === 'word') words.push(token.text);
  return words;
}

/** One line's display text: its words joined by single spaces. */
export function displayLine(line: string): string {
  return lineWords(line).join(' ');
}

/**
 * Display text of a whole snippet: each line's words joined by single spaces; lines that had a rest
 * and no words are dropped, other empty lines (stanza breaks) are kept.
 */
export function displayText(text: string): string {
  const out: string[] = [];
  for (const line of text.split('\n')) {
    const tokens = tokenizeLine(line);
    const words = tokens.flatMap((t) => (t.kind === 'word' ? [t.text] : []));
    if (words.length === 0 && tokens.length > 0) continue;
    out.push(words.join(' '));
  }
  return out.join('\n');
}

/** True when the text has at least one word (blank and rest-only text have none). */
export function hasWords(text: string): boolean {
  return text.split('\n').some((line) => lineWords(line).length > 0);
}
