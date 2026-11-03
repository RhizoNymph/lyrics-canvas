/** Checks before saving or overwriting a preset: the server rejects empty and invalid expressions too. */
import { format, parse } from '@/lib/tagExpr';
import type { TagExprError } from '@/lib/tagExpr';

export type PresetExpressionCheck =
  | { ok: true; normalized: string }
  | { ok: false; problem: 'empty_preset' }
  | { ok: false; problem: 'invalid_tag_expression'; error: TagExprError };

export function checkPresetExpression(text: string): PresetExpressionCheck {
  const parsed = parse(text);
  if (!parsed.ok) return { ok: false, problem: 'invalid_tag_expression', error: parsed.error };
  if (parsed.expr === null) return { ok: false, problem: 'empty_preset' };
  return { ok: true, normalized: format(parsed.expr) };
}
