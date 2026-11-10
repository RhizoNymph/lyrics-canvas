import { describePlacementError } from './errors';
import type { PlacementResult } from './errors';
import { checkCanvasTitle, titleProblemMessage } from './title';

/**
 * The inline title editor's Enter: check locally (trim, 1..255), then `save` the trimmed title. Resolves the
 * message to show inline, or null when saved.
 */
export async function submitCanvasTitle(
  raw: string,
  save: (title: string) => Promise<PlacementResult<unknown>>,
): Promise<string | null> {
  const check = checkCanvasTitle(raw);
  if (!check.ok) return titleProblemMessage(check.problem);
  const result = await save(check.title);
  return result.ok ? null : describePlacementError(result.error);
}
