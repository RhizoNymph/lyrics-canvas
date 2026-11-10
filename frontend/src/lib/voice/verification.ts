/**
 * Client-side mirror of the server's verification normalization (dataset contract §3), so the check
 * view can refuse an empty or over-long correction before sending it. The server stays authoritative
 * (`invalid_verification`).
 */
export const MAX_VERIFICATION_CODE_POINTS = 10_000;

export function normalizeVerificationText(raw: string): string {
  const lines = raw
    .replace(/\r\n?/g, '\n')
    .split('\n')
    .map((l) => l.replace(/\s+$/u, ''));
  let start = 0;
  let end = lines.length;
  while (start < end && lines[start] === '') start++;
  while (end > start && lines[end - 1] === '') end--;
  const out: string[] = [];
  for (const line of lines.slice(start, end)) {
    if (line === '' && out[out.length - 1] === '') continue;
    out.push(line);
  }
  return out.join('\n');
}

export type VerificationCheck = { ok: true; text: string } | { ok: false; reason: 'empty' | 'too_long' };

export function checkVerificationText(raw: string): VerificationCheck {
  const text = normalizeVerificationText(raw);
  if (text === '') return { ok: false, reason: 'empty' };
  if ([...text].length > MAX_VERIFICATION_CODE_POINTS) return { ok: false, reason: 'too_long' };
  return { ok: true, text };
}
