/**
 * Code point helpers. Every position in the expression language is a Unicode code point offset; DOM
 * inputs report UTF-16 offsets. The two never mix: convert at the DOM boundary with these.
 */

/** An offset counted in Unicode code points. */
export type CpOffset = number & { readonly __unit: 'codepoint' };
/** An offset counted in UTF-16 code units (what `HTMLInputElement.selectionStart` reports). */
export type Utf16Offset = number & { readonly __unit: 'utf16' };

export const cp = (n: number): CpOffset => n as CpOffset;
export const u16 = (n: number): Utf16Offset => n as Utf16Offset;

/** The string split into code points (each element is one code point, possibly two UTF-16 units). */
export function codePoints(s: string): string[] {
  return Array.from(s);
}

export function cpLength(s: string): number {
  let n = 0;
  for (const _ of s) n++;
  return n;
}

/** Clamp `offset` into `[0, cpLength(s)]` and convert it to a UTF-16 offset. */
export function cpToUtf16(s: string, offset: CpOffset): Utf16Offset {
  let units = 0;
  let count = 0;
  for (const ch of s) {
    if (count >= offset) break;
    units += ch.length;
    count++;
  }
  return u16(units);
}

/** Convert a UTF-16 offset to code points; an offset inside a surrogate pair rounds up past the pair. */
export function utf16ToCp(s: string, offset: Utf16Offset): CpOffset {
  let units = 0;
  let count = 0;
  for (const ch of s) {
    if (units >= offset) break;
    units += ch.length;
    count++;
  }
  return cp(count);
}
