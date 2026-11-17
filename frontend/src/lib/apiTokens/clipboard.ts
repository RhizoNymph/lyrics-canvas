/** Copy text with the async Clipboard API (needs a secure context); never throws. */
export interface ClipboardWriter {
  writeText(text: string): Promise<void>;
}

export async function copyToClipboard(text: string, clipboard: ClipboardWriter | undefined): Promise<'copied' | 'failed'> {
  if (clipboard === undefined) return 'failed';
  try {
    await clipboard.writeText(text);
    return 'copied';
  } catch (err) {
    // Only the failure kind is logged: the text may be a secret.
    console.warn('clipboard: write failed', { error: err instanceof Error ? err.name : 'unknown' });
    return 'failed';
  }
}
