/** Canvas title rules shared by rename and create (contract §2 Canvases, §3 Rename). */

export const CANVAS_TITLE_MAX = 255;
export const DEFAULT_CANVAS_TITLE = 'Untitled Canvas';

export type TitleProblem = 'empty' | 'too_long';

export type TitleCheck = { ok: true; title: string } | { ok: false; problem: TitleProblem };

/** Rename: trimmed, 1..255 code points (the server counts code points after a strip). */
export function checkCanvasTitle(raw: string): TitleCheck {
  const title = raw.trim();
  if (title.length === 0) return { ok: false, problem: 'empty' };
  if ([...title].length > CANVAS_TITLE_MAX) return { ok: false, problem: 'too_long' };
  return { ok: true, title };
}

/** Create: a blank title falls back to "Untitled Canvas", as the server does. */
export function checkNewCanvasTitle(raw: string): TitleCheck {
  return raw.trim().length === 0 ? { ok: true, title: DEFAULT_CANVAS_TITLE } : checkCanvasTitle(raw);
}

export function titleProblemMessage(problem: TitleProblem): string {
  switch (problem) {
    case 'empty':
      return 'A canvas title cannot be empty';
    case 'too_long':
      return `Canvas titles are at most ${CANVAS_TITLE_MAX} characters`;
  }
}

export type TitleEditAction = 'save' | 'cancel';

/** Keys of the inline title editor: Enter saves, Escape cancels. */
export function titleEditKey(key: string): TitleEditAction | null {
  if (key === 'Enter') return 'save';
  if (key === 'Escape') return 'cancel';
  return null;
}
