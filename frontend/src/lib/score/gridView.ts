/**
 * Read-only views of a grid as the score algorithms see it (spec/score_format.md section 3). Grids are
 * read as JSON: unknown keys are tolerated and preserved by every algorithm that returns a grid.
 */

/** A grid cell as JSON. `GridCell` (types/index.ts) is assignable to it. */
export interface CellJson {
  readonly syllable?: unknown;
  readonly stress?: unknown;
  readonly word_index?: unknown;
  readonly rest?: unknown;
  readonly word?: unknown;
}

export interface LineJson {
  readonly cells?: readonly CellJson[] | null;
  readonly raw_text?: unknown;
  readonly syllable_count?: unknown;
}

export interface GridObjectJson {
  readonly lines?: readonly LineJson[] | null;
}

/** A snippet's `grid_data` as JSON; null is a grid with zero lines. */
export type GridJson = GridObjectJson | null;

export function isRest(cell: CellJson): boolean {
  return cell.rest === true;
}

export function gridLines(grid: GridJson | undefined): readonly LineJson[] {
  return grid?.lines ?? [];
}

export function lineCells(line: LineJson): readonly CellJson[] {
  return line.cells ?? [];
}

/** `syl[k]`: the cell index of the syllable with ordinal `k`. */
export function syllablePositions(cells: readonly CellJson[]): number[] {
  const out: number[] = [];
  cells.forEach((cell, i) => {
    if (!isRest(cell)) out.push(i);
  });
  return out;
}

export function syllableCount(cells: readonly CellJson[]): number {
  let n = 0;
  for (const cell of cells) if (!isRest(cell)) n++;
  return n;
}

export function syllableCounts(grid: GridJson | undefined): number[] {
  return gridLines(grid).map((line) => syllableCount(lineCells(line)));
}

/** For each `k` in `0 .. n-2`: the rest cells between syllables `k` and `k+1`. */
export function restsBetween(cells: readonly CellJson[]): number[] {
  const syl = syllablePositions(cells);
  const out: number[] = [];
  for (let k = 0; k + 1 < syl.length; k++) out.push(syl[k + 1] - syl[k] - 1);
  return out;
}

/** `word_index` when it is an integer (not a boolean), else null. */
export function cellWordIndex(cell: CellJson): number | null {
  const value = cell.word_index;
  return typeof value === 'number' && Number.isInteger(value) ? value : null;
}

function syllableText(cell: CellJson): string {
  return typeof cell.syllable === 'string' ? cell.syllable : '';
}

export interface WordGroup {
  readonly spelling: string;
  readonly ordinals: readonly number[];
}

/**
 * Word groups by `word_index` in order of first appearance; syllables without one stand alone. The
 * spelling is the first non-empty `word` of the group's cells, else the concatenated syllables.
 */
export function wordGroups(cells: readonly CellJson[]): WordGroup[] {
  const order: string[] = [];
  const ordinals = new Map<string, number[]>();
  const syllables = new Map<string, string[]>();
  const spelled = new Map<string, string>();
  let ordinal = 0;
  for (const cell of cells) {
    if (isRest(cell)) continue;
    const index = cellWordIndex(cell);
    const key = index === null ? `anon:${ordinal}` : `w:${index}`;
    if (!ordinals.has(key)) {
      order.push(key);
      ordinals.set(key, []);
      syllables.set(key, []);
    }
    ordinals.get(key)!.push(ordinal);
    syllables.get(key)!.push(syllableText(cell));
    if (index !== null && !spelled.has(key) && typeof cell.word === 'string' && cell.word !== '') spelled.set(key, cell.word);
    ordinal++;
  }
  return order.map((key) => ({ spelling: spelled.get(key) ?? syllables.get(key)!.join(''), ordinals: ordinals.get(key)! }));
}

/** What a rest-only edit must keep per syllable: `[syllable, word_index]` (missing = null). */
export function syllableIdentity(cells: readonly CellJson[]): [unknown, unknown][] {
  return cells.filter((cell) => !isRest(cell)).map((cell) => [cell.syllable ?? null, cell.word_index ?? null]);
}

/**
 * The canonical text of a grid line (rest_marks `rebuild_line_text`, JSON form): `_` per rest, each word
 * once at its first syllable (by its first non-empty `word`, else its syllables), joined by spaces.
 */
export function rebuildLineText(cells: readonly CellJson[]): string {
  const pieces: string[] = [];
  const emitted = new Set<number>();
  for (const cell of cells) {
    if (cell.rest) {
      pieces.push('_');
      continue;
    }
    const index = cellWordIndex(cell);
    if (index === null) {
      pieces.push(syllableText(cell));
      continue;
    }
    if (emitted.has(index)) continue;
    emitted.add(index);
    pieces.push(wordSpelling(cells, index));
  }
  return pieces.filter((piece) => piece !== '').join(' ');
}

function wordSpelling(cells: readonly CellJson[], index: number): string {
  for (const cell of cells) {
    if (cellWordIndex(cell) === index && typeof cell.word === 'string' && cell.word !== '') return cell.word;
  }
  return cells
    .filter((cell) => !cell.rest && cellWordIndex(cell) === index)
    .map(syllableText)
    .join('');
}
