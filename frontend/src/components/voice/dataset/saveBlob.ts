/** How long a download's object URL lives; revoking at once can cancel the save in some browsers. */
const REVOKE_AFTER_MS = 60_000;

/** Save a blob under `filename` through a temporary object URL and an `<a download>` click. */
export function saveBlobAs(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.style.display = 'none';
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), REVOKE_AFTER_MS);
}
